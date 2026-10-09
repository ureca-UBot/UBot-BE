#!/usr/bin/env bash
# 모델 서버(Ollama, vLLM)를 멈추고, 시작하고, 준비됐는지 확인합니다.
# 쓰는 값: COMPOSE, ENV_FILE, DEPLOY_DIR, APP_NETWORK, AI_RUNTIME_MANAGED,
#          CHAT_ENGINE, EMBEDDING_ENGINE, USE_OLLAMA, USE_VLLM


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
