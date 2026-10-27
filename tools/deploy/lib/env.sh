#!/usr/bin/env bash
# 서버의 .env를 읽고 검증합니다.
# 정하는 값: SPRING_PROFILE, AI_MODE, AI_RUNTIME_MANAGED, EMBEDDING_PROFILE_VERSION,
#            CHAT_ENGINE, EMBEDDING_ENGINE, USE_OLLAMA, USE_VLLM
# 쓰는 값: ENV_FILE


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
