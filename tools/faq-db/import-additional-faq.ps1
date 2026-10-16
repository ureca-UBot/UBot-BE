[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^[0-9]{4,8}$')]
    [string] $Date
)

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot 'seed')).Path
$manifestPath = Join-Path (Join-Path $seedPath 'days') "additional-faq-$Date.json"
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Missing manifest: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.date -ne $Date) { throw 'Manifest date does not match the requested date.' }

$sourceIds = @($manifest.faqIds | ForEach-Object { [string] $_ })
if ($sourceIds.Count -eq 0 -or @($sourceIds | Select-Object -Unique).Count -ne $sourceIds.Count) {
    throw 'Manifest FAQ IDs are empty or duplicated.'
}
$wanted = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($id in $sourceIds) {
    if ($id -notmatch '^[0-9]+$') { throw "Invalid FAQ ID in manifest: $id" }
    [void] $wanted.Add($id)
}

$faqs = New-Object 'System.Collections.Generic.List[object]'
$variants = New-Object 'System.Collections.Generic.List[object]'
$faqPath = Join-Path $seedPath 'baseline-faqs.csv'
$variantPath = Join-Path $seedPath 'baseline-faq-variants.csv'
if (-not (Test-Path -LiteralPath $faqPath) -or -not (Test-Path -LiteralPath $variantPath)) {
    throw 'Missing baseline FAQ or variant CSV.'
}
foreach ($faq in @(Import-Csv -LiteralPath $faqPath -Encoding UTF8)) {
    if ($wanted.Contains([string] $faq.faqId)) { $faqs.Add($faq) }
}
foreach ($variant in @(Import-Csv -LiteralPath $variantPath -Encoding UTF8)) {
    if ($wanted.Contains([string] $variant.originFaqId)) { $variants.Add($variant) }
}

if ($faqs.Count -ne $sourceIds.Count -or $variants.Count -ne 5 * $faqs.Count) {
    throw 'Manifest IDs do not match the selected CSV rows.'
}
$faqIds = New-Object 'System.Collections.Generic.HashSet[string]'
$variantIds = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($faq in $faqs) {
    $id = [string] $faq.faqId
    if (-not $wanted.Contains($id) -or -not $faqIds.Add($id)) { throw "Duplicate or unexpected FAQ ID: $id" }
    foreach ($field in @('category', 'question', 'answer', 'intent')) {
        if ([string]::IsNullOrWhiteSpace([string] $faq.$field)) { throw "FAQ $id has a blank $field" }
    }
}
foreach ($variant in $variants) {
    $id = [string] $variant.variantId
    if (-not $variantIds.Add($id)) { throw "Duplicate variant ID: $id" }
    if (-not $faqIds.Contains([string] $variant.originFaqId) -or
        [string]::IsNullOrWhiteSpace([string] $variant.question)) {
        throw "Invalid variant $id"
    }
}
foreach ($faq in $faqs) {
    $group = @($variants | Where-Object originFaqId -eq $faq.faqId)
    if ($group.Count -ne 5 -or $group[0].question -cne $faq.question) {
        throw "FAQ $($faq.faqId) has invalid variants."
    }
    $questions = @($group | ForEach-Object { ($_.question -replace '\s+', ' ').Trim() })
    if (@($questions | Select-Object -Unique).Count -ne 5) {
        throw "FAQ $($faq.faqId) has duplicate variant questions."
    }
}
Write-Host "Validated $($faqs.Count) FAQ rows and $($variants.Count) variant rows."

$token = [Guid]::NewGuid().ToString('N')
$localFaq = Join-Path ([IO.Path]::GetTempPath()) "ubot-addfaq-$token-faqs.csv"
$localVariant = Join-Path ([IO.Path]::GetTempPath()) "ubot-addfaq-$token-variants.csv"
$localSql = Join-Path ([IO.Path]::GetTempPath()) "ubot-addfaq-$token.sql"
$remoteFaq = "/tmp/ubot-addfaq-$token-faqs.csv"
$remoteVariant = "/tmp/ubot-addfaq-$token-variants.csv"
$remoteSql = "/tmp/ubot-addfaq-$token.sql"
$containerId = $null

Push-Location (Join-Path $PSScriptRoot '..\..')
try {
    $containerId = (& docker compose ps --quiet postgres).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw 'Existing Compose PostgreSQL container is not running.' }
    $databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
    $databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
    if (-not $databaseName -or -not $databaseUser) { throw 'Could not read PostgreSQL database settings.' }

    $faqs | Select-Object faqId, category, question, answer, intent |
        Export-Csv -LiteralPath $localFaq -NoTypeInformation -Encoding UTF8
    $variants | Select-Object originFaqId, variantId, question |
        Export-Csv -LiteralPath $localVariant -NoTypeInformation -Encoding UTF8
    $sqlText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'import-additional-faq.sql') -Raw -Encoding UTF8
    $sqlText = $sqlText.Replace('__FAQ_FILE__', $remoteFaq).Replace('__VARIANT_FILE__', $remoteVariant)
    $sqlText = $sqlText.Replace('__FAQ_COUNT__', [string] $faqs.Count)
    $sqlText = $sqlText.Replace('__VARIANT_COUNT__', [string] $variants.Count)
    [IO.File]::WriteAllText($localSql, $sqlText, (New-Object System.Text.UTF8Encoding $false))

    foreach ($pair in @(@($localFaq, $remoteFaq), @($localVariant, $remoteVariant), @($localSql, $remoteSql))) {
        & docker cp $pair[0] "${containerId}:$($pair[1])"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $($pair[0]) to the DB container." }
    }
    & docker compose exec -T postgres psql --no-psqlrc -v ON_ERROR_STOP=1 `
        -U $databaseUser -d $databaseName -f $remoteSql
    if ($LASTEXITCODE -ne 0) { throw 'Additional FAQ import failed; the transaction was rolled back.' }
    Write-Host "Inserted $($faqs.Count) FAQs and $($variants.Count) variants. No vectors were inserted."
} finally {
    if ($containerId) {
        & docker exec $containerId rm -f -- $remoteFaq $remoteVariant $remoteSql 2>$null | Out-Null
    }
    Remove-Item -LiteralPath $localFaq, $localVariant, $localSql -Force -ErrorAction SilentlyContinue
    Pop-Location
}
