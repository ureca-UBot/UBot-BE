[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^[0-9]{4,8}$')]
    [string] $Date,

    # Runs every check and prints the report, then rolls the transaction back.
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
$seedPath = (Resolve-Path (Join-Path $PSScriptRoot 'seed')).Path
. (Join-Path $PSScriptRoot 'faq-seed-common.ps1')

$manifestPath = Join-Path (Join-Path $seedPath 'days') "updated-faq-$Date.json"
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Missing manifest: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.date -ne $Date) { throw 'Manifest date does not match the requested date.' }

$sourceIds = @($manifest.faqIds | ForEach-Object { [string] $_ })
if ($sourceIds.Count -eq 0 -or @($sourceIds | Select-Object -Unique).Count -ne $sourceIds.Count) {
    throw 'Manifest FAQ IDs are empty or duplicated.'
}
$before = @{}
foreach ($entry in @($manifest.before)) {
    $id = [string] $entry.faqId
    if ($before.ContainsKey($id)) { throw "Duplicate pre-change snapshot for FAQ $id" }
    $before[$id] = $entry
}
foreach ($id in $sourceIds) {
    if ($id -notmatch '^[0-9]+$') { throw "Invalid FAQ ID in manifest: $id" }
    if (-not $before.ContainsKey($id)) { throw "Manifest has no pre-change snapshot for FAQ $id" }
}

# Validates both CSVs, including deleted-faqs.json, before anything touches the database.
$null = Get-FaqSeedSummary -ToolsPath $PSScriptRoot
$faqById = @{}
foreach ($faq in @(Import-Csv -LiteralPath (Join-Path $seedPath 'baseline-faqs.csv') -Encoding UTF8)) {
    $faqById[[string] $faq.faqId] = $faq
}
$variantById = @{}
foreach ($variant in @(Import-Csv -LiteralPath (Join-Path $seedPath 'baseline-faq-variants.csv') -Encoding UTF8)) {
    $variantById[[string] $variant.variantId] = $variant
}

$updates = New-Object 'System.Collections.Generic.List[object]'
$variantUpdates = New-Object 'System.Collections.Generic.List[object]'
foreach ($id in $sourceIds) {
    if (-not $faqById.ContainsKey($id)) { throw "FAQ $id is not in baseline-faqs.csv" }
    $faq = $faqById[$id]
    $old = $before[$id]
    $updates.Add([pscustomobject]@{
        faqId = $id; category = $faq.category; question = $faq.question; answer = $faq.answer; intent = $faq.intent
        beforeCategory = $old.category; beforeQuestion = $old.question; beforeAnswer = $old.answer; beforeIntent = $old.intent
    })
    foreach ($oldVariant in @($old.variants | Where-Object { $_ })) {
        $variantId = [string] $oldVariant.variantId
        if (-not $variantById.ContainsKey($variantId) -or [string] $variantById[$variantId].originFaqId -ne $id) {
            throw "Variant $variantId does not belong to FAQ $id in baseline-faq-variants.csv"
        }
        $variantUpdates.Add([pscustomobject]@{
            variantId = $variantId; originFaqId = $id
            beforeQuestion = $oldVariant.question; question = $variantById[$variantId].question
        })
    }
}

$deletes = New-Object 'System.Collections.Generic.List[object]'
foreach ($id in @(Get-FaqDeletedIds -SeedPath $seedPath)) {
    $deletes.Add([pscustomobject]@{ faqId = $id; question = $faqById[$id].question })
}
Write-Host "Validated $($updates.Count) FAQ updates, $($variantUpdates.Count) variant updates and $($deletes.Count) soft deletes."

$token = [Guid]::NewGuid().ToString('N')
$localUpdate = Join-Path ([IO.Path]::GetTempPath()) "ubot-updfaq-$token-faqs.csv"
$localVariant = Join-Path ([IO.Path]::GetTempPath()) "ubot-updfaq-$token-variants.csv"
$localDelete = Join-Path ([IO.Path]::GetTempPath()) "ubot-updfaq-$token-deletes.csv"
$localSql = Join-Path ([IO.Path]::GetTempPath()) "ubot-updfaq-$token.sql"
$remoteUpdate = "/tmp/ubot-updfaq-$token-faqs.csv"
$remoteVariant = "/tmp/ubot-updfaq-$token-variants.csv"
$remoteDelete = "/tmp/ubot-updfaq-$token-deletes.csv"
$remoteSql = "/tmp/ubot-updfaq-$token.sql"
$containerId = $null

