# UBot LLM Serving Runtime

UBot에서 사용할 LLM Serving Runtime을 Docker Compose로 관리합니다.

현재는 **vLLM을 기본 엔진**, **SGLang을 대체/비교 엔진**으로 사용하며 두 엔진 모두 Backend에서 동일한 OpenAI Compatible API로 접근할 수 있도록 구성합니다.

```text
Backend
   ↓
http://<LLM_HOST>:8000/v1
   ↓
model = ubot-chat
   ↓
vLLM 또는 SGLang
```

엔진을 바꾸더라도 Backend의 API 주소와 모델 이름은 변경하지 않는 것을 목표로 합니다.

---

## 현재 기준

```text
기본 Serving Engine
→ vLLM

대체/비교 Serving Engine
→ SGLang

기본 Model
→ Qwen/Qwen3-4B-AWQ

Served Model Name
→ ubot-chat

Port
→ 8000

OpenAI Compatible API
→ /v1/models
→ /v1/chat/completions
```

로컬 RTX 3060 12GB 환경과 AWS `g6.xlarge` NVIDIA L4 환경에서 사용할 수 있도록 구성합니다.

---

## 구조

```text
infra/llm/
├─ docker-compose.yml
├─ .env.example
├─ .env
├─ llm-runtime.ps1
└─ README.md
```

역할:

```text
docker-compose.yml
→ vLLM / SGLang 실행 정의

.env.example
→ 공통 설정 예시

.env
→ 실제 로컬 설정
→ Git 제외

llm-runtime.ps1
→ vLLM / SGLang 시작·종료·전환 Wrapper
```

---

# 사전 준비

## 1. Docker

Docker Desktop 또는 Docker Engine이 필요합니다.

확인:

```powershell
docker --version
docker compose version
```

---

## 2. NVIDIA GPU 확인

Docker Container에서 GPU가 보여야 합니다.

예:

```powershell
docker run --rm --gpus all `
  nvidia/cuda:13.0.0-base-ubuntu24.04 `
  nvidia-smi
```

정상적으로 GPU 정보가 출력되어야 합니다.

---

# 환경 설정

`.env.example`을 복사합니다.

```powershell
Copy-Item `
  .\infra\llm\.env.example `
  .\infra\llm\.env
```

예:

```dotenv
LLM_MODEL=Qwen/Qwen3-4B-AWQ
LLM_SERVED_MODEL_NAME=ubot-chat
LLM_PORT=8000
LLM_MAX_MODEL_LEN=4096

HF_TOKEN=

VLLM_IMAGE=vllm/vllm-openai:v0.31.0
SGLANG_IMAGE=lmsysorg/sglang:latest-runtime

VLLM_TOOL_CALL_PARSER=hermes
VLLM_ENABLE_THINKING=false

VLLM_GPU_MEMORY_UTILIZATION=0.6
SGLANG_MEM_FRACTION_STATIC=0.6
```

`.env`는 Git에 포함하지 않습니다.

```gitignore
infra/llm/.env
```

---

## 주요 환경 변수

### LLM_MODEL

실제로 Hugging Face에서 로딩할 모델입니다.

```dotenv
LLM_MODEL=Qwen/Qwen3-4B-AWQ
```

---

### LLM_SERVED_MODEL_NAME

Backend에 노출하는 모델 이름입니다.

```dotenv
LLM_SERVED_MODEL_NAME=ubot-chat
```

실제 모델이 바뀌어도 Backend에서는 계속 `ubot-chat`을 사용할 수 있습니다.

```text
Qwen/Qwen3-4B-AWQ
        ↓
    ubot-chat
```

---

### LLM_PORT

외부에 노출할 API Port입니다.

```dotenv
LLM_PORT=8000
```

Backend 기준 URL:

```text
http://<LLM_HOST>:8000/v1
```

---

### LLM_MAX_MODEL_LEN

최대 Context Length입니다.

```dotenv
LLM_MAX_MODEL_LEN=4096
```

로컬 개발에서는 GPU 메모리 사용량을 줄이기 위해 비교적 작은 값으로 사용합니다.

실제 AWS L4 환경에서는 모델과 VRAM 사용량을 확인한 뒤 조정합니다.

