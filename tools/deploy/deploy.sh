#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="${HOME}/ubot"
ARTIFACT_DIR="${HOME}/ubot-deploy"
ENV_FILE="${DEPLOY_DIR}/.env"

IMAGE_NAME="${IMAGE_NAME:?IMAGE_NAME is required}"
GITHUB_SHA="${GITHUB_SHA:?GITHUB_SHA is required}"
BACKEND_IMAGE="${IMAGE_NAME}:${GITHUB_SHA}"

APP_NETWORK="ubot_default"

export BACKEND_IMAGE

cd "${DEPLOY_DIR}"


read_env() {
  local key="$1"

  grep -E "^${key}=" "${ENV_FILE}" 2>/dev/null \
    | tail -n 1 \
    | cut -d= -f2- \
    | tr -d '\r' \
    | sed 's/^[[:space:]]*//;s/[[:space:]]*$//;s/^"//;s/"$//' \
    || true
}


require_env() {
  local key="$1"
  local value

  value="$(read_env "${key}")"

  if [ -z "${value}" ]; then
    echo "ERROR: ${key} is required in ${ENV_FILE}"
    exit 1
  fi
}


require_envs() {
  local key

  for key in "$@"; do
    require_env "${key}"
  done
}


normalize_url() {
  printf '%s' "${1%/}"
}


require_engine() {
  local label="$1"
  local value="$2"

  case "${value}" in
    ollama|vllm)
      ;;
    *)
      echo "ERROR: ${label} must be ollama or vllm: ${value}"
      exit 1
      ;;
  esac
}


validate_managed_runtime() {
  if [ "${AI_RUNTIME_MANAGED}" != "true" ]; then
    return 0
  fi

  if [ "${USE_OLLAMA}" = "true" ]; then
    require_env OLLAMA_PORT

    if [ "$(normalize_url "$(read_env OLLAMA_BASE_URL)")" != "http://ollama:11434" ]; then
      echo "ERROR: managed Ollama requires OLLAMA_BASE_URL=http://ollama:11434"
      exit 1
    fi
  fi

  if [ "${CHAT_ENGINE}" = "vllm" ]; then
    require_envs \
      LLM_PORT \
      LLM_HF_MODEL \
      LLM_SERVED_MODEL_NAME

    if [ "$(read_env LLM_MODEL)" != "$(read_env LLM_SERVED_MODEL_NAME)" ]; then
      echo "ERROR: LLM_MODEL must equal LLM_SERVED_MODEL_NAME"
      exit 1
    fi

    if [ "$(normalize_url "$(read_env LLM_BASE_URL)")" != "http://vllm:8000/v1" ]; then
      echo "ERROR: managed vLLM chat requires LLM_BASE_URL=http://vllm:8000/v1"
      exit 1
    fi
  fi

  if [ "${EMBEDDING_ENGINE}" = "vllm" ]; then
    require_envs \
      EMBEDDING_PORT \
      EMBEDDING_HF_MODEL \
      EMBEDDING_SERVED_MODEL_NAME

    if [ "$(read_env EMBEDDING_MODEL)" != "$(read_env EMBEDDING_SERVED_MODEL_NAME)" ]; then
      echo "ERROR: EMBEDDING_MODEL must equal EMBEDDING_SERVED_MODEL_NAME"
      exit 1
    fi

    if [ "$(normalize_url "$(read_env EMBEDDING_BASE_URL)")" != "http://vllm-embedding:8000/v1" ]; then
      echo "ERROR: managed vLLM embedding requires EMBEDDING_BASE_URL=http://vllm-embedding:8000/v1"
      exit 1
    fi
  fi

  if [ "${USE_VLLM}" = "true" ]; then
    echo "== NVIDIA GPU =="

    if ! command -v nvidia-smi >/dev/null 2>&1; then
      echo "ERROR: nvidia-smi was not found"
      exit 1
    fi

    nvidia-smi
  fi
}


