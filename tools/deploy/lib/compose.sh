#!/usr/bin/env bash
# Compose 명령을 구성하고 공통 서비스를 올립니다. 컨테이너 상태와 로그를 출력하는 함수도 여기에 있습니다.
# 정하는 값: COMPOSE
# 쓰는 값: ENV_FILE, DEPLOY_DIR, APP_NETWORK, AI_RUNTIME_MANAGED, USE_OLLAMA, USE_VLLM


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
