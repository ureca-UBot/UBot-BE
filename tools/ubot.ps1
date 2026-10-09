param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("up", "down", "status")]
    [string]$Command
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

$EnvFile = Join-Path $RepoRoot ".env"

$CommonCompose = Join-Path $RepoRoot "docker-compose.yml"
$OllamaCompose = Join-Path $RepoRoot "docker-compose.ollama.yml"
$VllmCompose = Join-Path $RepoRoot "docker-compose.vllm.yml"

$AllComposeFiles = @(
    $CommonCompose,
    $OllamaCompose,
    $VllmCompose
)


function Get-EnvValue {
    param(
        [string]$Name,
        [string]$DefaultValue = $null
    )

    foreach ($line in Get-Content $EnvFile) {
        $trimmed = $line.Trim()

        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $parts = $trimmed -split "=", 2

        if ($parts.Count -ne 2) {
            continue
        }

        if ($parts[0].Trim() -ne $Name) {
            continue
        }

        $value = $parts[1].Trim()

        if (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))
        ) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        return $value
    }

    return $DefaultValue
}


function Invoke-Compose {
    param(
        [string[]]$Files,
        [string[]]$Arguments
    )

    $composeArguments = @()

    foreach ($file in $Files) {
        $composeArguments += @("-f", $file)
    }

    $composeArguments += @("--env-file", $EnvFile)
    $composeArguments += $Arguments

    & docker compose @composeArguments

    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed."
    }
}


function Wait-ModelServer {
    param(
        [string]$Name,
        [string]$BaseUrl,
        [string]$ExpectedModel,
        [int]$WaitSeconds = 300
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)

    Write-Host "Waiting for $Name..."

    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod `
                -Uri "$BaseUrl/models" `
                -TimeoutSec 5

            $models = @(
                $response.data |
                    ForEach-Object { $_.id }
            )

            if ($models -contains $ExpectedModel) {
                Write-Host "Ready: $Name ($ExpectedModel)"
                return
            }
        }
        catch {
        }

        Start-Sleep -Seconds 5
    }

    throw "$Name did not become ready within $WaitSeconds seconds."
}


if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker was not found."
}

if (-not (Test-Path $EnvFile)) {
    throw ".env was not found: $EnvFile"
}

foreach ($file in $AllComposeFiles) {
    if (-not (Test-Path $file)) {
        throw "Compose file was not found: $file"
    }
}


$AiMode = Get-EnvValue "AI_MODE"

if (-not $AiMode) {
    throw "AI_MODE is not configured in .env."
}

$AiMode = $AiMode.ToLowerInvariant()


switch ($Command) {

    "up" {

        switch ($AiMode) {

            "ollama" {
                Write-Host ""
                Write-Host "AI_MODE=ollama"
                Write-Host "Removing vLLM runtime..."

                Invoke-Compose `
                    -Files $AllComposeFiles `
                    -Arguments @(
                        "rm", "-f", "-s",
                        "vllm",
                        "vllm-embedding"
                    )

                Write-Host ""
                Write-Host "Starting UBot with Ollama..."

                Invoke-Compose `
                    -Files @(
                        $CommonCompose,
                        $OllamaCompose
                    ) `
                    -Arguments @(
                        "up",
                        "-d"
                    )

                Write-Host ""
                Write-Host "UBot Ollama environment started."
            }


            "vllm" {
                Write-Host ""
                Write-Host "AI_MODE=vllm"
                Write-Host "Removing Ollama runtime..."

                Invoke-Compose `
                    -Files $AllComposeFiles `
                    -Arguments @(
                        "rm", "-f", "-s",
                        "ollama",
                        "ollama-init"
                    )

                Write-Host ""
                Write-Host "Starting UBot with vLLM..."

                Invoke-Compose `
                    -Files @(
                        $CommonCompose,
                        $VllmCompose
                    ) `
                    -Arguments @(
                        "up",
                        "-d"
                    )

                $LlmPort = Get-EnvValue "LLM_PORT" "8000"
                $EmbeddingPort = Get-EnvValue "EMBEDDING_PORT" "8001"

                $LlmModel = Get-EnvValue "LLM_MODEL" "ubot-chat"
                $EmbeddingModel = Get-EnvValue "EMBEDDING_MODEL" "ubot-embedding"

                Wait-ModelServer `
                    -Name "Chat" `
                    -BaseUrl "http://localhost:$LlmPort/v1" `
                    -ExpectedModel $LlmModel

                Wait-ModelServer `
                    -Name "Embedding" `
                    -BaseUrl "http://localhost:$EmbeddingPort/v1" `
                    -ExpectedModel $EmbeddingModel

                Write-Host ""
                Write-Host "UBot vLLM environment is ready."
            }


            "custom" {
                throw "AI_MODE=custom is not supported by the one-click local runtime yet."
            }


            default {
                throw "Unsupported AI_MODE: $AiMode"
            }
        }
    }


    "down" {
        Write-Host ""
        Write-Host "Stopping UBot environment..."

        Invoke-Compose `
            -Files $AllComposeFiles `
            -Arguments @(
                "down"
            )

        Write-Host ""
        Write-Host "UBot environment stopped."
    }


    "status" {
        Invoke-Compose `
            -Files $AllComposeFiles `
            -Arguments @(
                "ps",
                "-a"
            )
    }
}