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
  local all_ai_compose=(
    docker compose
    -p ubot
    --env-file "${ENV_FILE}"
    -f "${DEPLOY_DIR}/docker-compose.yml"
    -f "${DEPLOY_DIR}/docker-compose.ollama.yml"
    -f "${DEPLOY_DIR}/docker-compose.vllm.yml"
  )

  # 외부 런타임을 사용하는 경우,
  # 이 Compose 프로젝트가 이전에 띄운 로컬 AI 런타임은 모두 중지한다.
  if [ "${AI_RUNTIME_MANAGED}" = "false" ]; then
    echo "== Stop locally managed AI runtimes =="

    "${all_ai_compose[@]}" \
      stop \
      ollama \
      vllm \
      vllm-embedding \
      || true

    return 0
  fi

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


print_service_logs() {
  local service="$1"

  echo "== ${service} container status =="

  "${COMPOSE[@]}" \
    ps -a \
    "${service}" \
    || true

  echo "== ${service} logs =="

  "${COMPOSE[@]}" \
    logs \
    --tail=200 \
    "${service}" \
    || true
}


# 컨테이너 상태와 재시작 횟수를 "running 0" 형태로 출력한다.
# 컨테이너가 없으면 아무것도 출력하지 않는다.
service_state() {
  local service="$1"
  local container_id

  container_id="$(
    "${COMPOSE[@]}" ps -aq "${service}" 2>/dev/null \
      | head -n 1 \
      || true
  )"

  if [ -z "${container_id}" ]; then
    return 0
  fi

  docker inspect \
    -f '{{.State.Status}} {{.RestartCount}}' \
    "${container_id}" \
    2>/dev/null \
    || true
}


start_managed_ai() {
  if [ "${AI_RUNTIME_MANAGED}" != "true" ]; then
    return 0
  fi

  if [ "${USE_OLLAMA}" = "true" ]; then
    echo "== Start Ollama =="

    if ! "${COMPOSE[@]}" \
      up -d \
      --wait \
      ollama; then

      echo "ERROR: Ollama did not become healthy"
      print_service_logs ollama

      exit 1
    fi

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

  # 이 Compose 프로젝트가 띄운 모델 서버일 때만 서비스 이름을 받는다.
  local service="${5:-}"

  local response
  local deadline=$((SECONDS + timeout_sec))
  local attempt=1

  local state
  local status
  local restart_count
  local initial_restart_count=""

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

    # 컨테이너가 죽었거나 계속 재시작하면 제한 시간까지 기다리지 않는다.
    if [ -n "${service}" ]; then
      state="$(service_state "${service}")"
      status="${state%% *}"
      restart_count="${state##* }"
      restart_count="${restart_count:-0}"

      if [ -z "${initial_restart_count}" ]; then
        initial_restart_count="${restart_count}"
      fi

      if [ "${status}" = "exited" ] || \
         [ "${status}" = "dead" ]; then

        echo "ERROR: ${service} container is ${status}"
        print_service_logs "${service}"

        return 1
      fi

      if [ "$((restart_count - initial_restart_count))" -ge 2 ]; then
        echo "ERROR: ${service} container keeps restarting"
        print_service_logs "${service}"

        return 1
      fi
    fi

    attempt=$((attempt + 1))
    sleep 5
  done

  echo "ERROR: ${label} is not ready within ${timeout_sec}s"
  echo "ERROR: expected model ${model} was not found at ${url}"

  if [ -n "${service}" ]; then
    print_service_logs "${service}"
  fi

  return 1
}


