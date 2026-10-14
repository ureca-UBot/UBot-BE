[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot '..\src\main\resources\seed')).Path

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

Invoke-Checked { python -X utf8 (Join-Path $PSScriptRoot 'validate_baseline_seed.py') } 'Baseline seed CSV validation failed.'
Invoke-Checked { docker compose up -d postgres } 'Could not start PostgreSQL.'
$containerId = (& docker compose ps --quiet postgres).Trim()
if (-not $containerId) { throw 'Could not locate the PostgreSQL container.' }
$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
if (-not $databaseName -or -not $databaseUser) { throw 'Could not read PostgreSQL connection settings.' }
$adminCount = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -Atc "SELECT count(*) FROM users WHERE email = 'admin@admin.com' AND role = 'ADMIN' AND deleted_at IS NULL").Trim()
if ($LASTEXITCODE -ne 0 -or $adminCount -ne '1') { throw 'One active admin@admin.com ADMIN account is required.' }

try {
    for ($part = 1; $part -le 5; $part++) {
        foreach ($prefix in @('baseline-faqs', 'baseline-faq-variants')) {
            $filename = "$prefix-$part.csv"
            Invoke-Checked { docker cp (Join-Path $seedPath $filename) "${containerId}:/tmp/$filename" } "Could not copy $filename."
        }
    }
    Invoke-Checked { docker cp (Join-Path $PSScriptRoot 'seed-baseline.sql') "${containerId}:/tmp/seed-baseline.sql" } 'Could not copy seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 -v faq_only=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-baseline.sql
    } 'FAQ baseline import failed.'
} finally {
    docker exec $containerId sh -c 'rm -f /tmp/baseline-faqs-*.csv /tmp/baseline-faq-variants-*.csv /tmp/seed-baseline.sql' 2>$null
}

$verification = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT (SELECT count(*) FROM faq),
       (SELECT count(*) FROM faq_question_variants),
       (SELECT count(*) FROM faq_embeddings WHERE vector_type = 'QUESTION_ANSWER'),
       (SELECT count(*) FROM faq_embeddings WHERE vector_type <> 'QUESTION_ANSWER');
'@).Trim()
if ($LASTEXITCODE -ne 0 -or $verification -ne '1000|5000|5000|0') {
    throw "FAQ baseline verification failed: $verification"
}
Write-Host 'FAQ reset completed: 1000 FAQs, 5000 variants, 5000 QUESTION_ANSWER vectors.'
