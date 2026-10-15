[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot 'seed')).Path
. (Join-Path $PSScriptRoot 'faq-seed-common.ps1')

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

$seedSummary = Get-FaqSeedSummary -ToolsPath $PSScriptRoot
$parts = [int[]] $seedSummary.parts
$renderedSql = New-FaqSeedSql -TemplatePath (Join-Path $PSScriptRoot 'seed-baseline.sql') -Parts $parts
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
    Copy-FaqSeedCsv -SeedPath $seedPath -ContainerId $containerId -Parts $parts
    Invoke-Checked { docker cp $renderedSql "${containerId}:/tmp/seed-baseline.sql" } 'Could not copy seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 -v faq_only=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-baseline.sql
    } 'FAQ baseline import failed.'
} finally {
    Remove-FaqSeedContainerFiles -ContainerId $containerId -Parts $parts -SqlName 'seed-baseline.sql'
    Remove-Item -LiteralPath $renderedSql -Force -ErrorAction SilentlyContinue
}

$verification = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT (SELECT count(*) FROM faq),
       (SELECT count(*) FROM faq_question_variants),
       (SELECT count(*) FROM faq_embeddings WHERE vector_type = 'QUESTION_ANSWER'),
       (SELECT count(*) FROM faq_embeddings WHERE vector_type <> 'QUESTION_ANSWER');
'@).Trim()
$expected = "$($seedSummary.faqCount)|$($seedSummary.variantCount)|$($seedSummary.variantCount)|0"
if ($LASTEXITCODE -ne 0 -or $verification -ne $expected) {
    throw "FAQ baseline verification failed: $verification"
}
Write-Host "FAQ reset completed: $($seedSummary.faqCount) FAQs, $($seedSummary.variantCount) variants and QUESTION_ANSWER vectors."
