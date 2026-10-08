[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$faqCsvPath = Join-Path $PSScriptRoot '..\src\main\resources\seed\baseline-faqs.csv'
$migrationPath = (Resolve-Path (Join-Path $PSScriptRoot '..\src\main\resources\db\migration')).Path

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

$postgresContainerId = (& docker compose ps --all --quiet postgres).Trim()
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 컨테이너 정보를 확인하지 못했습니다.' }
$postgresVolume = $null
if ($postgresContainerId) {
    $container = (& docker inspect $postgresContainerId | ConvertFrom-Json)
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 데이터 볼륨을 확인하지 못했습니다.' }
    $postgresVolume = @($container.Mounts | Where-Object { $_.Destination -eq '/var/lib/postgresql' } | Select-Object -ExpandProperty Name)[0]
    if (-not $postgresVolume) { throw 'PostgreSQL 데이터 볼륨을 찾지 못했습니다.' }
    Invoke-Checked { docker compose rm --force --stop postgres } 'PostgreSQL 컨테이너를 제거하지 못했습니다.'
}
if ($postgresVolume) { Invoke-Checked { docker volume rm $postgresVolume } "PostgreSQL 데이터 볼륨($postgresVolume)을 삭제하지 못했습니다." }

Invoke-Checked { docker compose up -d --build postgres } 'PostgreSQL 컨테이너를 시작하지 못했습니다.'
$postgresContainerId = (& docker compose ps --quiet postgres).Trim()
if (-not $postgresContainerId) { throw '새 PostgreSQL 컨테이너를 찾지 못했습니다.' }
$postgresContainer = (& docker inspect $postgresContainerId | ConvertFrom-Json)
$network = @($postgresContainer.NetworkSettings.Networks.PSObject.Properties.Name)[0]
if (-not $network) { throw 'PostgreSQL Docker 네트워크를 찾지 못했습니다.' }

$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
$databasePassword = (& docker compose exec -T postgres printenv POSTGRES_PASSWORD).Trim()
if (-not $databaseName -or -not $databaseUser -or -not $databasePassword) { throw 'PostgreSQL 접속 정보를 읽지 못했습니다.' }

Invoke-Checked {
    docker run --rm --network $network `
        --mount "type=bind,source=$migrationPath,target=/flyway/sql,readonly" `
        flyway/flyway:11 `
        "-url=jdbc:postgresql://postgres:5432/$databaseName" `
        "-user=$databaseUser" "-password=$databasePassword" `
        '-baselineOnMigrate=true' '-baselineVersion=0' '-connectRetries=10' migrate
} 'Flyway 마이그레이션 적용에 실패했습니다.'

$temporarySqlPath = Join-Path ([System.IO.Path]::GetTempPath()) "ubot-baseline-seed-$([guid]::NewGuid()).sql"
try {
    $sql = [System.Text.StringBuilder]::new()
    [void] $sql.AppendLine('BEGIN;')
    [void] $sql.AppendLine('CREATE EXTENSION IF NOT EXISTS pgcrypto;')
    [void] $sql.AppendLine(@"
INSERT INTO users (email, password_hash, name, "role")
VALUES
  ('user@user.com', crypt('12345678', gen_salt('bf')), 'Default User', 'USER'),
  ('admin@admin.com', crypt('12345678', gen_salt('bf')), 'Default Admin', 'ADMIN');
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
    Invoke-Checked { docker cp $temporarySqlPath "${postgresContainerId}:/tmp/baseline-seed.sql" } '기본 데이터 SQL을 PostgreSQL 컨테이너에 복사하지 못했습니다.'
    Invoke-Checked { docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U $databaseUser -d $databaseName -f /tmp/baseline-seed.sql } '기본 데이터 삽입에 실패했습니다.'
} finally {
    Remove-Item -LiteralPath $temporarySqlPath -ErrorAction SilentlyContinue
    if ($postgresContainerId) { docker exec $postgresContainerId rm -f /tmp/baseline-seed.sql 2>$null }
}

$verificationOutput = & docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @"
SELECT
  (SELECT
    count(*) = 2
    AND count(*) FILTER (WHERE email = 'user@user.com' AND "role" = 'USER'
      AND crypt('12345678', password_hash) = password_hash) = 1
    AND count(*) FILTER (WHERE email = 'admin@admin.com' AND "role" = 'ADMIN'
      AND crypt('12345678', password_hash) = password_hash) = 1
   FROM users) AS users_ok,
  (SELECT count(*) = 17 FROM faq_category) AS categories_ok,
  (SELECT
    count(*) = 1000
    AND count(vector) = 1000
    AND min(vector_dims(vector)) = 1024
    AND max(vector_dims(vector)) = 1024
   FROM faq) AS faqs_and_vectors_ok;
"@

if ($LASTEXITCODE -ne 0) { throw '기본 데이터 검증 쿼리 실행에 실패했습니다.' }

$verification = ($verificationOutput | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1).Trim()
if ($verification -ne 't|t|t') {
    throw "기본 데이터 검증에 실패했습니다. 검증 결과: $verification"
}

Write-Host 'DB Reset Completed.'
