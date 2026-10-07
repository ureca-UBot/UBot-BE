param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("start", "stop", "status", "logs")]
    [string]$Command,

    [Parameter(Position = 1)]
    [ValidateSet("vllm", "sglang")]
    [string]$Engine = "vllm"
)

$ErrorActionPreference = "Stop"

$ComposeFile = Join-Path $PSScriptRoot "docker-compose.yml"
$EnvFile = Join-Path $PSScriptRoot ".env"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker를 찾을 수 없습니다."
    exit 1
}

if (-not (Test-Path $ComposeFile)) {
    Write-Error "docker-compose.yml을 찾을 수 없습니다: $ComposeFile"
    exit 1
}

if (-not (Test-Path $EnvFile)) {
    Write-Error ".env 파일을 찾을 수 없습니다."
    Write-Host "먼저 다음 명령을 실행하세요:"
    Write-Host "Copy-Item .\infra\llm\.env.example .\infra\llm\.env"
    exit 1
}

function Invoke-Compose {
    param(
        [string[]]$Arguments
    )

    & docker compose `
        -f $ComposeFile `
        --env-file $EnvFile `
        @Arguments

    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

switch ($Command) {

    "start" {
        Write-Host ""
        Write-Host "Stopping current LLM runtime..."
        Write-Host ""

        Invoke-Compose @(
            "--profile", "vllm",
            "--profile", "sglang",
            "down"
        )

        Write-Host ""
        Write-Host "Starting LLM runtime: $Engine"
        Write-Host ""

        Invoke-Compose @(
            "--profile", $Engine,
            "up", "-d"
        )

        Write-Host ""
        Write-Host "Started: $Engine"
        Write-Host "API: http://localhost:8000/v1"
        Write-Host ""
    }

    "stop" {
        Write-Host ""
        Write-Host "Stopping LLM runtime..."
        Write-Host ""

        Invoke-Compose @(
            "--profile", "vllm",
            "--profile", "sglang",
            "down"
        )
    }

    "status" {
        Invoke-Compose @(
            "--profile", "vllm",
            "--profile", "sglang",
            "ps"
        )
    }

    "logs" {
        Invoke-Compose @(
            "--profile", $Engine,
            "logs", "-f", $Engine
        )
    }
}