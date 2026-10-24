\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE faq_category, faq, faq_question_variants, old_faq, faq_embeddings IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE seed_update (
  faq_id BIGINT, category TEXT, question TEXT, answer TEXT, intent TEXT,
  before_category TEXT, before_question TEXT, before_answer TEXT, before_intent TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE seed_variant_update (variant_id BIGINT, origin_faq_id BIGINT, before_question TEXT, question TEXT) ON COMMIT DROP;
CREATE TEMP TABLE seed_delete (faq_id BIGINT, question TEXT) ON COMMIT DROP;
\copy seed_update FROM '__UPDATE_FILE__' WITH (FORMAT csv, HEADER true)
\copy seed_variant_update FROM '__VARIANT_FILE__' WITH (FORMAT csv, HEADER true)
\copy seed_delete FROM '__DELETE_FILE__' WITH (FORMAT csv, HEADER true)
DO $$
BEGIN
  IF (SELECT count(*) FROM seed_update) <> __UPDATE_COUNT__ OR
     (SELECT count(*) FROM seed_variant_update) <> __VARIANT_COUNT__ OR
     (SELECT count(*) FROM seed_delete) <> __DELETE_COUNT__ THEN
    RAISE EXCEPTION 'Unexpected staged FAQ, variant or delete count';
  END IF;
  IF (SELECT count(*) FROM users WHERE email = 'admin@admin.com' AND role = 'ADMIN' AND deleted_at IS NULL) <> 1 THEN
    RAISE EXCEPTION 'Exactly one active default ADMIN is required';
  END IF;
  IF EXISTS (
    SELECT 1 FROM (SELECT DISTINCT intent FROM seed_update) staged
    WHERE NOT EXISTS (
      SELECT 1 FROM pg_constraint c
      WHERE c.conrelid = 'faq'::regclass AND c.conname = 'faq_intent_check'
        AND pg_get_constraintdef(c.oid) LIKE '%' || quote_literal(staged.intent) || '%')
  ) THEN
    RAISE EXCEPTION 'faq_intent_check does not allow every staged intent; apply the Flyway migrations first';
  END IF;
END $$;

-- A FAQ is updated only while the DB still holds the pre-change seed values, so admin edits are never overwritten.
CREATE TEMP TABLE update_target ON COMMIT DROP AS
SELECT s.*, f.version AS old_version,
  CASE
    WHEN f.id IS NULL THEN 'missing'
    WHEN f.deleted_at IS NOT NULL THEN 'deleted'
    WHEN c.name = s.category AND f.question = s.question AND f.answer = s.answer AND f.intent = s.intent
         AND NOT EXISTS (
           SELECT 1 FROM seed_variant_update v
           LEFT JOIN faq_question_variants d ON d.id = v.variant_id
           WHERE v.origin_faq_id = s.faq_id
             AND (d.origin_faq_id IS DISTINCT FROM s.faq_id OR d.question IS DISTINCT FROM v.question))
      THEN 'already applied'
    WHEN c.name IS DISTINCT FROM s.before_category OR f.question IS DISTINCT FROM s.before_question
         OR f.answer IS DISTINCT FROM s.before_answer OR f.intent IS DISTINCT FROM s.before_intent
      THEN 'FAQ differs from the pre-change seed'
    WHEN EXISTS (
           SELECT 1 FROM seed_variant_update v
           LEFT JOIN faq_question_variants d ON d.id = v.variant_id
           WHERE v.origin_faq_id = s.faq_id
             AND (d.origin_faq_id IS DISTINCT FROM s.faq_id OR d.question IS DISTINCT FROM v.before_question))
      THEN 'variant differs from the pre-change seed'
  END AS skip_reason
FROM seed_update s
LEFT JOIN faq f ON f.id = s.faq_id
LEFT JOIN faq_category c ON c.id = f.category_id;

INSERT INTO faq_category (name)
SELECT DISTINCT category FROM update_target WHERE skip_reason IS NULL
ON CONFLICT (name) WHERE deleted_at IS NULL DO NOTHING;

-- Same history row as FaqService.updateActiveFaq (OldFaq.from) before the FAQ changes.
INSERT INTO old_faq (faq_id, version, category_id, question, answer, updated_at, updated_by, created_by, intent, cacheable)
SELECT f.id, f.version, f.category_id, f.question, f.answer, f.updated_at, u.user_id, f.admin_id, f.intent, f.cacheable
FROM update_target t
JOIN faq f ON f.id = t.faq_id
CROSS JOIN users u
WHERE t.skip_reason IS NULL
  AND u.email = 'admin@admin.com' AND u.role = 'ADMIN' AND u.deleted_at IS NULL;

UPDATE faq f
SET category_id = c.id, question = t.question, answer = t.answer, intent = t.intent,
    version = f.version + 1, updated_at = CURRENT_TIMESTAMP
FROM update_target t
JOIN faq_category c ON c.name = t.category AND c.deleted_at IS NULL
WHERE f.id = t.faq_id AND t.skip_reason IS NULL;

UPDATE faq_question_variants d
SET question = v.question
FROM seed_variant_update v
JOIN update_target t ON t.faq_id = v.origin_faq_id AND t.skip_reason IS NULL
WHERE d.id = v.variant_id;

-- Variants whose text did not change keep their QUESTION vectors at the new version (as FaqService does),
-- so only changed variants wait for the embedding backfill.
UPDATE faq_embeddings e
SET faq_version = t.old_version + 1, updated_at = CURRENT_TIMESTAMP
FROM faq_question_variants d
JOIN update_target t ON t.faq_id = d.origin_faq_id AND t.skip_reason IS NULL
WHERE e.faq_question_variant_id = d.id
  AND e.vector_type = 'QUESTION'
  AND e.faq_version = t.old_version
  AND NOT EXISTS (SELECT 1 FROM seed_variant_update v WHERE v.variant_id = d.id);

-- Soft delete (FaqService.deleteFaq only sets deleted_at). A FAQ whose question no longer matches the seed is skipped.
CREATE TEMP TABLE delete_target ON COMMIT DROP AS
SELECT s.faq_id,
  CASE
    WHEN f.id IS NULL THEN 'missing'
    WHEN f.deleted_at IS NOT NULL THEN 'already deleted'
    WHEN f.question IS DISTINCT FROM s.question THEN 'question differs from the seed'
  END AS skip_reason
FROM seed_delete s
LEFT JOIN faq f ON f.id = s.faq_id;

UPDATE faq f
SET deleted_at = CURRENT_TIMESTAMP
FROM delete_target d
WHERE f.id = d.faq_id AND d.skip_reason IS NULL;

SELECT line FROM (
  SELECT 1 AS kind, t.faq_id, 'UPDATED|' || t.faq_id || '|' || (t.old_version + 1) || '|' ||
         CASE WHEN EXISTS (SELECT 1 FROM seed_variant_update v WHERE v.origin_faq_id = t.faq_id) THEN 1 ELSE 0 END AS line
  FROM update_target t WHERE t.skip_reason IS NULL
  UNION ALL
  SELECT 2, faq_id, 'SKIPPED|' || faq_id || '|' || skip_reason FROM update_target WHERE skip_reason IS NOT NULL
  UNION ALL
  SELECT 3, faq_id, 'DELETED|' || faq_id FROM delete_target WHERE skip_reason IS NULL
  UNION ALL
  SELECT 4, faq_id, 'DELETE_SKIPPED|' || faq_id || '|' || skip_reason FROM delete_target WHERE skip_reason IS NOT NULL
) report
ORDER BY kind, faq_id;
__END_TRANSACTION__;