validate_environment() {
  echo "== Validate deployment environment =="

  if [ ! -f "${ENV_FILE}" ]; then
    echo "ERROR: ${ENV_FILE} does not exist"
    exit 1
  fi

  require_envs \
    SPRING_PROFILES_ACTIVE \
    AI_MODE \
    AI_RUNTIME_MANAGED \
    EMBEDDING_PROFILE_VERSION \
    POSTGRES_DB \
    POSTGRES_USER \
    POSTGRES_PASSWORD \
    POSTGRES_PORT \
    REDIS_PORT \
    PROMETHEUS_PORT \
    JWT_SECRET \
    KAKAO_REST_API_KEY

  SPRING_PROFILE="$(read_env SPRING_PROFILES_ACTIVE)"
  AI_MODE="$(read_env AI_MODE)"
  AI_RUNTIME_MANAGED="$(read_env AI_RUNTIME_MANAGED)"
  EMBEDDING_PROFILE_VERSION="$(read_env EMBEDDING_PROFILE_VERSION)"

  if [ "${SPRING_PROFILE}" != "prod" ]; then
    echo "ERROR: SPRING_PROFILES_ACTIVE must be prod"
    exit 1
  fi

  case "${AI_RUNTIME_MANAGED}" in
    true|false)
      ;;
    *)
      echo "ERROR: AI_RUNTIME_MANAGED must be true or false"
      exit 1
      ;;
  esac

  if ! [[ "${EMBEDDING_PROFILE_VERSION}" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: EMBEDDING_PROFILE_VERSION must be >= 1"
    exit 1
  fi

  case "${AI_MODE}" in
    ollama)
      CHAT_ENGINE=ollama
      EMBEDDING_ENGINE=ollama

      if [ -n "$(read_env AI_CHAT_ENGINE)" ] || \
         [ -n "$(read_env AI_EMBEDDING_ENGINE)" ]; then

        echo "ERROR: AI_CHAT_ENGINE/AI_EMBEDDING_ENGINE are only allowed with AI_MODE=custom"
        exit 1
      fi
      ;;

    vllm)
      CHAT_ENGINE=vllm
      EMBEDDING_ENGINE=vllm

      if [ -n "$(read_env AI_CHAT_ENGINE)" ] || \
         [ -n "$(read_env AI_EMBEDDING_ENGINE)" ]; then

        echo "ERROR: AI_CHAT_ENGINE/AI_EMBEDDING_ENGINE are only allowed with AI_MODE=custom"
        exit 1
      fi
      ;;

    custom)
      require_envs \
        AI_CHAT_ENGINE \
        AI_EMBEDDING_ENGINE

      CHAT_ENGINE="$(read_env AI_CHAT_ENGINE)"
      EMBEDDING_ENGINE="$(read_env AI_EMBEDDING_ENGINE)"
      ;;

    *)
      echo "ERROR: AI_MODE must be ollama, vllm, or custom"
      exit 1
      ;;
  esac

  require_engine \
    "AI_CHAT_ENGINE" \
    "${CHAT_ENGINE}"

  require_engine \
    "AI_EMBEDDING_ENGINE" \
    "${EMBEDDING_ENGINE}"

  USE_OLLAMA=false
  USE_VLLM=false

  if [ "${CHAT_ENGINE}" = "ollama" ] || \
     [ "${EMBEDDING_ENGINE}" = "ollama" ]; then

    USE_OLLAMA=true
  fi

  if [ "${CHAT_ENGINE}" = "vllm" ] || \
     [ "${EMBEDDING_ENGINE}" = "vllm" ]; then

    USE_VLLM=true
  fi

  if [ "${USE_OLLAMA}" = "true" ]; then
    require_env OLLAMA_BASE_URL
  fi

  if [ "${CHAT_ENGINE}" = "ollama" ]; then
    require_env OLLAMA_CHAT_MODEL
  fi

  if [ "${EMBEDDING_ENGINE}" = "ollama" ]; then
    require_env OLLAMA_EMBEDDING_MODEL
  fi

  if [ "${CHAT_ENGINE}" = "vllm" ]; then
    require_envs \
      LLM_BASE_URL \
      LLM_MODEL
  fi

  if [ "${EMBEDDING_ENGINE}" = "vllm" ]; then
    require_envs \
      EMBEDDING_BASE_URL \
      EMBEDDING_MODEL
  fi

  validate_managed_runtime

  echo "SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}"
  echo "AI_MODE=${AI_MODE}"
  echo "AI_RUNTIME_MANAGED=${AI_RUNTIME_MANAGED}"
  echo "CHAT_ENGINE=${CHAT_ENGINE}"
  echo "EMBEDDING_ENGINE=${EMBEDDING_ENGINE}"
  echo "EMBEDDING_PROFILE_VERSION=${EMBEDDING_PROFILE_VERSION}"
}