---

### HF_TOKEN

공개 Hugging Face 모델은 Token 없이도 받을 수 있습니다.

```dotenv
HF_TOKEN=
```

Token이 없으면 다음 경고가 표시될 수 있습니다.

```text
Warning: You are sending unauthenticated requests to the HF Hub.
```

공개 모델 사용 시 오류는 아닙니다.

Private/Gated Model을 사용하거나 Hugging Face Rate Limit을 높여야 하는 경우에만 설정합니다.

실제 Token은 `.env.example`, Dockerfile, Git Repository에 저장하지 않습니다.

---

# 실행

## vLLM 시작

```powershell
.\infra\llm\llm-runtime.ps1 start vllm
```

vLLM을 UBot의 기본 Serving Engine으로 사용합니다.

---

## SGLang 시작

```powershell
.\infra\llm\llm-runtime.ps1 start sglang
```

기존에 실행 중인 엔진을 종료한 뒤 SGLang을 시작합니다.

vLLM과 SGLang은 같은 GPU와 같은 Port `8000`을 사용하므로 동시에 실행하지 않습니다.

---

## 상태 확인

```powershell
.\infra\llm\llm-runtime.ps1 status
```

---

## 로그 확인

vLLM:

```powershell
.\infra\llm\llm-runtime.ps1 logs vllm
```

SGLang:

```powershell
.\infra\llm\llm-runtime.ps1 logs sglang
```

---

## 종료

```powershell
.\infra\llm\llm-runtime.ps1 stop
```

---

# Docker Compose 직접 실행

Wrapper를 사용하지 않고 Docker Compose를 직접 실행할 수도 있습니다.

vLLM:

```powershell
docker compose `
  -f .\infra\llm\docker-compose.yml `
  --env-file .\infra\llm\.env `
  --profile vllm `
  up
```

SGLang:

```powershell
docker compose `
  -f .\infra\llm\docker-compose.yml `
  --env-file .\infra\llm\.env `
  --profile sglang `
  up
```

종료:

```powershell
docker compose `
  -f .\infra\llm\docker-compose.yml `
  --env-file .\infra\llm\.env `
  --profile vllm `
  --profile sglang `
  down
