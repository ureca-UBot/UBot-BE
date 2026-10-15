"""Insert one dated additional FAQ manifest into the existing Compose PostgreSQL DB."""

import argparse
import csv
import json
import re
import subprocess
import tempfile
import uuid
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "src/main/resources/seed"
FAQ_FIELDS = ["faqId", "category", "question", "answer", "intent"]
VARIANT_FIELDS = ["originFaqId", "variantId", "question", "vector"]


def run(*args: str) -> str:
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{' '.join(args[:4])} failed:\n{result.stderr}\n{result.stdout}")
    return result.stdout.strip()


def read_csv(path: Path, fields: list[str]) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != fields:
            raise ValueError(f"Unexpected columns in {path.name}: {reader.fieldnames}")
        return list(reader)


def write_csv(path: Path, fields: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def load_manifest(date: str) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    manifest = json.loads((SEED / f"additional-faq-{date}.json").read_text(encoding="utf-8"))
    if manifest["date"] != date:
        raise ValueError("Manifest date differs from requested date")
    source_ids = [int(value) for value in manifest["faqIds"]]
    if not source_ids or len(source_ids) != len(set(source_ids)):
        raise ValueError("Manifest FAQ IDs are missing or duplicated")
    wanted = set(source_ids)
    faqs: list[dict[str, str]] = []
    variants: list[dict[str, str]] = []
    for part in manifest["parts"]:
        faqs.extend(row for row in read_csv(SEED / f"baseline-faqs-{part}.csv", FAQ_FIELDS)
                    if int(row["faqId"]) in wanted)
        variants.extend(row for row in read_csv(SEED / f"baseline-faq-variants-{part}.csv", VARIANT_FIELDS)
                        if int(row["originFaqId"]) in wanted)
    if {int(row["faqId"]) for row in faqs} != wanted or len(faqs) != len(wanted):
        raise ValueError("Manifest FAQs do not match numbered CSVs")
    counts = Counter(int(row["originFaqId"]) for row in variants)
    if len(variants) != 5 * len(faqs) or any(counts[faq_id] != 5 for faq_id in wanted):
        raise ValueError("Every FAQ needs five variants")
    if len({int(row["variantId"]) for row in variants}) != len(variants):
        raise ValueError("Duplicate variant IDs")
    by_faq = {int(row["faqId"]): row for row in faqs}
    first = {}
    for row in variants:
        first.setdefault(int(row["originFaqId"]), row["question"])
        vector = json.loads(row["vector"])
        if not isinstance(vector, list) or len(vector) != 1024:
            raise ValueError(f"Invalid vector for {row['variantId']}")
    if any(first[faq_id] != by_faq[faq_id]["question"] for faq_id in wanted):
        raise ValueError("Original variant does not match FAQ question")
    return faqs, variants


def sql(faq_file: str, variant_file: str, expected: int) -> str:
    return rf"""\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE faq_category, faq, faq_question_variants, faq_embeddings IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE seed_faq (source_id BIGINT, category TEXT, question TEXT, answer TEXT, intent TEXT) ON COMMIT DROP;
CREATE TEMP TABLE seed_variant (source_faq_id BIGINT, source_id BIGINT, question TEXT, vector_text TEXT) ON COMMIT DROP;
\copy seed_faq FROM '{faq_file}' WITH (FORMAT csv, HEADER true)
\copy seed_variant FROM '{variant_file}' WITH (FORMAT csv, HEADER true)
DO $$
BEGIN
  IF (SELECT count(*) FROM seed_faq) <> {expected} OR
     (SELECT count(*) FROM seed_variant) <> {expected * 5} THEN
    RAISE EXCEPTION 'Unexpected staged FAQ or variant count';
  END IF;
  IF (SELECT count(*) FROM users WHERE email = 'admin@admin.com' AND role = 'ADMIN' AND deleted_at IS NULL) <> 1 THEN
    RAISE EXCEPTION 'Exactly one active default ADMIN is required';
  END IF;
  IF (SELECT count(*) FROM embedding_profiles WHERE provider = 'ollama' AND model_name = 'bge-m3:567m'
      AND dimensions = 1024 AND profile_version = 1) <> 1 THEN
    RAISE EXCEPTION 'Ollama bge-m3:567m profile version 1 is required';
  END IF;
  IF EXISTS (SELECT 1 FROM seed_faq s JOIN faq f ON f.question = s.question AND f.deleted_at IS NULL) THEN
    RAISE EXCEPTION 'One or more FAQ questions already exist; no rows inserted';
  END IF;
END $$;
INSERT INTO faq_category (name)
SELECT DISTINCT category FROM seed_faq
ON CONFLICT (name) WHERE deleted_at IS NULL DO NOTHING;
CREATE TEMP TABLE faq_id_map ON COMMIT DROP AS
SELECT source_id, nextval(pg_get_serial_sequence('faq', 'id')) AS db_id
FROM seed_faq ORDER BY source_id;
INSERT INTO faq (id, category_id, question, answer, intent, admin_id)
SELECT m.db_id, c.id, s.question, s.answer, s.intent, u.user_id
FROM seed_faq s
JOIN faq_id_map m ON m.source_id = s.source_id
JOIN faq_category c ON c.name = s.category AND c.deleted_at IS NULL
CROSS JOIN users u
WHERE u.email = 'admin@admin.com' AND u.role = 'ADMIN' AND u.deleted_at IS NULL;
CREATE TEMP TABLE variant_id_map ON COMMIT DROP AS
SELECT source_id, nextval(pg_get_serial_sequence('faq_question_variants', 'id')) AS db_id
FROM seed_variant ORDER BY source_id;
INSERT INTO faq_question_variants (id, origin_faq_id, question)
SELECT vm.db_id, fm.db_id, s.question
FROM seed_variant s
JOIN variant_id_map vm ON vm.source_id = s.source_id
JOIN faq_id_map fm ON fm.source_id = s.source_faq_id;
INSERT INTO faq_embeddings (faq_question_variant_id, profile_id, vector_type, faq_version, vector)
SELECT vm.db_id, p.profile_id, 'QUESTION_ANSWER', f.version, s.vector_text::vector(1024)
FROM seed_variant s
JOIN variant_id_map vm ON vm.source_id = s.source_id
JOIN faq_id_map fm ON fm.source_id = s.source_faq_id
JOIN faq f ON f.id = fm.db_id
CROSS JOIN embedding_profiles p
WHERE p.provider = 'ollama' AND p.model_name = 'bge-m3:567m'
  AND p.dimensions = 1024 AND p.profile_version = 1;
DO $$
BEGIN
  IF (SELECT count(*) FROM faq_id_map m JOIN faq f ON f.id = m.db_id) <> {expected} OR
     (SELECT count(*) FROM variant_id_map m JOIN faq_question_variants v ON v.id = m.db_id) <> {expected * 5} OR
     (SELECT count(*) FROM variant_id_map m JOIN faq_embeddings e ON e.faq_question_variant_id = m.db_id
      WHERE e.vector_type = 'QUESTION_ANSWER') <> {expected * 5} THEN
    RAISE EXCEPTION 'Inserted FAQ, variant, or vector count differs from staged count';
  END IF;
END $$;
SELECT 'FAQ_ID|' || source_id || '|' || db_id FROM faq_id_map ORDER BY source_id;
COMMIT;
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("date", help="Date stem, for example 1015")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{4,8}", args.date):
        parser.error("date must be 4-8 digits")
    faqs, variants = load_manifest(args.date)
    print(f"Validated {len(faqs)} FAQ and {len(variants)} variant rows", flush=True)
    container = run("docker", "compose", "ps", "--quiet", "postgres")
    if not container:
        raise RuntimeError("Existing Compose PostgreSQL container is not running")
    db = run("docker", "compose", "exec", "-T", "postgres", "printenv", "POSTGRES_DB")
    user = run("docker", "compose", "exec", "-T", "postgres", "printenv", "POSTGRES_USER")
    token = uuid.uuid4().hex
    faq_dest = f"/tmp/ubot-addfaq-{token}-faqs.csv"
    variant_dest = f"/tmp/ubot-addfaq-{token}-variants.csv"
    sql_dest = f"/tmp/ubot-addfaq-{token}.sql"
    destinations = [faq_dest, variant_dest, sql_dest]
    try:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            faq_path = directory / "faqs.csv"
            variant_path = directory / "variants.csv"
            sql_path = directory / "import.sql"
            write_csv(faq_path, FAQ_FIELDS, faqs)
            write_csv(variant_path, VARIANT_FIELDS, variants)
            sql_path.write_text(sql(faq_dest, variant_dest, len(faqs)), encoding="utf-8")
            for local, remote in ((faq_path, faq_dest), (variant_path, variant_dest), (sql_path, sql_dest)):
                run("docker", "cp", str(local), f"{container}:{remote}")
        output = run("docker", "compose", "exec", "-T", "postgres", "psql", "--no-psqlrc",
                     "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-f", sql_dest)
        print(output)
        print(f"Inserted {len(faqs)} FAQs, {len(variants)} variants and QUESTION_ANSWER vectors")
    finally:
        run("docker", "exec", container, "rm", "-f", *destinations)


if __name__ == "__main__":
    main()
