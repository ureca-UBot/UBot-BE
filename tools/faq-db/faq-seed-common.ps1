function Get-FaqSeedSummary {
    param([string] $ToolsPath)
    $output = & python -X utf8 (Join-Path $ToolsPath 'validate_baseline_seed.py') --json
    if ($LASTEXITCODE -ne 0) { throw 'FAQ seed CSV validation failed.' }
    return ($output | ConvertFrom-Json)
}

function Copy-FaqSeedCsv {
    param([string] $SeedPath, [string] $ContainerId)
    foreach ($filename in @('baseline-faqs.csv', 'baseline-faq-variants.csv')) {
        & docker cp (Join-Path $SeedPath $filename) "${ContainerId}:/tmp/$filename"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $filename to PostgreSQL." }
    }
}

function New-FaqSeedSql {
    param([string] $TemplatePath)
    $sql = Get-Content -LiteralPath $TemplatePath -Raw -Encoding UTF8
    if (-not $sql.Contains('-- __FAQ_COPIES__') -or -not $sql.Contains('-- __VARIANT_COPIES__')) {
        throw "Missing CSV copy markers in $TemplatePath"
    }
    $faqCopies = "\copy seed_faq (faq_id, category, question, answer, intent) FROM '/tmp/baseline-faqs.csv' WITH (FORMAT csv, HEADER true)"
    $variantCopies = "\copy seed_variant (origin_faq_id, variant_id, question) FROM '/tmp/baseline-faq-variants.csv' WITH (FORMAT csv, HEADER true)"
    $sql = $sql.Replace('-- __FAQ_COPIES__', $faqCopies).Replace('-- __VARIANT_COPIES__', $variantCopies)
    $path = Join-Path (Split-Path -Parent $TemplatePath) (".ubot-faq-seed-" + [Guid]::NewGuid().ToString('N') + '.sql')
    [IO.File]::WriteAllText($path, $sql, (New-Object System.Text.UTF8Encoding $false))
    return $path
}

function Remove-FaqSeedContainerFiles {
    param([string] $ContainerId, [string] $SqlName)
    if (-not $ContainerId) { return }
    $paths = New-Object 'System.Collections.Generic.List[string]'
    $paths.Add('/tmp/baseline-faqs.csv')
    $paths.Add('/tmp/baseline-faq-variants.csv')
    $paths.Add("/tmp/$SqlName")
    & docker exec $ContainerId rm -f -- $paths.ToArray() 2>$null | Out-Null
}
