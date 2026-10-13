"""Legacy experiment upsert tool. Default: validate only.

Catalog omission does not deactivate stored expressions; --apply enables every
provided expression. Use the Java administrator API for operational management.
Imports retain normalization_version=0 and use raw text as a provisional key;
they cannot participate in semantic caching until the Java API re-registers them.
"""
import argparse
import json
import math
import subprocess
import urllib.request
from pathlib import Path


def literal(value): return "'" + value.replace("'", "''") + "'"


def database(args, sql):
    result = subprocess.run(['docker','exec','-i',args.container,'psql','-X','-qAt',
                             '-v','ON_ERROR_STOP=1','-U',args.user,'-d',args.database,'-f','-'],
                            input=sql,capture_output=True,text=True,encoding='utf-8')
    if result.returncode: raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def query(args, sql): return json.loads(database(args,sql))


def embed(url, profile, texts):
    if profile['provider']=='ollama':
        endpoint = url.rstrip('/')+'/api/embed'
        payload = {'model':profile['model_name'],'input':texts,'truncate':False,
                   'options':{'num_ctx':4096},'keep_alive':'5m'}
    else:
        endpoint = url.rstrip('/')+'/embeddings'
        payload = {'model':profile['model_name'],'input':texts}
    request = urllib.request.Request(endpoint,data=json.dumps(payload,ensure_ascii=False).encode('utf-8'),
                                     headers={'Content-Type':'application/json; charset=utf-8'})
    with urllib.request.urlopen(request,timeout=120) as response: result=json.load(response)
    if profile['provider']=='ollama': vectors=result['embeddings']
    else:
        rows=sorted(result['data'],key=lambda r:r['index'])
        if [r['index'] for r in rows]!=list(range(len(texts))): raise ValueError('Invalid embedding response indices')
        vectors=[r['embedding'] for r in rows]
    if len(vectors)!=len(texts): raise ValueError('Embedding count differs from input')
    normalized=[]
    for vector in vectors:
        if len(vector)!=profile['dimensions'] or not all(isinstance(x,(int,float)) and math.isfinite(x) for x in vector):
            raise ValueError('Invalid vector dimensions/values')
        norm=math.sqrt(sum(x*x for x in vector))
        if norm==0: raise ValueError('Zero vector')
        normalized.append([x/norm for x in vector])
    return normalized


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container',required=True,help='Explicit PostgreSQL container for the new schema')
    parser.add_argument('--profile-id',required=True,type=int)
    parser.add_argument('--catalog',type=Path,default=Path(__file__).with_name('catalog-reviewed.json'))
    parser.add_argument('--user',default='ubot'); parser.add_argument('--database',default='ubot')
    parser.add_argument('--embedding-url',help='Ollama base URL or OpenAI-compatible URL including /v1')
    parser.add_argument('--apply',action='store_true',help='Embed and write; without this flag, only validate')
    args=parser.parse_args()
    if args.profile_id<=0: parser.error('--profile-id must be positive')
    profile=query(args,f"SELECT row_to_json(p) FROM embedding_profiles p WHERE profile_id={args.profile_id};")
    if not profile or profile['dimensions']!=1024: raise ValueError('Missing or unsupported embedding profile')
    aliases=json.loads(args.catalog.read_text(encoding='utf-8-sig'))['aliases']
    if not aliases or len({r['faqId'] for r in aliases})!=len(aliases): raise ValueError('Catalog FAQ IDs must be unique and nonempty')
    ids=','.join(str(int(r['faqId'])) for r in aliases)
    faqs=query(args,f"""SELECT coalesce(json_agg(row_to_json(t)),'[]') FROM (
        SELECT f.id,f.question,f.answer,f.version,f.intent,f.cacheable,
               EXISTS(SELECT 1 FROM faq_embeddings fe WHERE fe.faq_id=f.id AND fe.profile_id={args.profile_id}
                      AND fe.vector_type='QUESTION' AND fe.faq_version=f.version) AS current_vector
        FROM faq f WHERE f.deleted_at IS NULL AND f.id IN ({ids}) ORDER BY f.id) t;""")
    current={r['id']:r for r in faqs}; entries=[]; seen=set()
    for alias in aliases:
        faq=current.get(alias['faqId'])
        if not faq or not faq['cacheable'] or faq['intent']!='GENERAL' or not faq['current_vector']:
            raise ValueError(f"FAQ {alias['faqId']} requires active GENERAL approval and a current profile question vector")
        if (faq['question'],faq['answer'])!=(alias['reviewedFaqQuestion'],alias['reviewedFaqAnswer']):
            raise ValueError(f"FAQ {faq['id']} differs from the reviewed answer; re-review before registration")
        for question in dict.fromkeys([alias['reviewedFaqQuestion'],*alias['questions']]):
            if not isinstance(question,str) or not question.strip(): raise ValueError('Empty/non-text question')
            key=(faq['id'],question)
            if key in seen: continue
            seen.add(key)
            entries.append({'faq_id':faq['id'],'profile_id':args.profile_id,'question':question,
                            'faq_version':faq['version'],'faq_question':faq['question'],'faq_answer':faq['answer']})
    print(f"Validated {len(entries)} expressions / {len(aliases)} FAQs / profile {args.profile_id} "
          f"({profile['provider']}, {profile['model_name']}, version {profile['profile_version']})",flush=True)
    if not args.apply:
        print('Validation only. No model calls or writes. Add --apply after review.',flush=True); return
    url=args.embedding_url or ('http://localhost:11435' if profile['provider']=='ollama' else 'http://localhost:8001/v1')
    for start in range(0,len(entries),8):
        batch=entries[start:start+8]
        for entry,vector in zip(batch,embed(url,profile,[r['question'] for r in batch])):
            entry['vector']=json.dumps(vector,separators=(',',':'))
    payload=literal(json.dumps(entries,ensure_ascii=False))
    # Data stays outside dollar-quoted procedural SQL. Lock FAQ rows and verify all
    # snapshots after embedding, before any persistent insert, in one transaction.
    sql=f"""BEGIN;
        CREATE TEMP TABLE semantic_import_rows (
            faq_id bigint,profile_id bigint,question text,vector text,
            faq_version integer,faq_question text,faq_answer text
        ) ON COMMIT DROP;
        INSERT INTO semantic_import_rows
        SELECT * FROM jsonb_to_recordset({payload}::jsonb) AS r(
            faq_id bigint,profile_id bigint,question text,vector text,
            faq_version integer,faq_question text,faq_answer text);
        DO $validation$
        BEGIN
            PERFORM f.id FROM faq f WHERE f.id IN (SELECT faq_id FROM semantic_import_rows)
                ORDER BY f.id FOR UPDATE;
            IF EXISTS(SELECT 1 FROM semantic_import_rows r LEFT JOIN faq f ON f.id=r.faq_id
                WHERE f.id IS NULL OR f.deleted_at IS NOT NULL OR NOT f.cacheable OR f.intent<>'GENERAL'
                    OR f.version<>r.faq_version OR f.question IS DISTINCT FROM r.faq_question
                    OR f.answer IS DISTINCT FROM r.faq_answer
                    OR NOT EXISTS(SELECT 1 FROM faq_embeddings fe WHERE fe.faq_id=f.id
                        AND fe.profile_id=r.profile_id AND fe.vector_type='QUESTION' AND fe.faq_version=f.version))
            THEN RAISE EXCEPTION 'FAQ approval/content/vector changed; no expressions registered'; END IF;
            IF EXISTS(SELECT 1 FROM semantic_import_rows r JOIN faq_cache_question c
                ON c.faq_id=r.faq_id AND c.profile_id=r.profile_id AND c.normalized_question=r.question
                WHERE c.normalization_version<>0)
            THEN RAISE EXCEPTION 'Java-managed expressions require the administrator API; no expressions registered'; END IF;
        END $validation$;
        INSERT INTO faq_cache_question(faq_id,profile_id,question,normalized_question,normalization_version,
            vector,faq_version,faq_question,faq_answer,enabled)
        SELECT faq_id,profile_id,question,question,0,vector::vector,faq_version,faq_question,faq_answer,true
        FROM semantic_import_rows
        ON CONFLICT(faq_id,normalized_question,profile_id) DO UPDATE SET
            vector=EXCLUDED.vector,faq_version=EXCLUDED.faq_version,
            faq_question=EXCLUDED.faq_question,faq_answer=EXCLUDED.faq_answer,enabled=true;
        COMMIT;"""
    database(args,sql)
    print(f'Registered {len(entries)} reviewed expressions. Administrator approvals were not changed.',flush=True)


if __name__=='__main__': main()
