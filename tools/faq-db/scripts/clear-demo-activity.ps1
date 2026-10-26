[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

Invoke-Checked { docker compose up -d postgres } 'Could not start PostgreSQL.'
$containerId = (& docker compose ps --quiet postgres).Trim()
if (-not $containerId) { throw 'Could not locate the PostgreSQL container.' }

$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
if (-not $databaseName -or -not $databaseUser) { throw 'Could not read PostgreSQL connection settings.' }

$before = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT (SELECT count(*) FROM question_log),
       (SELECT count(*) FROM faq_log),
       (SELECT count(*) FROM surging_alerts),
       (SELECT count(*) FROM surging_clusters),
       (SELECT count(*) FROM surging_questions),
       (SELECT count(*) FROM surging_question_embeddings),
       (SELECT count(*) FROM unanswered_questions),
       (SELECT count(*) FROM unanswered_question_groups),
       (SELECT count(*) FROM unanswered_question_embeddings),
       (SELECT count(*) FROM unanswered_group_embeddings),
       (SELECT count(*) FROM unanswered_question_reverifications),
       (SELECT count(*) FROM outbox_events WHERE event_type IN ('UNANSWERED_QUESTION', 'FAQ_REVERIFY', 'SURGING_QUESTION', 'SURGING_ALERT_LABEL')),
       (SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = 'demo_activity_faq_timestamp_backup'),
       (SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = 'demo_product_faq_backup');
'@).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not count existing question and FAQ logs.' }

Invoke-Checked {
    docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
        -U $databaseUser -d $databaseName -c @'
BEGIN;
CREATE TABLE IF NOT EXISTS demo_activity_faq_timestamp_backup (
    faq_id BIGINT PRIMARY KEY,
    original_created_at TIMESTAMP NOT NULL,
    original_updated_at TIMESTAMP NOT NULL,
    original_version INTEGER NOT NULL,
    demo_created_at TIMESTAMP,
    demo_updated_at TIMESTAMP
);
UPDATE faq f
SET created_at = b.original_created_at,
    updated_at = b.original_updated_at
FROM demo_activity_faq_timestamp_backup b
WHERE f.id = b.faq_id
  AND f.version = b.original_version
  AND f.created_at = b.demo_created_at
  AND f.updated_at = b.demo_updated_at;
DROP TABLE demo_activity_faq_timestamp_backup;
DELETE FROM outbox_events WHERE event_type IN ('UNANSWERED_QUESTION', 'FAQ_REVERIFY', 'SURGING_QUESTION', 'SURGING_ALERT_LABEL');
DELETE FROM unanswered_question_reverifications;
DELETE FROM unanswered_question_embeddings;
DELETE FROM unanswered_group_embeddings;
DELETE FROM unanswered_questions;
DELETE FROM unanswered_question_groups;
DELETE FROM surging_alerts;
DELETE FROM surging_question_embeddings;
DELETE FROM surging_questions;
DELETE FROM surging_clusters;
DELETE FROM faq_log;
DELETE FROM question_log;
-- 시연 적용(seed-demo-product-faqs.sql)이 넣은 상품 FAQ만 되돌린다. 기본 CSV로 들어간 상품 FAQ는 백업이 없으므로 그대로 둔다.
-- 적용 뒤 관리자가 고친 FAQ(버전이 다름)는 되돌리지 않는다.
CREATE TABLE IF NOT EXISTS demo_product_faq_backup (
    kind TEXT NOT NULL,
    id BIGINT NOT NULL,
    old_version INTEGER,
    applied_version INTEGER,
    category TEXT,
    question TEXT,
    answer TEXT,
    intent TEXT,
    PRIMARY KEY (kind, id)
);
CREATE TEMP TABLE product_revert ON COMMIT DROP AS
SELECT b.* FROM demo_product_faq_backup b JOIN faq f ON f.id = b.id AND f.version = b.applied_version
WHERE b.kind = 'FAQ_UPDATED';
DELETE FROM faq_embeddings e
USING faq_question_variants v, product_revert r, demo_product_faq_backup b
WHERE e.faq_question_variant_id = v.id AND v.origin_faq_id = r.id
  AND b.kind = 'VARIANT_UPDATED' AND b.id = v.id AND e.faq_version = r.applied_version;
UPDATE faq_embeddings e
SET faq_version = r.old_version, updated_at = CURRENT_TIMESTAMP
FROM faq_question_variants v, product_revert r
WHERE e.faq_question_variant_id = v.id AND v.origin_faq_id = r.id AND e.faq_version = r.applied_version;
UPDATE faq_question_variants v
SET question = b.question
FROM demo_product_faq_backup b, product_revert r
WHERE b.kind = 'VARIANT_UPDATED' AND v.id = b.id AND v.origin_faq_id = r.id;
DELETE FROM old_faq o USING product_revert r WHERE o.faq_id = r.id AND o.version = r.old_version;
UPDATE faq f
SET category_id = c.id, question = r.question, answer = r.answer, intent = r.intent, version = r.old_version
FROM product_revert r
JOIN faq_category c ON c.name = r.category AND c.deleted_at IS NULL
WHERE f.id = r.id;
DELETE FROM faq f USING demo_product_faq_backup b WHERE b.kind = 'FAQ_INSERTED' AND f.id = b.id;
DELETE FROM faq_category c USING demo_product_faq_backup b
WHERE b.kind = 'CATEGORY_CREATED' AND c.id = b.id
  AND NOT EXISTS (SELECT 1 FROM faq f WHERE f.category_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM old_faq o WHERE o.category_id = c.id);
DROP TABLE demo_product_faq_backup;
COMMIT;
'@
} 'Could not clear question logs, FAQ logs, surging data, and unanswered-question data.'

$after = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT (SELECT count(*) FROM question_log),
       (SELECT count(*) FROM faq_log),
       (SELECT count(*) FROM surging_alerts),
       (SELECT count(*) FROM surging_clusters),
       (SELECT count(*) FROM surging_questions),
       (SELECT count(*) FROM surging_question_embeddings),
       (SELECT count(*) FROM unanswered_questions),
       (SELECT count(*) FROM unanswered_question_groups),
       (SELECT count(*) FROM unanswered_question_embeddings),
       (SELECT count(*) FROM unanswered_group_embeddings),
       (SELECT count(*) FROM unanswered_question_reverifications),
       (SELECT count(*) FROM outbox_events WHERE event_type IN ('UNANSWERED_QUESTION', 'FAQ_REVERIFY', 'SURGING_QUESTION', 'SURGING_ALERT_LABEL')),
       (SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = 'demo_activity_faq_timestamp_backup'),
       (SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = 'demo_product_faq_backup');
'@).Trim()
if ($LASTEXITCODE -ne 0 -or $after -ne '0|0|0|0|0|0|0|0|0|0|0|0|0|0') {
    throw "Question/FAQ log, surging alert, and unanswered-question clear verification failed: $after"
}

$beforeParts = $before -split '\|'
Write-Host "Cleared $($beforeParts[0]) question_log rows, $($beforeParts[1]) faq_log rows, $($beforeParts[2]) surging alerts, $($beforeParts[3]) surging clusters, $($beforeParts[4]) surging questions, $($beforeParts[6]) unanswered questions, and $($beforeParts[7]) unanswered-question groups. Restored demo-adjusted FAQ timestamps and reverted demo product FAQs when a backup existed."
