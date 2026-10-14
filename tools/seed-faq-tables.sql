\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE seed_faq (faq_id BIGINT, category TEXT, question TEXT, answer TEXT, intent TEXT) ON COMMIT DROP;
CREATE TEMP TABLE seed_variant (origin_faq_id BIGINT, variant_id BIGINT, question TEXT, vector_text TEXT) ON COMMIT DROP;
\copy seed_faq (faq_id, category, question, answer, intent) FROM '/tmp/baseline-faqs-1.csv' WITH (FORMAT csv, HEADER true)
\copy seed_faq (faq_id, category, question, answer, intent) FROM '/tmp/baseline-faqs-2.csv' WITH (FORMAT csv, HEADER true)
\copy seed_faq (faq_id, category, question, answer, intent) FROM '/tmp/baseline-faqs-3.csv' WITH (FORMAT csv, HEADER true)
\copy seed_faq (faq_id, category, question, answer, intent) FROM '/tmp/baseline-faqs-4.csv' WITH (FORMAT csv, HEADER true)
\copy seed_faq (faq_id, category, question, answer, intent) FROM '/tmp/baseline-faqs-5.csv' WITH (FORMAT csv, HEADER true)
\copy seed_variant (origin_faq_id, variant_id, question, vector_text) FROM '/tmp/baseline-faq-variants-1.csv' WITH (FORMAT csv, HEADER true)
\copy seed_variant (origin_faq_id, variant_id, question, vector_text) FROM '/tmp/baseline-faq-variants-2.csv' WITH (FORMAT csv, HEADER true)
\copy seed_variant (origin_faq_id, variant_id, question, vector_text) FROM '/tmp/baseline-faq-variants-3.csv' WITH (FORMAT csv, HEADER true)
\copy seed_variant (origin_faq_id, variant_id, question, vector_text) FROM '/tmp/baseline-faq-variants-4.csv' WITH (FORMAT csv, HEADER true)
\copy seed_variant (origin_faq_id, variant_id, question, vector_text) FROM '/tmp/baseline-faq-variants-5.csv' WITH (FORMAT csv, HEADER true)

DO $$
BEGIN
  IF (SELECT count(*) FROM users WHERE email = 'admin@admin.com' AND role = 'ADMIN' AND deleted_at IS NULL) <> 1 THEN
    RAISE EXCEPTION 'One active admin@admin.com ADMIN account is required';
  END IF;
  IF (SELECT count(*) FROM embedding_profiles WHERE provider = 'ollama' AND model_name = 'bge-m3:567m'
      AND dimensions = 1024 AND profile_version = 1) <> 1 THEN
    RAISE EXCEPTION 'Ollama bge-m3:567m embedding profile is required';
  END IF;
  IF EXISTS (SELECT category FROM seed_faq EXCEPT SELECT name FROM faq_category WHERE deleted_at IS NULL) THEN
    RAISE EXCEPTION 'Seed contains a category missing from active faq_category';
  END IF;
  IF EXISTS (SELECT 1 FROM faq_log) OR EXISTS (SELECT 1 FROM old_faq)
     OR EXISTS (SELECT 1 FROM unanswered_questions WHERE best_faq_id IS NOT NULL)
     OR EXISTS (SELECT 1 FROM unanswered_question_groups
                WHERE related_faq_id IS NOT NULL OR resolved_faq_id IS NOT NULL) THEN
    RAISE EXCEPTION 'Other tables reference FAQ rows; three-table reset would alter or invalidate them';
  END IF;
END $$;

DELETE FROM faq_embeddings;
DELETE FROM faq_question_variants;
DELETE FROM faq;

INSERT INTO faq (id, category_id, question, answer, intent, admin_id)
SELECT seed.faq_id, category.id, seed.question, seed.answer, seed.intent, admin.user_id
FROM seed_faq seed
JOIN faq_category category ON category.name = seed.category AND category.deleted_at IS NULL
JOIN users admin ON admin.email = 'admin@admin.com' AND admin.role = 'ADMIN' AND admin.deleted_at IS NULL;
INSERT INTO faq_question_variants (id, origin_faq_id, question)
SELECT variant_id, origin_faq_id, question FROM seed_variant ORDER BY variant_id;
INSERT INTO faq_embeddings (faq_question_variant_id, profile_id, vector_type, faq_version, vector)
SELECT seed.variant_id, profile.profile_id, 'QUESTION_ANSWER', faq.version, seed.vector_text::vector(1024)
FROM seed_variant seed
JOIN faq ON faq.id = seed.origin_faq_id
JOIN embedding_profiles profile ON profile.provider = 'ollama'
  AND profile.model_name = 'bge-m3:567m' AND profile.dimensions = 1024 AND profile.profile_version = 1;

SELECT setval(pg_get_serial_sequence('faq', 'id'), 1000, true);
SELECT setval(pg_get_serial_sequence('faq_question_variants', 'id'), 5000, true);
COMMIT;
