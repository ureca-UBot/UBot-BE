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
       (SELECT count(*) FROM outbox_events WHERE event_type IN ('UNANSWERED_QUESTION', 'FAQ_REVERIFY', 'SURGING_QUESTION', 'SURGING_ALERT_LABEL'));
'@).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not count existing question and FAQ logs.' }

Invoke-Checked {
    docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
        -U $databaseUser -d $databaseName -c @'
BEGIN;
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
       (SELECT count(*) FROM outbox_events WHERE event_type IN ('UNANSWERED_QUESTION', 'FAQ_REVERIFY', 'SURGING_QUESTION', 'SURGING_ALERT_LABEL'));
'@).Trim()
if ($LASTEXITCODE -ne 0 -or $after -ne '0|0|0|0|0|0|0|0|0|0|0|0') {
    throw "Question/FAQ log, surging alert, and unanswered-question clear verification failed: $after"
}

$beforeParts = $before -split '\|'
Write-Host "Cleared $($beforeParts[0]) question_log rows, $($beforeParts[1]) faq_log rows, $($beforeParts[2]) surging alerts, $($beforeParts[3]) surging clusters, $($beforeParts[4]) surging questions, $($beforeParts[6]) unanswered questions, and $($beforeParts[7]) unanswered-question groups."
