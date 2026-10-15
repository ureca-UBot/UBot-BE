[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot 'seed')).Path
$sqlPath = Join-Path $PSScriptRoot 'seed-faq-tables.sql'
. (Join-Path $PSScriptRoot 'faq-seed-common.ps1')

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

# Validate every CSV before touching the database.
$seedSummary = Get-FaqSeedSummary -ToolsPath $PSScriptRoot
$parts = [int[]] $seedSummary.parts
$renderedSql = New-FaqSeedSql -TemplatePath $sqlPath -Parts $parts

$containerId = (& docker compose ps --quiet postgres).Trim()
if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw 'Start the existing PostgreSQL container before resetting FAQ tables.' }
$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
if (-not $databaseName -or -not $databaseUser) { throw 'Could not read PostgreSQL connection settings.' }

try {
    Copy-FaqSeedCsv -SeedPath $seedPath -ContainerId $containerId -Parts $parts
    Invoke-Checked { docker cp $renderedSql "${containerId}:/tmp/seed-faq-tables.sql" } 'Could not copy FAQ seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-faq-tables.sql
    } 'FAQ table reset failed. The transaction was rolled back.'
} finally {
    Remove-FaqSeedContainerFiles -ContainerId $containerId -Parts $parts -SqlName 'seed-faq-tables.sql'
    Remove-Item -LiteralPath $renderedSql -Force -ErrorAction SilentlyContinue
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
$expected = "$($seedSummary.faqCount)|$($seedSummary.variantCount)|$($seedSummary.variantCount)|0|1024|1024"
if ($LASTEXITCODE -ne 0 -or $verification -ne $expected) {
    throw "FAQ table verification failed: $verification"
}
Write-Host "FAQ tables reset completed: $($seedSummary.faqCount) FAQs, $($seedSummary.variantCount) variants and QUESTION_ANSWER vectors."
