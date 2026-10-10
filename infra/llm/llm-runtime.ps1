param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("start", "stop", "status", "logs")]
    [string]$Command,

    [Parameter(Position = 1)]
    [ValidateSet("vllm", "sglang", "embedding")]
    [string]$Engine = "vllm"
)

$ErrorActionPreference = "Stop"

$ComposeFile = Join-Path $PSScriptRoot "docker-compose.yml"
$EnvFile = Join-Path $PSScriptRoot ".env"


# ── 사전 검사 ──────────────────────────────────────────────────

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
    Write-Host ""
    Write-Host "먼저 다음 명령을 실행하세요:"
    Write-Host "Copy-Item .\infra\llm\.env.example .\infra\llm\.env"
    exit 1
}


# ── Docker Compose 실행 Wrapper ────────────────────────────────

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


# ── Command ────────────────────────────────────────────────────

switch ($Command) {

    "start" {

        Write-Host ""
        Write-Host "Stopping current LLM runtime..."
        Write-Host ""

        # 기존 Chat / Embedding 런타임을 모두 종료합니다.
        Invoke-Compose @(
            "--profile", "vllm",
            "--profile", "embedding",
            "--profile", "sglang",
            "down"
        )


        if ($Engine -eq "vllm") {

            Write-Host ""
            Write-Host "Starting vLLM runtime..."
            Write-Host "  Chat      : vLLM"
            Write-Host "  Embedding : vLLM"
            Write-Host ""

            # 기본 vLLM 환경은 Chat + Embedding을 함께 실행합니다.
            Invoke-Compose @(
                "--profile", "vllm",
                "--profile", "embedding",
                "up", "-d",
                "vllm",
                "vllm-embedding"
            )

            Write-Host ""
            Write-Host "Started vLLM runtime."
            Write-Host "Chat API      : http://localhost:8000/v1"
            Write-Host "Embedding API : http://localhost:8001/v1"
            Write-Host ""
        }

        elseif ($Engine -eq "sglang") {

            Write-Host ""
            Write-Host "Starting SGLang runtime..."
            Write-Host ""

            # SGLang은 현재 비교/벤치마크용 Chat runtime으로만 실행합니다.
            Invoke-Compose @(
                "--profile", "sglang",
                "up", "-d",
                "sglang"
            )

            Write-Host ""
            Write-Host "Started SGLang runtime."
            Write-Host "Chat API : http://localhost:8000/v1"
            Write-Host ""
        }

        elseif ($Engine -eq "embedding") {

            Write-Host ""
            Write-Host "Starting embedding runtime only..."
            Write-Host ""

            # 임베딩 서버만 따로 테스트하고 싶을 때 사용합니다.
            Invoke-Compose @(
                "--profile", "embedding",
                "up", "-d",
                "vllm-embedding"
            )

            Write-Host ""
            Write-Host "Started embedding runtime."
            Write-Host "Embedding API : http://localhost:8001/v1"
            Write-Host ""
        }
    }


    "stop" {

        Write-Host ""
        Write-Host "Stopping LLM runtime..."
        Write-Host ""

        Invoke-Compose @(
            "--profile", "vllm",
            "--profile", "embedding",
            "--profile", "sglang",
            "down"
        )
    }


    "status" {

        Invoke-Compose @(
            "--profile", "vllm",
            "--profile", "embedding",
            "--profile", "sglang",
            "ps"
        )
    }


    "logs" {

        if ($Engine -eq "vllm") {

            Invoke-Compose @(
                "--profile", "vllm",
                "--profile", "embedding",
                "logs", "-f",
                "vllm",
                "vllm-embedding"
            )
        }

        elseif ($Engine -eq "sglang") {

            Invoke-Compose @(
                "--profile", "sglang",
                "logs", "-f",
                "sglang"
            )
        }

        elseif ($Engine -eq "embedding") {

            Invoke-Compose @(
                "--profile", "embedding",
                "logs", "-f",
                "vllm-embedding"
            )
        }
    }
}