validate_ai_from_app_network() {
  echo "== Validate AI from application network =="

  local timeout_sec=60

  # 외부 런타임이면 비워 둔다. 이 서버에 컨테이너가 없어 상태와 로그를 볼 수 없다.
  local ollama_service=""
  local vllm_chat_service=""
  local vllm_embedding_service=""

  if [ "${AI_RUNTIME_MANAGED}" = "true" ]; then
    # 첫 vLLM 기동은 모델 다운로드/컴파일 때문에 오래 걸릴 수 있음.
    timeout_sec=1200

    ollama_service=ollama
    vllm_chat_service=vllm
    vllm_embedding_service=vllm-embedding
  fi

  if [ "${CHAT_ENGINE}" = "ollama" ]; then
    check_model \
      "Ollama Chat" \
      "$(normalize_url "$(read_env OLLAMA_BASE_URL)")/api/tags" \
      "$(read_env OLLAMA_CHAT_MODEL)" \
      "${timeout_sec}" \
      "${ollama_service}"
  fi

  if [ "${EMBEDDING_ENGINE}" = "ollama" ]; then
    check_model \
      "Ollama Embedding" \
      "$(normalize_url "$(read_env OLLAMA_BASE_URL)")/api/tags" \
      "$(read_env OLLAMA_EMBEDDING_MODEL)" \
      "${timeout_sec}" \
      "${ollama_service}"
  fi

  if [ "${CHAT_ENGINE}" = "vllm" ]; then
    check_model \
      "vLLM Chat" \
      "$(normalize_url "$(read_env LLM_BASE_URL)")/models" \
      "$(read_env LLM_MODEL)" \
      "${timeout_sec}" \
      "${vllm_chat_service}"
  fi

  if [ "${EMBEDDING_ENGINE}" = "vllm" ]; then
    check_model \
      "vLLM Embedding" \
      "$(normalize_url "$(read_env EMBEDDING_BASE_URL)")/models" \
      "$(read_env EMBEDDING_MODEL)" \
      "${timeout_sec}" \
      "${vllm_embedding_service}"
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

  # 롤백에 쓸 수 있게 지금 이미지와 그 전 이미지 2개는 남긴다.
  local keep_previous=2
  local kept=0
  local ref

  while read -r ref; do

    if [ -z "${ref}" ]; then
      continue
    fi

    # 태그가 없는 이미지는 아래 prune이 정리한다.
    if [ "${ref}" = "${BACKEND_IMAGE}" ] || \
       [ "${ref##*:}" = "<none>" ]; then

      continue
    fi

    if [ "${kept}" -lt "${keep_previous}" ]; then
      kept=$((kept + 1))
      continue
    fi

    echo "Removing ${ref}"

    docker image rm \
      "${ref}" \
      >/dev/null 2>&1 \
      || true

  done < <(
    docker images \
      "${IMAGE_NAME}" \
      --format '{{.CreatedAt}}\t{{.Repository}}:{{.Tag}}' \
      | sort -r \
      | cut -f2
  )

  docker image prune \
    -f \
    >/dev/null 2>&1 \
    || true
}


verify_faq_embedding_coverage() {
  echo "== Verify FAQ embedding coverage =="

  local provider
  local model
  local profile_id
  local active_count
  local covered_count

  case "${EMBEDDING_ENGINE}" in
    ollama)
      provider="ollama"
      model="$(read_env OLLAMA_EMBEDDING_MODEL)"
      ;;

    vllm)
      provider="openai-compatible"
      model="$(read_env EMBEDDING_MODEL)"
      ;;

    *)
      echo "ERROR: unsupported embedding engine: ${EMBEDDING_ENGINE}"
      return 1
      ;;
  esac

  active_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
      -U "$(read_env POSTGRES_USER)" \
      -d "$(read_env POSTGRES_DB)" \
      -v ON_ERROR_STOP=1 \
      -Atc "
        SELECT COUNT(*)
        FROM faq
        WHERE deleted_at IS NULL;
      "
  )"

  echo "Active FAQ count: ${active_count}"

  # FAQ가 없으면 백필할 것도 없음.
  if [ "${active_count}" -eq 0 ]; then
    echo "No active FAQs. Embedding coverage check passed."
    return 0
  fi

  profile_id="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
      -U "$(read_env POSTGRES_USER)" \
      -d "$(read_env POSTGRES_DB)" \
      -v ON_ERROR_STOP=1 \
      -v provider="${provider}" \
      -v model="${model}" \
      -v profile_version="${EMBEDDING_PROFILE_VERSION}" \
      -At <<'SQL'
SELECT profile_id
FROM embedding_profiles
WHERE provider = :'provider'
  AND model_name = :'model'
  AND dimensions = 1024
  AND profile_version = :'profile_version';
SQL
  )"

  if [ -z "${profile_id}" ]; then
    echo "ERROR: embedding profile does not exist"
    echo "provider=${provider}"
    echo "model=${model}"
    echo "profileVersion=${EMBEDDING_PROFILE_VERSION}"
    echo "Run the embedding backfill for this profile."
    return 1
  fi

  covered_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
      -U "$(read_env POSTGRES_USER)" \
      -d "$(read_env POSTGRES_DB)" \
      -v ON_ERROR_STOP=1 \
      -v profile_id="${profile_id}" \
      -At <<'SQL'
SELECT COUNT(*)
FROM faq f
JOIN faq_embeddings fe
  ON fe.faq_id = f.id
WHERE f.deleted_at IS NULL
  AND fe.profile_id = :'profile_id'
  AND fe.vector_type = 'QUESTION'
  AND fe.faq_version = f.version;
SQL
  )"

  echo "Current FAQ embedding count: ${covered_count}/${active_count}"

  if [ "${covered_count}" -ne "${active_count}" ]; then
    echo "ERROR: FAQ embedding backfill is incomplete"
    echo "Missing: $((active_count - covered_count))"
    echo "Run tools/backfill-embeddings.ps1 and retry the deployment."
    return 1
  fi

  echo "FAQ embedding coverage check passed."
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

verify_faq_embedding_coverage

echo "== Container status =="

"${COMPOSE[@]}" \
  ps -a


echo "== Deployment completed =="

echo "BACKEND_IMAGE=${BACKEND_IMAGE}"
echo "CHAT_ENGINE=${CHAT_ENGINE}"
echo "EMBEDDING_ENGINE=${EMBEDDING_ENGINE}"