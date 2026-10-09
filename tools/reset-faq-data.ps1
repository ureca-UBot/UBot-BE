[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$faqCsvPath = Join-Path $PSScriptRoot '..\src\main\resources\seed\baseline-faqs.csv'

function Invoke-Checked {
    param([Parameter(Mandatory)] [scriptblock] $Command, [Parameter(Mandatory)] [string] $FailureMessage)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

function Escape-SqlLiteral([string] $Value) { return $Value.Replace("'", "''") }

if (-not (Test-Path -LiteralPath $faqCsvPath)) { throw "FAQ CSV를 찾을 수 없습니다: $faqCsvPath" }
$faqRows = @(Import-Csv -LiteralPath $faqCsvPath -Encoding UTF8)
if ($faqRows.Count -eq 0) { throw 'FAQ CSV에 데이터가 없습니다.' }

$headers = @($faqRows[0].PSObject.Properties.Name)
foreach ($header in @('category', 'question', 'answer', 'intent', 'vector')) {
    if ($headers -notcontains $header) { throw "FAQ CSV에 $header 컬럼이 없습니다." }
}
foreach ($row in $faqRows) {
    foreach ($header in @('category', 'question', 'answer', 'intent', 'vector')) {
        if ([string]::IsNullOrWhiteSpace($row.$header)) { throw "FAQ CSV에 비어 있는 $header 값이 있습니다." }
    }
    if ($row.vector[0] -ne '[' -or $row.vector[-1] -ne ']') { throw 'FAQ CSV의 vector 값은 pgvector 배열 형식이어야 합니다.' }
}

Invoke-Checked { docker compose up -d postgres } 'PostgreSQL 컨테이너를 시작하지 못했습니다.'
$postgresContainerId = (& docker compose ps --quiet postgres).Trim()
if (-not $postgresContainerId) { throw 'PostgreSQL 컨테이너를 찾지 못했습니다.' }

$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
if (-not $databaseName -or -not $databaseUser) { throw 'PostgreSQL 접속 정보를 읽지 못했습니다.' }

$adminCheckQuery = @"
SELECT count(*) = 1 FROM users WHERE email = 'admin@admin.com' AND "role" = 'ADMIN';
"@
$adminCheck = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -Atc $adminCheckQuery).Trim()
if ($LASTEXITCODE -ne 0) { throw '기본 ADMIN 계정을 확인하지 못했습니다.' }
if ($adminCheck -ne 't') { throw 'FAQ 초기화에는 admin@admin.com ADMIN 계정이 정확히 하나 필요합니다. 먼저 reset-local-db.ps1을 실행하세요.' }

$temporarySqlPath = Join-Path ([System.IO.Path]::GetTempPath()) "ubot-faq-seed-$([guid]::NewGuid()).sql"
try {
    $sql = [System.Text.StringBuilder]::new()
    [void] $sql.AppendLine('BEGIN;')
    [void] $sql.AppendLine(@"
UPDATE unanswered_question_groups
SET related_faq_id = NULL, resolved_faq_id = NULL
WHERE related_faq_id IS NOT NULL OR resolved_faq_id IS NOT NULL;
UPDATE unanswered_questions SET best_faq_id = NULL WHERE best_faq_id IS NOT NULL;
DELETE FROM faq_log;
DELETE FROM old_faq;
DELETE FROM faq;
DELETE FROM faq_category;
ALTER TABLE faq ALTER COLUMN id RESTART WITH 1;
ALTER TABLE faq_category ALTER COLUMN id RESTART WITH 1;
CREATE TEMP TABLE baseline_faq_seed (
  category TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL,
  intent TEXT NOT NULL, vector vector(1024) NOT NULL
) ON COMMIT DROP;
"@)
    foreach ($row in $faqRows) {
        $category = Escape-SqlLiteral $row.category; $question = Escape-SqlLiteral $row.question
        $answer = Escape-SqlLiteral $row.answer; $intent = Escape-SqlLiteral $row.intent; $vector = Escape-SqlLiteral $row.vector
        [void] $sql.AppendLine("INSERT INTO baseline_faq_seed VALUES ('$category', '$question', '$answer', '$intent', '$vector');")
    }
    [void] $sql.AppendLine(@"
INSERT INTO faq_category (name) SELECT DISTINCT category FROM baseline_faq_seed ORDER BY category;
INSERT INTO faq (category_id, question, answer, intent, vector, admin_id)
SELECT category.id, seed.question, seed.answer, seed.intent, seed.vector, admin.user_id
FROM baseline_faq_seed seed
JOIN faq_category category ON category.name = seed.category
JOIN users admin ON admin.email = 'admin@admin.com';
COMMIT;
"@)
    [System.IO.File]::WriteAllText($temporarySqlPath, $sql.ToString(), [System.Text.UTF8Encoding]::new($false))
    Invoke-Checked { docker cp $temporarySqlPath "${postgresContainerId}:/tmp/faq-seed.sql" } 'FAQ 데이터 SQL을 PostgreSQL 컨테이너에 복사하지 못했습니다.'
    Invoke-Checked { docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U $databaseUser -d $databaseName -f /tmp/faq-seed.sql } 'FAQ 데이터 초기화 및 삽입에 실패했습니다.'
} finally {
    Remove-Item -LiteralPath $temporarySqlPath -ErrorAction SilentlyContinue
    if ($postgresContainerId) { docker exec $postgresContainerId rm -f /tmp/faq-seed.sql 2>$null }
}

$verificationOutput = & docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @"
SELECT
  (SELECT count(*) = 17 FROM faq_category) AS categories_ok,
  (SELECT count(*) = 1000 AND count(vector) = 1000
    AND min(vector_dims(vector)) = 1024 AND max(vector_dims(vector)) = 1024 FROM faq) AS faqs_and_vectors_ok,
  (SELECT count(*) = 0 FROM faq_log) AS faq_logs_cleared,
  (SELECT count(*) = 0 FROM old_faq) AS faq_history_cleared,
  (SELECT count(*) = 0 FROM faq_embeddings) AS faq_embeddings_cleared,
  (SELECT count(*) = 0 FROM unanswered_question_groups
    WHERE related_faq_id IS NOT NULL OR resolved_faq_id IS NOT NULL) AS unanswered_groups_unlinked,
  (SELECT count(*) = 0 FROM unanswered_questions WHERE best_faq_id IS NOT NULL) AS unanswered_questions_unlinked;
"@

if ($LASTEXITCODE -ne 0) { throw 'FAQ 데이터 검증 쿼리 실행에 실패했습니다.' }

$verification = ($verificationOutput | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1).Trim()
if ($verification -ne 't|t|t|t|t|t|t') {
    throw "FAQ 데이터 검증에 실패했습니다. 검증 결과: $verification"
}

Write-Host 'FAQ Reset Completed.'