build_compose_command() {
  COMPOSE=(
    docker compose
    -p ubot
    --env-file "${ENV_FILE}"
    -f "${DEPLOY_DIR}/docker-compose.yml"
    -f "${DEPLOY_DIR}/docker-compose.deploy.yml"
  )

  if [ "${AI_RUNTIME_MANAGED}" = "true" ] && \
     [ "${USE_OLLAMA}" = "true" ]; then

    COMPOSE+=(
      -f "${DEPLOY_DIR}/docker-compose.ollama.yml"
    )
  fi

  if [ "${AI_RUNTIME_MANAGED}" = "true" ] && \
     [ "${USE_VLLM}" = "true" ]; then

    COMPOSE+=(
      -f "${DEPLOY_DIR}/docker-compose.vllm.yml"
    )
  fi

  echo "== Validate Compose =="

  "${COMPOSE[@]}" config >/dev/null
}


stop_unused_managed_runtimes() {
  if [ "${AI_RUNTIME_MANAGED}" != "true" ]; then
    return 0
  fi

  local all_ai_compose=(
    docker compose
    -p ubot
    --env-file "${ENV_FILE}"
    -f "${DEPLOY_DIR}/docker-compose.yml"
    -f "${DEPLOY_DIR}/docker-compose.ollama.yml"
    -f "${DEPLOY_DIR}/docker-compose.vllm.yml"
  )

  if [ "${USE_OLLAMA}" = "false" ]; then
    echo "== Stop unused Ollama runtime =="

    "${all_ai_compose[@]}" \
      stop \
      ollama \
      || true
  fi

  if [ "${USE_VLLM}" = "false" ]; then
    echo "== Stop unused vLLM runtime =="

    "${all_ai_compose[@]}" \
      stop \
      vllm \
      vllm-embedding \
      || true
  fi
}


start_common_infrastructure() {
  echo "== Start common infrastructure =="

  "${COMPOSE[@]}" \
    up -d \
    postgres \
    redis \
    prometheus

  if ! docker network inspect "${APP_NETWORK}" >/dev/null 2>&1; then
    echo "ERROR: Docker network ${APP_NETWORK} was not created"
    exit 1
  fi
}


start_managed_ai() {
  if [ "${AI_RUNTIME_MANAGED}" != "true" ]; then
    return 0
  fi

  if [ "${USE_OLLAMA}" = "true" ]; then
    echo "== Start Ollama =="

    "${COMPOSE[@]}" \
      up -d \
      --wait \
      ollama

    echo "== Prepare Ollama models =="

    "${COMPOSE[@]}" \
      run --rm \
      ollama-init
  fi

  local vllm_services=()

  if [ "${CHAT_ENGINE}" = "vllm" ]; then
    vllm_services+=(vllm)
  fi

  if [ "${EMBEDDING_ENGINE}" = "vllm" ]; then
    vllm_services+=(vllm-embedding)
  fi

  if [ "${#vllm_services[@]}" -gt 0 ]; then
    echo "== Start vLLM =="

    "${COMPOSE[@]}" \
      up -d \
      "${vllm_services[@]}"
  fi
}


check_model() {
  local label="$1"
  local url="$2"
  local model="$3"
  local timeout_sec="$4"

  local response
  local deadline=$((SECONDS + timeout_sec))
  local attempt=1

  echo "Checking ${label}"
  echo "Expected model: ${model}"

  while [ "${SECONDS}" -lt "${deadline}" ]; do

    response="$(
      docker run --rm \
        --network "${APP_NETWORK}" \
        curlimages/curl:8.10.1 \
        -fsS \
        --connect-timeout 5 \
        --max-time 10 \
        "${url}" \
        2>/dev/null \
        || true
    )"

    if [ -n "${response}" ] && \
       printf '%s' "${response}" \
         | grep -Fq "\"${model}\""; then

      echo "${label} is ready"
      return 0
    fi

    echo "${label} readiness attempt ${attempt} failed"

    attempt=$((attempt + 1))
    sleep 5
  done

  echo "ERROR: ${label} is not ready within ${timeout_sec}s"
  echo "ERROR: expected model ${model} was not found at ${url}"

  return 1
}


