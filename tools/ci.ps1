$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
    $Gradle = Join-Path $RepoRoot "gradlew.bat"
}
else {
    $Gradle = Join-Path $RepoRoot "gradlew"
}

Push-Location $RepoRoot

try {
    Write-Host ""
    Write-Host "Running UBot backend CI..."
    Write-Host ""

    $ErrorActionPreference = "Continue"

    & $Gradle `
        clean `
        build `
        --no-daemon `
        --console=plain

    $exitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = "Stop"
    Pop-Location
}

Write-Host ""

if ($exitCode -ne 0) {
    Write-Host "CI FAILED."
    exit $exitCode
}

Write-Host "CI PASSED."