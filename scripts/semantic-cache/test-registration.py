"""Exercise the registration CLI using an owned disposable DB and fake embedding HTTP server."""
import argparse
import http.server
import json
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--image',default='ubot-postgres:18-pgvector0.8.6-postgis3.6.4')
    args=p.parse_args()
    name='ubot-semantic-registration-'+uuid.uuid4().hex[:12]
    container_id=None; server=None; requests=[]; revoke_next=[False]

    def docker(*command,sql=None,check=True):
        return subprocess.run(['docker',*command],input=sql,capture_output=True,text=True,encoding='utf-8',check=check)

    def db(sql):
        return docker('exec','-i',name,'psql','-X','-qAt','-v','ON_ERROR_STOP=1',
                      '-U','ubot_test','-d','ubot_test','-f','-',sql=sql).stdout.strip()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self,*values): pass
        def do_POST(self):
            body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append({'path':self.path,'model':body['model'],'count':len(body['input'])})
            if revoke_next[0]:
                db('UPDATE faq SET cacheable=false WHERE id=14;'); revoke_next[0]=False
            vectors=[[1.0]+[0.0]*1023 for _ in body['input']]
            payload={'embeddings':vectors} if self.path=='/api/embed' else {
                'data':[{'index':i,'embedding':vector} for i,vector in enumerate(vectors)]}
            encoded=json.dumps(payload).encode('utf-8')
            self.send_response(200);self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(encoded)));self.end_headers();self.wfile.write(encoded)

    try:
        container_id=docker('run','--rm','-d','--name',name,'--label','com.ubot.test-db=true',
                            '-e','POSTGRES_USER=ubot_test','-e','POSTGRES_DB=ubot_test',
                            '-e','POSTGRES_PASSWORD='+uuid.uuid4().hex,args.image).stdout.strip()
        deadline=time.monotonic()+45
        while docker('exec',name,'pg_isready','-U','ubot_test','-d','ubot_test',check=False).returncode:
            if time.monotonic()>deadline: raise RuntimeError('Test DB did not become ready')
            time.sleep(.5)
        db("""CREATE EXTENSION vector;
            CREATE TABLE faq(id bigint PRIMARY KEY,question text,answer text,version integer,intent text,
                             cacheable boolean,deleted_at timestamp);
            CREATE TABLE embedding_profiles(profile_id bigint PRIMARY KEY,provider text,model_name text,
                                            dimensions integer,profile_version integer);
            CREATE TABLE faq_embeddings(faq_id bigint,profile_id bigint,vector_type text,faq_version integer,vector vector(1024));
            INSERT INTO embedding_profiles VALUES(1,'ollama','bge-m3:567m',1024,1),
                                                 (2,'openai-compatible','ubot-embedding',1024,1);
            INSERT INTO faq VALUES(14,'eSIM이 무엇인가요?','공식 정의',1,'GENERAL',true,NULL);""")
        db((ROOT/'src/main/resources/db/migration/V30__create_faq_cache_questions.sql').read_text(encoding='utf-8'))
        vector=json.dumps([1.0]+[0.0]*1023)
        db(f"INSERT INTO faq_embeddings VALUES(14,1,'QUESTION',1,'{vector}'),(14,2,'QUESTION',1,'{vector}');")
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        threading.Thread(target=server.serve_forever,daemon=True).start()
        url=f'http://127.0.0.1:{server.server_port}'
        with tempfile.TemporaryDirectory(prefix='ubot-reviewed-catalog-') as temp:
            catalog=Path(temp)/'catalog.json'
            data={'aliases':[{'faqId':14,'reviewedFaqQuestion':'eSIM이 무엇인가요?',
                              'reviewedFaqAnswer':'공식 정의','questions':["eSIM이 뭐야? $$ '"]}]}
            catalog.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')

            def invoke(profile_id,apply=False):
                command=[sys.executable,'-X','utf8',str(HERE/'register-reviewed-expressions.py'),
                         '--container',name,'--user','ubot_test','--database','ubot_test',
                         '--profile-id',str(profile_id),'--catalog',str(catalog),'--embedding-url',
                         url if profile_id==1 else url+'/v1']
                if apply: command.append('--apply')
                return subprocess.run(command,capture_output=True,text=True,encoding='utf-8')

            result=invoke(1)
            assert result.returncode==0,result.stderr
            assert not requests and db('SELECT count(*) FROM faq_cache_question;')=='0'
            for _ in range(2):
                result=invoke(1,True);assert result.returncode==0,result.stderr
            assert db('SELECT count(*) FROM faq_cache_question WHERE profile_id=1;')=='2'
            assert db('SELECT count(*) FROM faq_cache_question WHERE normalization_version<>0;')=='0'
            assert db("SELECT question FROM faq_cache_question WHERE question LIKE '%$$%';")=="eSIM이 뭐야? $$ '"
            result=invoke(2,True);assert result.returncode==0,result.stderr
            assert db('SELECT count(*) FROM faq_cache_question WHERE profile_id=2;')=='2'
            assert requests[-1]['path']=='/v1/embeddings' and requests[-1]['model']=='ubot-embedding'
            data['aliases'][0]['questions'].append('추가 검토 표현')
            catalog.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
            revoke_next[0]=True
            result=invoke(1,True)
            assert result.returncode!=0 and 'no expressions registered' in result.stderr
            assert db('SELECT count(*) FROM faq_cache_question;')=='4'
            previous_requests=len(requests);result=invoke(1,True)
            assert result.returncode!=0 and len(requests)==previous_requests
            db("UPDATE faq SET cacheable=true WHERE id=14;")
            db("UPDATE faq_cache_question SET normalization_version=1,enabled=false "
               "WHERE profile_id=1 AND question='eSIM이 무엇인가요?';")
            result=invoke(1,True)
            assert result.returncode!=0 and 'Java-managed expressions require the administrator API' in result.stderr
            assert db('SELECT count(*) FROM faq_cache_question;')=='4'
            assert db('SELECT count(*) FROM faq_cache_question WHERE normalization_version=1 AND NOT enabled;')=='1'
            print(json.dumps({'dryRunHasNoWritesOrModelCalls':True,'registrationIdempotent':True,
                  'quotedTextPreserved':True,'bothProvidersIsolated':True,
                  'revocationDuringEmbeddingRollsBack':True,'unapprovedFaqHasNoModelCalls':True,
                  'legacyImportsRemainUnversioned':True,'javaManagedExpressionsPreserved':True,
                  'realModelCalls':0,'existingDatabaseTouched':False}),flush=True)
    finally:
        if server: server.shutdown();server.server_close()
        if container_id:
            owned=docker('inspect',name,'--format','{{.Id}} {{index .Config.Labels "com.ubot.test-db"}}',check=False)
            if owned.returncode==0 and owned.stdout.strip()==container_id+' true':
                docker('rm','-f',name)


if __name__=='__main__': main()
