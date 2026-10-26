[CmdletBinding()]
param(
    # question_log에 이미 행이 있어도 적용한다. 같은 시연 데이터가 중복으로 들어갈 수 있다.
    [switch] $Force
)

# DB를 초기화하지 않고 이 브랜치의 시연 데이터 세 가지를 한 트랜잭션으로 순서대로 넣는다.
#   1. seed-demo-product-faqs.sql (상품 의도 FAQ. 이미 있으면 건너뜀. 인기 FAQ 시연이 이 FAQ를 씀)
#   2. seed-demo-faqranking.sql   (인기 FAQ 시연)
#   3. seed-demo-telemap.sql      (FAQ 대시보드·통신 지도 시연)
# 사용자·FAQ 기본 시드가 먼저 들어가 있어야 한다. 상품 FAQ를 새로 넣었으면 적용 뒤 임베딩을 백필한다.
$ErrorActionPreference = 'Stop'
$toolsPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$seedSqlPaths = @(
    @{ Path = (Join-Path $toolsPath 'seed\demo\seed-demo-product-faqs.sql'); Name = 'product FAQ demo' },
    @{ Path = (Join-Path $toolsPath 'seed\demo\seed-demo-faqranking.sql'); Name = 'FAQ ranking demo' },
    @{ Path = (Join-Path $toolsPath 'seed\demo\seed-demo-telemap.sql'); Name = 'telemap demo' },
    # 상품 FAQ SQL이 새 FAQ·질문 표현을 기본 CSV에서 읽는다.
    @{ Path = (Join-Path $toolsPath 'seed\baseline\baseline-faqs.csv'); Name = 'baseline FAQ CSV' },
    @{ Path = (Join-Path $toolsPath 'seed\baseline\baseline-faq-variants.csv'); Name = 'baseline variant CSV' }
)

function Invoke-Checked {
    param([scriptblock] $Command, [string] $Message)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $Message }
}

foreach ($seed in $seedSqlPaths) {
    if (-not (Test-Path -LiteralPath $seed.Path)) { throw "Seed SQL is missing: $($seed.Path)" }
}

$transactionScript = [IO.Path]::GetTempFileName()
[IO.File]::WriteAllText($transactionScript, @'
BEGIN;
\i /tmp/seed-demo-product-faqs.sql
\i /tmp/seed-demo-faqranking.sql
\i /tmp/seed-demo-telemap.sql
COMMIT;
'@, (New-Object System.Text.UTF8Encoding $false))

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
        Invoke-Checked { docker cp $seed.Path "${containerId}:/tmp/$fileName" } "Could not copy $($seed.Name)."
    }
    Invoke-Checked { docker cp $transactionScript "${containerId}:/tmp/apply-demo-activity.sql" } 'Could not copy demo activity transaction script.'
    Invoke-Checked {
        docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 -v managed_transaction=1 `
            -U $databaseUser -d $databaseName -f /tmp/apply-demo-activity.sql
    } 'Demo activity seed import failed; all demo activity changes were rolled back.'
} finally {
    $remove = $seedSqlPaths | ForEach-Object { '/tmp/' + (Split-Path -Leaf $_.Path) }
    $remove += '/tmp/apply-demo-activity.sql'
    & docker compose exec -T postgres rm -f @remove | Out-Null
    Remove-Item -LiteralPath $transactionScript -Force -ErrorAction SilentlyContinue
}

$counts = (& docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
    -U $databaseUser -d $databaseName -At -F '|' -c @'
SELECT (SELECT count(*) FROM question_log), (SELECT count(*) FROM faq_log), (SELECT count(*) FROM surging_alerts),
       (SELECT count(*) FROM faq WHERE intent = 'PRODUCT_DATA' AND deleted_at IS NULL);
'@).Trim()
$parts = $counts.Split('|')
Write-Host "Demo seeds applied: $($parts[0]) question logs, $($parts[1]) FAQ logs, $($parts[2]) surging alerts, $($parts[3]) product FAQs."