```

---

# API 확인

서버가 완전히 올라온 뒤 확인합니다.

## Model 목록

```powershell
curl.exe http://localhost:8000/v1/models
```

정상 예:

```json
{
  "object": "list",
  "data": [
    {
      "id": "ubot-chat"
    }
  ]
}
```

vLLM과 SGLang 모두 `ubot-chat`이 보여야 합니다.

---

## Chat Completion

요청 형식:

```json
{
  "model": "ubot-chat",
  "messages": [
    {
      "role": "user",
      "content": "안녕하세요. U+ 고객센터 챗봇처럼 한 문장으로 인사해 주세요."
    }
  ],
  "max_tokens": 100
}
```

Endpoint:

```text
POST /v1/chat/completions
```

전체 URL:

```text
http://localhost:8000/v1/chat/completions
```

---

# Qwen3 Thinking Mode

Qwen3 계열은 Thinking Mode가 활성화되면 `<think>...</think>` 형태의 추론 텍스트가 출력될 수 있습니다.

일반 채팅 응답에서는 Thinking Mode를 끌 수 있습니다.

OpenAI Compatible 요청 예:

```json
{
  "model": "ubot-chat",
  "messages": [
    {
      "role": "user",
      "content": "안녕하세요."
    }
  ],
  "chat_template_kwargs": {
    "enable_thinking": false
  }
}
```

vLLM 서비스는 `VLLM_ENABLE_THINKING` 값을 `--default-chat-template-kwargs`로 넘겨 Thinking Mode의 기본값을 정합니다.

```dotenv
VLLM_ENABLE_THINKING=false
```

기본값은 `false`이며, `true`로 바꾸고 다시 시작하면 모든 요청에서 Thinking이 켜집니다.

Backend는 이 옵션을 요청에 넣지 않고 서버 기본값을 따릅니다.

Thinking을 켠 경우 답변 앞에 붙는 `<think>...</think>`는 Backend가 떼어 내고 최종 답변만 사용합니다.

---

# Tool Calling

Backend의 매장 조회는 Tool Calling을 사용합니다.

vLLM 서비스는 다음 옵션으로 실행합니다.

```text
--enable-auto-tool-choice
--tool-call-parser ${VLLM_TOOL_CALL_PARSER}
```

```dotenv
VLLM_TOOL_CALL_PARSER=hermes
```

Qwen3 계열은 `hermes` parser를 사용합니다.

모델을 바꾸면 parser도 그 모델에 맞는 값으로 바꿉니다.

이 옵션 없이 실행한 vLLM은 `tools`가 포함된 요청을 거절하므로 매장 관련 질문의 답변 생성이 실패합니다.

SGLang 서비스에는 Tool Calling 설정을 반영하지 않았습니다.

---

# vLLM 이미지 버전

`VLLM_IMAGE`는 `latest` 대신 검증한 버전으로 고정합니다.

```dotenv
VLLM_IMAGE=vllm/vllm-openai:v0.31.0
```

Tool parser, Thinking 옵션, OpenAI Compatible API 동작이 버전에 따라 달라질 수 있기 때문입니다.

버전을 올릴 때는 아래 항목을 다시 확인한 뒤 값을 바꿉니다.

```text
/v1/models
/v1/chat/completions 한국어 응답
Tool Calling 응답 (tool_calls)
Thinking 기본값
Backend 연결 테스트
```

Backend 연결 테스트는 vLLM을 띄운 상태에서 Repository 루트에서 실행합니다.

```powershell
$env:LLM_LIVE_BASE_URL = "http://localhost:8000/v1"
.\gradlew.bat test --tests 'com.ubot.llm.client.OpenAiCompatibleLlmClientLiveTest'
```

---

# Windows PowerShell 5.1 한글 인코딩

Windows PowerShell 5.1에서 `Invoke-RestMethod`로 한국어 JSON을 주고받을 경우 UTF-8 응답이 깨져 다음처럼 표시될 수 있습니다.

```text
ìëíì¸ì...
```

이 경우 Serving Engine이나 모델 자체의 문제라고 단정하지 않습니다.

UTF-8 Byte Array로 요청과 응답을 처리하면 정상적으로 확인할 수 있습니다.

예:

```powershell
Add-Type -AssemblyName System.Net.Http

$client = New-Object System.Net.Http.HttpClient

$body = @{
    model = "ubot-chat"
    messages = @(
        @{
            role = "user"
            content = "안녕하세요."
        }
    )
    max_tokens = 100
    chat_template_kwargs = @{
        enable_thinking = $false
    }
} | ConvertTo-Json -Depth 10

$content = New-Object System.Net.Http.StringContent(
    $body,
    [System.Text.Encoding]::UTF8,
    "application/json"
)

$response = $client.PostAsync(
    "http://localhost:8000/v1/chat/completions",
    $content
).Result

$json = [System.Text.Encoding]::UTF8.GetString(
    $response.Content.ReadAsByteArrayAsync().Result
)

($json | ConvertFrom-Json).choices[0].message.content
```

Spring Backend에서는 일반적인 JSON HTTP 통신을 UTF-8로 처리하므로 이 문제는 주로 PowerShell 5.1 수동 테스트 시 주의합니다.

---

# vLLM / SGLang 전환 정책

Backend는 Serving Engine 종류를 알 필요가 없습니다.

```text
Backend
   ↓
LLM_BASE_URL=http://<GPU_HOST>:8000/v1
LLM_MODEL=ubot-chat
   ↓
현재 활성화된 Serving Engine
   ├─ vLLM
   └─ SGLang
```

따라서 Backend에 다음과 같은 엔진별 Client를 각각 만들지 않는 것을 목표로 합니다.

```text
VllmClient
SglangClient
```

두 엔진 모두 OpenAI Compatible API를 사용하므로 Backend에서는 하나의 공통 LLM Client를 사용합니다.

---

# 현재 Engine 선택

현재 테스트에서는 vLLM을 기본 Serving Engine으로 사용합니다.

```text
vLLM
→ 기본

