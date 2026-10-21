#!/usr/bin/env bash
# 미적용 마이그레이션이 없을 때만 API 교체 전에 워커와 상담 색인을 준비함

validate_counsel_environment() {
  COUNSEL_ENABLED="$(read_env COUNSEL_ENABLED)"
  COUNSEL_ENABLED="${COUNSEL_ENABLED:-true}"
  local worker_enabled transport
  worker_enabled="$(read_env COUNSEL_EMBEDDING_WORKER_ENABLED)"
  transport="$(read_env COUNSEL_EMBEDDING_TRANSPORT)"

  case "${COUNSEL_ENABLED}" in
    false) return 0 ;;
    true) ;;
    *) echo "ERROR: COUNSEL_ENABLED must be true or false"; return 1 ;;
  esac

  if [ "${worker_enabled:-true}" != "true" ]; then
    echo "ERROR: counseling requires COUNSEL_EMBEDDING_WORKER_ENABLED=true"
    return 1
  fi
  if [ "${transport:-worker}" != "worker" ]; then
    echo "ERROR: deployed counseling requires COUNSEL_EMBEDDING_TRANSPORT=worker"
    return 1
  fi
}

COUNSEL_SEED_AFTER_SWITCH=false

run_counsel_seed() {
  "${COMPOSE[@]}" run --rm -T --no-deps backend \
    --counsel.seed-on-start=true \
    --counsel.seed-only=true \
    "$@" \
    </dev/null
}

prepare_counsel_before_switch() {
  COUNSEL_SEED_AFTER_SWITCH=false
  if [ "${COUNSEL_ENABLED}" != "true" ]; then
    return 0
  fi

  # 새 워커도 Flyway를 실행하므로 워커를 교체하기 전에 일회성 검사부터 수행함
  echo "== Check counseling migrations before switch =="
  local check=0
  run_counsel_seed --counsel.seed-check-only=true || check=$?
  case "${check}" in
    0) ;;
    3)
      echo "Pending migrations found. Counseling index will be prepared after the backend switch."
      COUNSEL_SEED_AFTER_SWITCH=true
      return 0
      ;;
    *) return "${check}" ;;
  esac

  echo "== Prepare counseling worker and safety index =="
  "${COMPOSE[@]}" up -d ai-worker || return $?
  worker_health_check || return $?

  # 같은 프로필·모델·데이터 해시가 있으면 재색인하지 않음
  # 색인·워커 검증에 실패하면 기존 API와 nginx를 교체하지 않고 배포를 중단함
  run_counsel_seed
}

finish_counsel_after_switch() {
  if [ "${COUNSEL_ENABLED}" != "true" ] || [ "${COUNSEL_SEED_AFTER_SWITCH}" != "true" ]; then
    return 0
  fi

  echo "== Prepare counseling safety index after switch =="
  local seed=0
  run_counsel_seed || seed=$?
  if [ "${seed}" -ne 0 ]; then
    echo "ERROR: counseling index preparation failed after the switch (exit code ${seed})"
    echo "The new backend is running. Fix the migration/worker/index error and retry deployment."
    return "${seed}"
  fi
  COUNSEL_SEED_AFTER_SWITCH=false
}
