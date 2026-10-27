param(
    [string]$LlmBaseUrl = "http://localhost:8000/v1",
    [string]$EmbeddingBaseUrl = "http://localhost:8001/v1",
    [string]$LlmModel = "ubot-chat",
    [string]$EmbeddingModel = "ubot-embedding",
    [int]$WaitSeconds = 300
)

# Runs backend live tests against running OpenAI-compatible model servers.
# Both Chat and Embedding servers must already be running.
# Messages are kept in ASCII for Windows PowerShell 5.1 compatibility.

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Gradle = Join-Path $RepoRoot "gradlew.bat"


function Wait-ModelServer {
    param(
        [string]$Name,
        [string]$BaseUrl,
        [string]$ExpectedModel
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)

    Write-Host "Waiting for $Name server: $BaseUrl"

    while ($true) {
        try {
            $models = Invoke-RestMethod `
                -Uri "$BaseUrl/models" `
                -TimeoutSec 5

            $servedModels = @(
                $models.data |
                    ForEach-Object { $_.id }
            )

            if ($servedModels -contains $ExpectedModel) {
                Write-Host "Ready: $Name server serves '$ExpectedModel'"
                return
            }

            Write-Host (
                "$Name server is up, but expected model " +
                "'$ExpectedModel' is not ready. " +
                "Current models: $($servedModels -join ', ')"
            )
        }
        catch {
            # Server is not ready yet.
        }

        if ((Get-Date) -gt $deadline) {
            throw (
                "$Name server did not become ready with model " +
                "'$ExpectedModel' within $WaitSeconds seconds: $BaseUrl"
            )
        }

        Start-Sleep -Seconds 5
    }
}


Wait-ModelServer `
    -Name "LLM" `
    -BaseUrl $LlmBaseUrl `
    -ExpectedModel $LlmModel

Wait-ModelServer `
    -Name "Embedding" `
    -BaseUrl $EmbeddingBaseUrl `
    -ExpectedModel $EmbeddingModel


$previousEnvironment = @{
    LLM_LIVE_BASE_URL       = $env:LLM_LIVE_BASE_URL
    LLM_LIVE_MODEL          = $env:LLM_LIVE_MODEL
    EMBEDDING_LIVE_BASE_URL = $env:EMBEDDING_LIVE_BASE_URL
    EMBEDDING_LIVE_MODEL    = $env:EMBEDDING_LIVE_MODEL
}


$env:LLM_LIVE_BASE_URL = $LlmBaseUrl
$env:LLM_LIVE_MODEL = $LlmModel
$env:EMBEDDING_LIVE_BASE_URL = $EmbeddingBaseUrl
$env:EMBEDDING_LIVE_MODEL = $EmbeddingModel


Push-Location $RepoRoot

try {
    Write-Host ""
    Write-Host "Running live tests..."
    Write-Host ""

    # Gradle and the JVM may print warnings to stderr.
    # Windows PowerShell 5.1 can treat those lines as terminating errors
    # when ErrorActionPreference is Stop, so rely on Gradle's exit code.
    $ErrorActionPreference = "Continue"

    # cleanTest forces live tests to run instead of reusing previous results.
    & $Gradle cleanTest test `
        --tests "com.ubot.llm.client.OpenAiCompatibleLlmClientLiveTest" `
        --tests "com.ubot.embedding.client.OpenAiCompatibleEmbeddingClientLiveTest" `
        --console=plain

    $exitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = "Stop"

    Pop-Location

    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        if ($null -eq $entry.Value) {
            Remove-Item "Env:$($entry.Key)" `
                -ErrorAction SilentlyContinue
        }
        else {
            Set-Item "Env:$($entry.Key)" $entry.Value
        }
    }
}


Write-Host ""

if ($exitCode -ne 0) {
    Write-Host "Live tests FAILED."
    Write-Host "See build\reports\tests\test\index.html"
    exit $exitCode
}

Write-Host "Live tests PASSED."
Write-Host "See build\reports\tests\test\index.html"