Push-Location (Join-Path $PSScriptRoot '..\..')
try {
    $containerId = (& docker compose ps --quiet postgres).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw 'Existing Compose PostgreSQL container is not running.' }
    $databaseName = (& docker compose exec -T postgres printenv POSTGRES_DB).Trim()
    $databaseUser = (& docker compose exec -T postgres printenv POSTGRES_USER).Trim()
    if (-not $databaseName -or -not $databaseUser) { throw 'Could not read PostgreSQL database settings.' }

    # Header rows are skipped by COPY, so the column order below must match the SQL staging tables.
    $updates | Select-Object faqId, category, question, answer, intent, beforeCategory, beforeQuestion, beforeAnswer, beforeIntent |
        Export-Csv -LiteralPath $localUpdate -NoTypeInformation -Encoding UTF8
    if ($variantUpdates.Count -gt 0) {
        $variantUpdates | Select-Object variantId, originFaqId, beforeQuestion, question |
            Export-Csv -LiteralPath $localVariant -NoTypeInformation -Encoding UTF8
    } else {
        [IO.File]::WriteAllText($localVariant, "variantId,originFaqId,beforeQuestion,question`r`n")
    }
    if ($deletes.Count -gt 0) {
        $deletes | Select-Object faqId, question | Export-Csv -LiteralPath $localDelete -NoTypeInformation -Encoding UTF8
    } else {
        [IO.File]::WriteAllText($localDelete, "faqId,question`r`n")
    }
    $sqlText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'update-existing-faq.sql') -Raw -Encoding UTF8
    $sqlText = $sqlText.Replace('__UPDATE_FILE__', $remoteUpdate).Replace('__VARIANT_FILE__', $remoteVariant)
    $sqlText = $sqlText.Replace('__DELETE_FILE__', $remoteDelete)
    $sqlText = $sqlText.Replace('__UPDATE_COUNT__', [string] $updates.Count)
    $sqlText = $sqlText.Replace('__VARIANT_COUNT__', [string] $variantUpdates.Count)
    $sqlText = $sqlText.Replace('__DELETE_COUNT__', [string] $deletes.Count)
    $sqlText = $sqlText.Replace('__END_TRANSACTION__', $(if ($DryRun) { 'ROLLBACK' } else { 'COMMIT' }))
    [IO.File]::WriteAllText($localSql, $sqlText, (New-Object System.Text.UTF8Encoding $false))

    foreach ($pair in @(@($localUpdate, $remoteUpdate), @($localVariant, $remoteVariant),
            @($localDelete, $remoteDelete), @($localSql, $remoteSql))) {
        & docker cp $pair[0] "${containerId}:$($pair[1])"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $($pair[0]) to the DB container." }
    }
    $report = @(& docker compose exec -T postgres psql --no-psqlrc -q -At -v ON_ERROR_STOP=1 `
        -U $databaseUser -d $databaseName -f $remoteSql)
    if ($LASTEXITCODE -ne 0) { throw 'FAQ update failed; the transaction was rolled back.' }
} finally {
    if ($containerId) {
        & docker exec $containerId rm -f -- $remoteUpdate $remoteVariant $remoteDelete $remoteSql 2>$null | Out-Null
    }
    Remove-Item -LiteralPath $localUpdate, $localVariant, $localDelete, $localSql -Force -ErrorAction SilentlyContinue
    Pop-Location
}

$updated = @($report | Where-Object { $_ -like 'UPDATED|*' })
$skipped = @($report | Where-Object { $_ -like 'SKIPPED|*' })
$deleted = @($report | Where-Object { $_ -like 'DELETED|*' })
$deleteSkipped = @($report | Where-Object { $_ -like 'DELETE_SKIPPED|*' })
$needsBackfill = @($updated | Where-Object { $_.Split('|')[3] -eq '1' } | ForEach-Object { $_.Split('|')[1] })

Write-Host "Updated $($updated.Count) FAQs, soft-deleted $($deleted.Count) FAQs."
foreach ($line in $skipped + $deleteSkipped) {
    $parts = $line.Split('|')
    Write-Host "  $($parts[0]) FAQ $($parts[1]): $($parts[2])"
}
if ($skipped | Where-Object { $_ -notlike '*|already applied' }) {
    Write-Warning 'Some FAQs were skipped because the DB no longer matches the pre-change seed (edited by an admin, or loaded with different IDs). Review them in the admin page.'
}
if ($DryRun) {
    Write-Host 'Dry run: the transaction was rolled back. Nothing was changed.'
} elseif ($needsBackfill.Count -gt 0) {
    Write-Host ''
    Write-Host "FAQs with changed questions or variants are not searchable until their vectors are rebuilt: $($needsBackfill -join ', ')"
    Write-Host 'Run the embedding backfill now (backend must be running):'
    Write-Host '  .\tools\faq-db\backfill-embeddings.ps1 -Email <admin email>'
}
if (-not $DryRun -and $updated.Count + $deleted.Count -gt 0) {
    Write-Host 'Restart the backend so the sub-question English vocabulary is rebuilt from the updated FAQs.'
}
