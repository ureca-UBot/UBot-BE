[CmdletBinding()]
param(
    # question_log에 이미 행이 있어도 적용한다. 같은 시연 데이터가 중복으로 들어갈 수 있다.
    [switch] $Force
)

# DB를 초기화하지 않고 시연용 질문 기록 두 가지를 순서대로 넣는다.
#   1. seed-demo-faqranking.sql  (인기 FAQ 시연)
#   2. seed-demo-telemap.sql     (FAQ 대시보드·통신 지도 시연)
# reset-local-db.ps1이 마지막에 실행하는 두 단계와 같다. 사용자·FAQ 시드가 먼저 들어가 있어야 한다.
$ErrorActionPreference = 'Stop'
$seedSqlPaths = @(
    @{ Path = (Join-Path $PSScriptRoot 'seed-demo-faqranking.sql'); Name = 'FAQ ranking demo' },
    @{ Path = (Join-Path $PSScriptRoot 'seed-demo-telemap.sql'); Name = 'telemap demo' }
)

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

foreach ($seed in $seedSqlPaths) {
    if (-not (Test-Path -LiteralPath $seed.Path)) { throw "Seed SQL is missing: $($seed.Path)" }
}

Invoke-Checked { docker compose up -d postgres } 'Could not start PostgreSQL.'
$containerId = (& docker compose ps --quiet postgres).Trim()
if (-not $containerId) { throw 'Could not locate the PostgreSQL container.' }
$databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
$databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
if (-not $databaseName -or -not $databaseUser) { throw 'Could not read PostgreSQL connection settings.' }

if (-not $Force) {
    $existing = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
        -U $databaseUser -d $databaseName -At -c 'SELECT count(*) FROM question_log').Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not count question_log rows.' }
    if ($existing -ne '0') {
        throw "question_log already has $existing rows, so the demo data would be added twice. Reset the DB first, or pass -Force."
    }
}

try {
    foreach ($seed in $seedSqlPaths) {
        $fileName = Split-Path -Leaf $seed.Path
        Invoke-Checked { docker cp $seed.Path "${containerId}:/tmp/$fileName" } "Could not copy $($seed.Name) seed SQL."
        Invoke-Checked {
            docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
                -U $databaseUser -d $databaseName -f "/tmp/$fileName"
        } "$($seed.Name) seed import failed."
    }
} finally {
    $remove = $seedSqlPaths | ForEach-Object { '/tmp/' + (Split-Path -Leaf $_.Path) }
    & docker compose exec -T postgres rm -f @remove | Out-Null
}

$counts = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT (SELECT count(*) FROM question_log), (SELECT count(*) FROM faq_log), (SELECT count(*) FROM surging_alerts);
'@).Trim()
$parts = $counts.Split('|')
Write-Host "Demo seeds applied: $($parts[0]) question logs, $($parts[1]) FAQ logs, $($parts[2]) surging alerts."
