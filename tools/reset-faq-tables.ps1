[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot '..\src\main\resources\seed')).Path
$sqlPath = Join-Path $PSScriptRoot 'seed-faq-tables.sql'

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

# Validate every CSV before touching the database.
Invoke-Checked { python -X utf8 (Join-Path $PSScriptRoot 'validate_baseline_seed.py') } 'Baseline seed CSV validation failed.'

$containerId = (& docker compose ps --quiet postgres).Trim()
if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw 'Start the existing PostgreSQL container before resetting FAQ tables.' }
$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
if (-not $databaseName -or -not $databaseUser) { throw 'Could not read PostgreSQL connection settings.' }

try {
    for ($part = 1; $part -le 5; $part++) {
        foreach ($prefix in @('baseline-faqs', 'baseline-faq-variants')) {
            $filename = "$prefix-$part.csv"
            Invoke-Checked { docker cp (Join-Path $seedPath $filename) "${containerId}:/tmp/$filename" } "Could not copy $filename."
        }
    }
    Invoke-Checked { docker cp $sqlPath "${containerId}:/tmp/seed-faq-tables.sql" } 'Could not copy FAQ seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-faq-tables.sql
    } 'FAQ table reset failed. The transaction was rolled back.'
} finally {
    docker exec $containerId sh -c 'rm -f /tmp/baseline-faqs-*.csv /tmp/baseline-faq-variants-*.csv /tmp/seed-faq-tables.sql' 2>$null
}

$verification = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT (SELECT count(*) FROM faq),
       (SELECT count(*) FROM faq_question_variants),
       (SELECT count(*) FROM faq_embeddings WHERE vector_type = 'QUESTION_ANSWER'),
       (SELECT count(*) FROM faq_embeddings WHERE vector_type <> 'QUESTION_ANSWER'),
       (SELECT min(vector_dims(vector)) FROM faq_embeddings),
       (SELECT max(vector_dims(vector)) FROM faq_embeddings);
'@).Trim()
if ($LASTEXITCODE -ne 0 -or $verification -ne '1000|5000|5000|0|1024|1024') {
    throw "FAQ table verification failed: $verification"
}
Write-Host 'FAQ tables reset completed: 1000 FAQs, 5000 variants, 5000 QUESTION_ANSWER vectors.'
