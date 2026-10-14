[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot '..\src\main\resources\seed')).Path
$migrationPath = (Resolve-Path (Join-Path $PSScriptRoot '..\src\main\resources\db\migration')).Path
$sqlPath = Join-Path $PSScriptRoot 'seed-baseline.sql'

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

# Finish every file and vector check before removing the PostgreSQL volume.
Invoke-Checked { python -X utf8 (Join-Path $PSScriptRoot 'validate_baseline_seed.py') } 'Baseline seed CSV validation failed.'

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
    for ($part = 1; $part -le 5; $part++) {
        foreach ($prefix in @('baseline-faqs', 'baseline-faq-variants')) {
            $filename = "$prefix-$part.csv"
            Invoke-Checked { docker cp (Join-Path $seedPath $filename) "${containerId}:/tmp/$filename" } "Could not copy $filename."
        }
    }
    Invoke-Checked { docker cp $sqlPath "${containerId}:/tmp/seed-baseline.sql" } 'Could not copy seed SQL.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $databaseName -f /tmp/seed-baseline.sql
    } 'Baseline seed import failed.'
} finally {
    if ($containerId) {
        docker exec $containerId sh -c 'rm -f /tmp/baseline-faqs-*.csv /tmp/baseline-faq-variants-*.csv /tmp/seed-baseline.sql' 2>$null
    }
}

$verification = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT
  (SELECT count(*) FROM users),
  (SELECT count(*) FROM faq_category),
  (SELECT count(*) FROM faq),
  (SELECT count(*) FROM faq_question_variants),
  (SELECT count(*) FROM faq_embeddings WHERE vector_type = 'QUESTION_ANSWER'),
  (SELECT count(*) FROM faq_embeddings WHERE vector_type <> 'QUESTION_ANSWER'),
  (SELECT min(vector_dims(vector)) FROM faq_embeddings),
  (SELECT max(vector_dims(vector)) FROM faq_embeddings);
'@).Trim()
if ($LASTEXITCODE -ne 0 -or $verification -ne '2|17|1000|5000|5000|0|1024|1024') {
    throw "Baseline seed verification failed: $verification"
}
Write-Host 'DB reset and baseline seed completed: 1000 FAQs, 5000 variants, 5000 QUESTION_ANSWER vectors.'
