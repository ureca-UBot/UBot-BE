#!/usr/bin/env bash
# 백엔드(API)·답변 Worker·nginx를 교체하고, health check를 한 뒤, 오래된 백엔드 이미지를 지웁니다.
# 쓰는 값: COMPOSE, IMAGE_NAME, BACKEND_IMAGE, APP_NETWORK


start_application() {
  echo "== Start backend and AI worker =="

  "${COMPOSE[@]}" \
    up -d \
    backend \
    ai-worker

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


# Worker는 포트를 열지 않으므로, 같은 Docker 네트워크에 curl 컨테이너를 띄워 확인한다.
worker_health_check() {
  echo "== AI worker health check =="

  for i in $(seq 1 30); do

    if docker run --rm \
      --network "${APP_NETWORK}" \
      curlimages/curl:8.10.1 \
      -fsS \
      --max-time 5 \
      http://ai-worker:8080/actuator/health \
      >/dev/null 2>&1; then

      echo "AI worker is healthy"

      return 0
    fi

    echo "AI worker health check attempt ${i}/30 failed"

    sleep 5
  done

  echo "ERROR: AI worker health check failed"

  "${COMPOSE[@]}" \
    ps -a \
    ai-worker \
    || true

  "${COMPOSE[@]}" \
    logs \
    --tail=200 \
    ai-worker \
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