SGLang
→ 대체/벤치마크용
```

로컬 RTX 3060 + Qwen3-4B-AWQ 환경에서는 SGLang이 vLLM보다 느리게 동작하는 현상을 확인했습니다.

다만 해당 결과는 로컬 GPU, Docker/WSL, 단일 요청 중심의 개발 환경 결과이므로 최종 성능 판단은 AWS L4에서 동일 조건으로 벤치마크한 뒤 결정합니다.

---

# AWS GPU Server

Terraform으로 생성한 GPU 테스트 서버에서 동일한 Compose 구성을 실행할 수 있습니다.

기본 환경:

```text
Instance
→ g6.xlarge

GPU
→ NVIDIA L4

VRAM
→ 약 24GB

OS
→ Ubuntu 24.04 AWS Deep Learning Base AMI

Docker
→ 설치 및 실행 완료

NVIDIA Container Runtime
→ 설정 완료
```

서버 위치:

```text
/opt/ubot/llm
```

실제 배포 시 Repository 또는 `infra/llm` 파일을 서버에 준비한 뒤 Docker Compose로 실행합니다.

AWS에서는 화면 출력에 GPU 메모리를 사용하지 않으므로 로컬보다 높은 GPU Memory Utilization을 사용할 수 있습니다.

단, 값은 실제 VRAM 사용량을 확인한 뒤 조정합니다.

---

# Security Group

LLM API Port는 기본적으로 외부 전체에 공개하지 않습니다.

```text
Port
→ TCP 8000

허용 대상
→ Backend Server IP /32
```

Terraform의 `llm_allowed_cidrs`를 사용합니다.

예:

```hcl
llm_allowed_cidrs = ["203.0.113.10/32"]
```

```text
Backend
   ↓ TCP 8000
GPU LLM Server
```

`0.0.0.0/0`로 공개하지 않습니다.

---

# 성능 비교

최종 Engine 선택은 동일 조건에서 비교합니다.

예:

```text
Model
→ Qwen3-4B-AWQ

GPU
→ NVIDIA L4 1장

Served Model Name
→ ubot-chat

Context Length
→ 동일

Output Token
→ 동일
```

비교할 항목:

```text
TTFT
Time To First Token

ITL
Inter-Token Latency

E2E Latency
전체 응답 시간

Requests/sec

Output Tokens/sec

GPU Utilization

VRAM Usage

실패 요청 수
```

Concurrency 예:

```text
1
4
8
16
32
```

현재 단계에서는 Serving Runtime 구성과 API 호환성 검증을 우선하며 세부 성능 튜닝은 별도 벤치마크 결과를 기준으로 진행합니다.

---

# 검증 완료 항목

현재 로컬에서 확인한 항목:

```text
vLLM Docker 실행              ✅
SGLang Docker 실행            ✅

Qwen3-0.6B Smoke Test          ✅
Qwen3-4B-AWQ 실행             ✅

vLLM /v1/models               ✅
vLLM /v1/chat/completions     ✅

SGLang /v1/models             ✅
SGLang /v1/chat/completions   ✅

Port 8000 통일                ✅
Served Model ubot-chat 통일    ✅
한국어 요청/응답               ✅
Engine 전환                   ✅
```

vLLM `v0.31.0` + Qwen3-4B-AWQ, 로컬 RTX 3060 12GB에서 확인한 항목 (2026-10-07):

```text
Tool Calling (hermes parser)        ✅
Thinking 기본값 꺼짐                 ✅
요청별 Thinking 켜기                 ✅
Backend 일반 답변 연결               ✅
Backend 매장 조회 Tool Calling 연결   ✅
```

AWS L4 환경에서는 아직 확인하지 않았습니다.

---

# 예정된 Serving 구조

Chat, Embedding, Reranker는 Port와 Served Model Name을 나눠서 운영할 예정입니다.

```text
:8000
ubot-chat
→ Generation (현재)

:8001
ubot-embedding
→ Embedding (예정)

:8002
ubot-reranker
→ Reranker (예정)
```

`8001`, `8002` 서비스는 아직 구성하지 않았습니다.

Embedding은 현재 Ollama 기반 구성을 그대로 유지합니다.

Backend 설정 이름과 전환 시 지켜야 할 절차는 [LLM 모듈 안내](../../docs/how-to/llm-module.md#embedding-provider-전환-기반-예정)에 있습니다.
