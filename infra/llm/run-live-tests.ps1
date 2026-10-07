param(
    [string]$LlmBaseUrl = "http://localhost:8000/v1",
    [string]$EmbeddingBaseUrl = "http://localhost:8001/v1",
    [string]$LlmModel = "ubot-chat",
    [string]$EmbeddingModel = "ubot-embedding",
    [int]$WaitSeconds = 300
)

# Runs the backend live tests against running vLLM servers in one step.
# It waits until both servers answer, then runs the LLM and embedding live tests.
# Messages are kept in ASCII so they display correctly in Windows PowerShell 5.1.

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Gradle = Join-Path $RepoRoot "gradlew.bat"

function Wait-ModelServer {
    param(
        [string]$Name,
        [string]$BaseUrl
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    Write-Host "Waiting for $Name server: $BaseUrl"

    while ($true) {
        try {
            $models = Invoke-RestMethod -Uri "$BaseUrl/models" -TimeoutSec 5
            Write-Host "Ready: $Name server serves '$($models.data[0].id)'"
            return
        } catch {
            if ((Get-Date) -gt $deadline) {
                throw "$Name server did not become ready within $WaitSeconds seconds: $BaseUrl"
            }
            Start-Sleep -Seconds 5
        }
    }
}

Wait-ModelServer -Name "LLM" -BaseUrl $LlmBaseUrl
Wait-ModelServer -Name "Embedding" -BaseUrl $EmbeddingBaseUrl

$env:LLM_LIVE_BASE_URL = $LlmBaseUrl
$env:LLM_LIVE_MODEL = $LlmModel
$env:EMBEDDING_LIVE_BASE_URL = $EmbeddingBaseUrl
$env:EMBEDDING_LIVE_MODEL = $EmbeddingModel

Push-Location $RepoRoot
try {
    Write-Host ""
    Write-Host "Running live tests..."
    Write-Host ""

    # Gradle and the JVM print warnings to stderr. With "Stop", Windows PowerShell 5.1 turns those lines
    # into terminating errors when output is redirected, so rely on the exit code instead.
    $ErrorActionPreference = "Continue"

    # cleanTest forces the tests to run. Without it Gradle reuses the previous result when the code is unchanged.
    & $Gradle cleanTest test `
        --tests "com.ubot.llm.client.OpenAiCompatibleLlmClientLiveTest" `
        --tests "com.ubot.embedding.client.OpenAiCompatibleEmbeddingClientLiveTest" `
        --console=plain
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = "Stop"
    Pop-Location
    Remove-Item Env:LLM_LIVE_BASE_URL, Env:LLM_LIVE_MODEL, Env:EMBEDDING_LIVE_BASE_URL, Env:EMBEDDING_LIVE_MODEL -ErrorAction SilentlyContinue
}

Write-Host ""
if ($exitCode -ne 0) {
    Write-Host "Live tests FAILED. See build\reports\tests\test\index.html"
    exit $exitCode
}

Write-Host "Live tests PASSED. See build\reports\tests\test\index.html"
