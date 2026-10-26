[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot 'seed')).Path
$migrationPath = (Resolve-Path (Join-Path $PSScriptRoot '..\..\src\main\resources\db\migration')).Path
$sqlPath = Join-Path $PSScriptRoot 'seed-baseline.sql'
$demoUsersSqlPath = Join-Path $PSScriptRoot 'seed-demo-users.sql'
$usersSqlPath = Join-Path $PSScriptRoot 'seed-users.sql'
$popularFaqDemoSqlPath = Join-Path $PSScriptRoot 'seed\faq_ranking_demo\seed-popular-faq-from-live-logs.sql'
$demoActivitySqlPath = Join-Path $PSScriptRoot 'seed-demo-activity.sql'
. (Join-Path $PSScriptRoot 'faq-seed-common.ps1')

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

# Validate both CSV files before removing the PostgreSQL volume.
$seedSummary = Get-FaqSeedSummary -ToolsPath $PSScriptRoot
$renderedSql = New-FaqSeedSql -TemplatePath $sqlPath -DeletedFaqIds @(Get-FaqDeletedIds -SeedPath $seedPath)

$containerId = (& docker compose ps --all --quiet postgres).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the PostgreSQL container.' }
$volume = $null
if ($containerId) {
    $container = (& docker inspect $containerId | ConvertFrom-Json)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the PostgreSQL volume.' }
    $volume = @($container.Mounts | Where-Object { $_.Destination -eq '/var/lib/postgresql' } | Select-Object -ExpandProperty Name)[0]
    if (-not $volume) { throw 'Could not locate the PostgreSQL volume.' }
    Invoke-Checked { docker compose rm --force --stop postgres } 'Could not remove the PostgreSQL container.'
}
if ($volume) { Invoke-Checked { docker volume rm $volume } 'Could not remove the PostgreSQL volume.' }

Invoke-Checked { docker compose up -d --build postgres } 'Could not start PostgreSQL.'
$containerId = (& docker compose ps --quiet postgres).Trim()
if (-not $containerId) { throw 'Could not locate the new PostgreSQL container.' }
$container = (& docker inspect $containerId | ConvertFrom-Json)
$network = @($container.NetworkSettings.Networks.PSObject.Properties.Name)[0]
if (-not $network) { throw 'Could not locate the PostgreSQL Docker network.' }
$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
$databasePassword = (& docker compose exec -T postgres printenv POSTGRES_PASSWORD).Trim()
if (-not $databaseName -or -not $databaseUser -or -not $databasePassword) { throw 'Could not read PostgreSQL connection settings.' }

Invoke-Checked {
    docker run --rm --network $network `
        --mount "type=bind,source=$migrationPath,target=/flyway/sql,readonly" `
        flyway/flyway:11 `
        "-url=jdbc:postgresql://postgres:5432/$databaseName" `
        "-user=$databaseUser" "-password=$databasePassword" `
        '-baselineOnMigrate=true' '-baselineVersion=0' '-connectRetries=10' migrate
} 'Flyway migration failed.'

try {
    Copy-FaqSeedCsv -SeedPath $seedPath -ContainerId $containerId
    Invoke-Checked { docker cp $renderedSql "${containerId}:/tmp/seed-baseline.sql" } 'Could not copy seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-baseline.sql
    } 'Baseline seed import failed.'
    Invoke-Checked { docker cp $demoUsersSqlPath "${containerId}:/tmp/seed-demo-users.sql" } 'Could not copy demo user seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-demo-users.sql
    } 'Demo user seed import failed.'
    Invoke-Checked { docker cp $usersSqlPath "${containerId}:/tmp/seed-users.sql" } 'Could not copy user seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-users.sql
    } 'User seed import failed.'
    if (-not (Test-Path -LiteralPath $popularFaqDemoSqlPath)) {
        throw "Popular FAQ demo seed SQL is missing: $popularFaqDemoSqlPath"
    }
    Invoke-Checked { docker cp $popularFaqDemoSqlPath "${containerId}:/tmp/seed-popular-faq-from-live-logs.sql" } 'Could not copy popular FAQ demo seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-popular-faq-from-live-logs.sql
    } 'Popular FAQ demo seed import failed.'
    Invoke-Checked { docker cp $demoActivitySqlPath "${containerId}:/tmp/seed-demo-activity.sql" } 'Could not copy demo activity seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-demo-activity.sql
    } 'Demo activity seed import failed.'
} finally {
    if ($containerId) {
        Remove-FaqSeedContainerFiles -ContainerId $containerId -SqlName 'seed-baseline.sql'
        & docker compose exec -T postgres rm -f /tmp/seed-demo-users.sql /tmp/seed-users.sql /tmp/seed-popular-faq-from-live-logs.sql /tmp/seed-demo-activity.sql | Out-Null
    }
    Remove-Item -LiteralPath $renderedSql -Force -ErrorAction SilentlyContinue
}

$verification = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT
  (SELECT count(*) FROM users),
  (SELECT count(*) FROM faq_category),
  (SELECT count(*) FROM faq),
  (SELECT count(*) FROM faq WHERE deleted_at IS NOT NULL),
  (SELECT count(*) FROM faq_question_variants),
  (SELECT count(*) FROM faq_embeddings),
  (SELECT count(*) FROM question_log),
  (SELECT count(*) FROM faq_log),
  (SELECT count(*) FROM plans),
  (SELECT count(*) FROM subscriptions),
  (SELECT count(*) FROM subscription_bundles),
  (SELECT count(*) FROM bundle_groups);
'@).Trim()
$expected = "311|$($seedSummary.categoryCount)|$($seedSummary.faqCount)|$($seedSummary.deletedCount)|$($seedSummary.variantCount)|0|517|1085|107|382|137|61"
if ($LASTEXITCODE -ne 0 -or $verification -ne $expected) {
    throw "Baseline seed verification failed: $verification"
}
Write-Host "DB reset and seed completed: $($seedSummary.faqCount) FAQs ($($seedSummary.deletedCount) soft-deleted), $($seedSummary.variantCount) variants, 517 question logs, and 1085 FAQ logs. No vectors were inserted."