validate_ai_from_app_network() {
  echo "== Validate AI from application network =="

  local timeout_sec=60

  if [ "${AI_RUNTIME_MANAGED}" = "true" ]; then
    # 첫 vLLM 기동은 모델 다운로드/컴파일 때문에 오래 걸릴 수 있음.
    timeout_sec=1200
  fi

  if [ "${CHAT_ENGINE}" = "ollama" ]; then
    check_model \
      "Ollama Chat" \
      "$(normalize_url "$(read_env OLLAMA_BASE_URL)")/api/tags" \
      "$(read_env OLLAMA_CHAT_MODEL)" \
      "${timeout_sec}"
  fi

  if [ "${EMBEDDING_ENGINE}" = "ollama" ]; then
    check_model \
      "Ollama Embedding" \
      "$(normalize_url "$(read_env OLLAMA_BASE_URL)")/api/tags" \
      "$(read_env OLLAMA_EMBEDDING_MODEL)" \
      "${timeout_sec}"
  fi

  if [ "${CHAT_ENGINE}" = "vllm" ]; then
    check_model \
      "vLLM Chat" \
      "$(normalize_url "$(read_env LLM_BASE_URL)")/models" \
      "$(read_env LLM_MODEL)" \
      "${timeout_sec}"
  fi

  if [ "${EMBEDDING_ENGINE}" = "vllm" ]; then
    check_model \
      "vLLM Embedding" \
      "$(normalize_url "$(read_env EMBEDDING_BASE_URL)")/models" \
      "$(read_env EMBEDDING_MODEL)" \
      "${timeout_sec}"
  fi
}


start_application() {
  echo "== Start backend =="

  "${COMPOSE[@]}" \
    up -d \
    backend

  echo "== Recreate nginx =="

  "${COMPOSE[@]}" \
    up -d \
    --no-deps \
    --force-recreate \
    nginx
}


health_check() {
  echo "== Health check =="

  for i in $(seq 1 30); do

    if curl -fsS \
      http://127.0.0.1/actuator/health; then

      echo
      echo "Backend is healthy"

      return 0
    fi

    echo "Health check attempt ${i}/30 failed"

    sleep 5
  done

  echo
  echo "ERROR: backend health check failed"

  echo "== Container status =="

  "${COMPOSE[@]}" \
    ps -a \
    || true

  echo "== Backend / nginx logs =="

  "${COMPOSE[@]}" \
    logs \
    --tail=200 \
    backend \
    nginx \
    || true

  return 1
}


cleanup_old_backend_images() {
  echo "== Cleanup old backend images =="

  local ref

  while read -r ref; do

    if [ -z "${ref}" ]; then
      continue
    fi

    if [ "${ref}" = "${BACKEND_IMAGE}" ]; then
      continue
    fi

    docker image rm \
      "${ref}" \
      >/dev/null 2>&1 \
      || true

  done < <(
    docker images \
      "${IMAGE_NAME}" \
      --format '{{.Repository}}:{{.Tag}}'
  )

  docker image prune \
    -f \
    >/dev/null 2>&1 \
    || true
}


validate_environment


echo "== Load backend Docker image =="

gunzip -c \
  "${ARTIFACT_DIR}/ubot-be.tar.gz" \
  | docker load


build_compose_command

stop_unused_managed_runtimes

start_common_infrastructure

start_managed_ai

validate_ai_from_app_network

start_application

health_check

cleanup_old_backend_images


echo "== Container status =="

"${COMPOSE[@]}" \
  ps -a


echo "== Deployment completed =="

echo "BACKEND_IMAGE=${BACKEND_IMAGE}"
echo "CHAT_ENGINE=${CHAT_ENGINE}"
echo "EMBEDDING_ENGINE=${EMBEDDING_ENGINE}"