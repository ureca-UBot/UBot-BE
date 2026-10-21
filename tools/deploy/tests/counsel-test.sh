#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/counsel.sh"

read_env() {
  case "$1" in
    COUNSEL_ENABLED) printf '%s' "${TEST_ENABLED:-}" ;;
    COUNSEL_EMBEDDING_WORKER_ENABLED) printf '%s' "${TEST_WORKER:-}" ;;
    COUNSEL_EMBEDDING_TRANSPORT) printf '%s' "${TEST_TRANSPORT:-}" ;;
  esac
}
fail() { echo "FAIL: $1"; exit 1; }
calls=()
compose() {
  calls+=("$*")
  if [ "$1" = up ]; then return "${START_RESULT:-0}"; fi
  if [[ "$*" == *--counsel.seed-check-only=true* ]]; then return "${CHECK_RESULT:-0}"; fi
  return "${SEED_RESULT:-0}"
}
worker_health_check() { calls+=(health); return "${HEALTH_RESULT:-0}"; }
COMPOSE=(compose)

validate_counsel_environment
[ "${COUNSEL_ENABLED}" = true ] || fail 'counsel must be enabled by default'
prepare_counsel_before_switch
[ "${#calls[@]}" -eq 4 ] || fail 'preflight, worker, health, seed should each run once'
[ "${calls[0]}" = 'run --rm -T --no-deps backend --counsel.seed-on-start=true --counsel.seed-only=true --counsel.seed-check-only=true' ] || fail 'migration check must precede worker replacement'
[ "${calls[1]}" = 'up -d ai-worker' ] || fail 'worker must start after preflight'
[ "${calls[2]}" = health ] || fail 'health must precede seeding'
[ "${calls[3]}" = 'run --rm -T --no-deps backend --counsel.seed-on-start=true --counsel.seed-only=true' ] || fail 'one-off seed must finish before API switch'
finish_counsel_after_switch
[ "${#calls[@]}" -eq 4 ] || fail 'completed seed must not run twice'

TEST_WORKER=false
if validate_counsel_environment; then fail 'disabled worker must stop deployment'; fi
TEST_ENABLED=false
validate_counsel_environment
calls=()
prepare_counsel_before_switch
COUNSEL_SEED_AFTER_SWITCH=true
finish_counsel_after_switch
[ "${#calls[@]}" -eq 0 ] || fail 'disabled counseling must not start worker or seed'
TEST_ENABLED=true TEST_WORKER=true TEST_TRANSPORT=http
if validate_counsel_environment; then fail 'deployment must use the worker transport'; fi
TEST_TRANSPORT=worker
validate_counsel_environment

for failure in CHECK_RESULT START_RESULT HEALTH_RESULT SEED_RESULT; do
  CHECK_RESULT=0 START_RESULT=0 HEALTH_RESULT=0 SEED_RESULT=0
  printf -v "${failure}" '%s' 7
  calls=()
  status=0
  prepare_counsel_before_switch || status=$?
  [ "${status}" -eq 7 ] || fail "${failure} must propagate"
  if [ "${failure}" = CHECK_RESULT ]; then
    [ "${#calls[@]}" -eq 1 ] || fail 'failed preflight must not replace worker'
  elif [ "${failure}" != SEED_RESULT ]; then
    for call in "${calls[@]}"; do
      [[ "${call}" != run* || "${call}" == *--counsel.seed-check-only=true* ]] || fail 'unready worker must not run seed'
    done
  fi
done

CHECK_RESULT=3 START_RESULT=0 HEALTH_RESULT=0 SEED_RESULT=0
calls=()
prepare_counsel_before_switch
[ "${COUNSEL_SEED_AFTER_SWITCH}" = true ] || fail 'pending migrations must defer seed'
[ "${#calls[@]}" -eq 1 ] || fail 'pending migrations must not replace worker or seed'
# 실제 deploy.sh에서는 API·worker 교체, 두 health check, FAQ 벡터 확인 다음에 실행함
calls+=(api-switch api-health worker-health faq-embeddings)
finish_counsel_after_switch
[ "${#calls[@]}" -eq 6 ] || fail 'deferred seed must run after the switch'
[ "${calls[5]}" = 'run --rm -T --no-deps backend --counsel.seed-on-start=true --counsel.seed-only=true' ] || fail 'deferred run must actually seed'
[ "${COUNSEL_SEED_AFTER_SWITCH}" = false ] || fail 'successful deferred seed must clear the flag'

for result in 1 3 7; do
  COUNSEL_SEED_AFTER_SWITCH=true SEED_RESULT="${result}"
  status=0
  finish_counsel_after_switch || status=$?
  [ "${status}" -eq "${result}" ] || fail 'post-switch failures must fail deployment, including remaining migrations'
  [ "${COUNSEL_SEED_AFTER_SWITCH}" = true ] || fail 'failed deferred seed must remain incomplete'
done

# 상담 색인이 실패해도 교체 후 FAQ 백필은 이미 끝나 있어야 함
deploy_script="${SCRIPT_DIR}/../deploy.sh"
faq_step="$(grep -n '^finish_embeddings_after_switch$' "${deploy_script}" | cut -d: -f1)"
counsel_step="$(grep -n '^finish_counsel_after_switch$' "${deploy_script}" | cut -d: -f1)"
[ -n "${faq_step}" ] && [ -n "${counsel_step}" ] && [ "${faq_step}" -lt "${counsel_step}" ] \
  || fail 'post-switch FAQ backfill must run before the deferred counsel seed'
echo 'PASS: counseling preflight, unchanged old worker on pending migrations, post-switch seed after FAQ backfill, and failure propagation'
