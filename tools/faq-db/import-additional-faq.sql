\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE faq_category, faq, faq_question_variants, faq_embeddings IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE seed_faq (source_id BIGINT, category TEXT, question TEXT, answer TEXT, intent TEXT) ON COMMIT DROP;
CREATE TEMP TABLE seed_variant (source_faq_id BIGINT, source_id BIGINT, question TEXT, vector_text TEXT) ON COMMIT DROP;
\copy seed_faq FROM '__FAQ_FILE__' WITH (FORMAT csv, HEADER true)
\copy seed_variant FROM '__VARIANT_FILE__' WITH (FORMAT csv, HEADER true)
DO $$
BEGIN
  IF (SELECT count(*) FROM seed_faq) <> __FAQ_COUNT__ OR
     (SELECT count(*) FROM seed_variant) <> __VARIANT_COUNT__ THEN
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
  IF (SELECT count(*) FROM faq_id_map m JOIN faq f ON f.id = m.db_id) <> __FAQ_COUNT__ OR
     (SELECT count(*) FROM variant_id_map m JOIN faq_question_variants v ON v.id = m.db_id) <> __VARIANT_COUNT__ OR
     (SELECT count(*) FROM variant_id_map m JOIN faq_embeddings e ON e.faq_question_variant_id = m.db_id
      WHERE e.vector_type = 'QUESTION_ANSWER') <> __VARIANT_COUNT__ THEN
    RAISE EXCEPTION 'Inserted FAQ, variant, or vector count differs from staged count';
  END IF;
END $$;
SELECT 'FAQ_ID|' || source_id || '|' || db_id FROM faq_id_map ORDER BY source_id;
COMMIT;
