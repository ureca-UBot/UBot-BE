\set ON_ERROR_STOP on
-- Run through the reset PowerShell scripts, which replace the CSV copy markers below.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
\if :{?faq_only}
UPDATE unanswered_question_groups SET related_faq_id = NULL, resolved_faq_id = NULL
WHERE related_faq_id IS NOT NULL OR resolved_faq_id IS NOT NULL;
UPDATE unanswered_questions SET best_faq_id = NULL WHERE best_faq_id IS NOT NULL;
DELETE FROM faq_log;
DELETE FROM old_faq;
DELETE FROM faq;
DELETE FROM faq_category;
\else
INSERT INTO users (email, password_hash, name, "role") VALUES
  ('user@user.com', crypt('12345678', gen_salt('bf')), 'Default User', 'USER'),
  ('admin@admin.com', crypt('12345678', gen_salt('bf')), 'Default Admin', 'ADMIN');
\endif

CREATE TEMP TABLE seed_faq (faq_id BIGINT, category TEXT, question TEXT, answer TEXT, intent TEXT) ON COMMIT DROP;
CREATE TEMP TABLE seed_variant (origin_faq_id BIGINT, variant_id BIGINT, question TEXT, vector_text TEXT) ON COMMIT DROP;
-- __FAQ_COPIES__
-- __VARIANT_COPIES__

INSERT INTO faq_category (name) SELECT DISTINCT category FROM seed_faq ORDER BY category;
INSERT INTO faq (id, category_id, question, answer, intent, admin_id)
SELECT seed.faq_id, category.id, seed.question, seed.answer, seed.intent, admin.user_id
FROM seed_faq seed
JOIN faq_category category ON category.name = seed.category
JOIN users admin ON admin.email = 'admin@admin.com';
INSERT INTO faq_question_variants (id, origin_faq_id, question)
SELECT variant_id, origin_faq_id, question FROM seed_variant ORDER BY variant_id;
INSERT INTO embedding_profiles (provider, model_name, dimensions, profile_version)
VALUES ('ollama', 'bge-m3:567m', 1024, 1)
ON CONFLICT (provider, model_name, dimensions, profile_version) DO NOTHING;
INSERT INTO faq_embeddings (faq_question_variant_id, profile_id, vector_type, faq_version, vector)
SELECT seed.variant_id, profile.profile_id, 'QUESTION_ANSWER', faq.version, seed.vector_text::vector(1024)
FROM seed_variant seed
JOIN faq ON faq.id = seed.origin_faq_id
JOIN embedding_profiles profile ON profile.provider = 'ollama'
  AND profile.model_name = 'bge-m3:567m' AND profile.dimensions = 1024 AND profile.profile_version = 1;
SELECT setval(pg_get_serial_sequence('faq', 'id'), (SELECT max(id) FROM faq), true);
SELECT setval(pg_get_serial_sequence('faq_question_variants', 'id'), (SELECT max(id) FROM faq_question_variants), true);
COMMIT;
