#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
work="$(mktemp -d)"
deploy_pid=''
cleanup() {
  if [ -n "$deploy_pid" ]; then kill -KILL -- "-$deploy_pid" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
export TEST_ROOT="$work" PATH="$work/bin:$PATH"
mkdir -p "$work/bin" "$work/root/.deploy" "$work/units"
image="123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/ubot-be@sha256:$(printf 'a%.0s' {1..64})"
old_image="${image%:*}:$(printf 'b%.0s' {1..64})"
release="$work/root/releases/${image##*@sha256:}"
mkdir -p "$release/tools/deploy"
touch "$release/.env" "$release/docker-compose.api.yml" "$release/docker-compose.worker.yml" "$release/docker-compose.monitoring.yml"
cp "$repo_dir/tools/deploy/aws-be-deploy.sh" "$release/tools/deploy/"
cp "$repo_dir/tools/deploy/aws-be-worker-recovery.sh" "$release/tools/deploy/"
cp "$repo_dir/tools/deploy/install-aws-be-recovery.sh" "$release/tools/deploy/"

cat > "$work/bin/sudo" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" = -n ]; then shift; fi
exec "$@"
EOF
cat > "$work/bin/curl" <<'EOF'
#!/usr/bin/env bash
test "$(cat "$TEST_ROOT/api-health")" = up
EOF
REAL_PGREP="$(command -v pgrep)"
export REAL_PGREP
cat > "$work/bin/pgrep" <<'EOF'
#!/usr/bin/env bash
if [ "${TEST_REAL_PGREP:-0}" = 1 ]; then exec "$REAL_PGREP" "$@"; fi
test -f "$TEST_ROOT/legacy-deploy"
EOF
cat > "$work/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$TEST_ROOT/systemctl-calls"
EOF
# 시스템 디렉터리 대신 시험용 디렉터리에만 유닛 설치함
real_install="$(command -v install)"
cat > "$work/bin/install" <<EOF
#!/usr/bin/env bash
if [ "\${!#}" = /etc/systemd/system/ ]; then
  set -- "\${@:1:\$#-1}" "\$TEST_ROOT/units/"
fi
exec "$real_install" "\$@"
EOF
cat > "$work/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  ps)
    if [[ "$*" = *com.docker.compose.service=backend* ]]; then
      if [ -f "$TEST_ROOT/backend-exists" ]; then
        # API 상태 확인 직후 수동 교체가 겹치는 경우 재현함
        if [ -f "$TEST_ROOT/change-api" ]; then
          count=$(cat "$TEST_ROOT/api-reads" 2>/dev/null || echo 0)
          echo "$((count+1))" > "$TEST_ROOT/api-reads"
          if [ "$count" -gt 0 ]; then echo backend-new; exit 0; fi
        fi
        echo backend
      fi
    elif [ -f "$TEST_ROOT/worker-exists" ]; then echo worker; fi
    ;;
  inspect)
    case "$3" in
      '{{.State.Running}}') cat "$TEST_ROOT/backend-running" ;;
      '{{.Config.Image}}') cat "$TEST_ROOT/${4}-image" ;;
      '{{.State.Status}}') cat "$TEST_ROOT/worker-status" ;;
      *) exit 2 ;;
    esac
    ;;
  image) exit 0 ;;
  compose)
    if [[ "$*" = *' ps -q nginx' ]]; then echo nginx; exit 0; fi
    if [[ "$*" = *' ps -q backend' ]]; then echo backend; exit 0; fi
    if [[ "$*" = *' stop ai-worker' ]]; then
      echo exited > "$TEST_ROOT/worker-status"
      touch "$TEST_ROOT/worker-stopped"
    elif [[ "$*" = *' up -d --no-deps --force-recreate backend' ]]; then
      echo "$BACKEND_IMAGE" > "$TEST_ROOT/backend-image"
      touch "$TEST_ROOT/deploy-blocked"
      while [ ! -f "$TEST_ROOT/release-deploy" ]; do sleep 0.05; done
    elif [[ "$*" = *' up -d --no-deps --pull never ai-worker' ]]; then
      printf '%s\n' "$BACKEND_IMAGE" >> "$TEST_ROOT/recovery-images"
      if [ -f "$TEST_ROOT/fail-start" ]; then exit 7; fi
      echo "$BACKEND_IMAGE" > "$TEST_ROOT/worker-image"
      echo running > "$TEST_ROOT/worker-status"
      touch "$TEST_ROOT/worker-exists"
    fi
    ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$work/bin/"*

fail() { echo "FAIL: $*" >&2; exit 1; }
reset() {
  rm -f "$work/recovery-images" "$work/fail-start" "$work/change-api" "$work/api-reads" \
    "$work/legacy-deploy" "$work/root/.deploy/worker-recovery.paused"
  touch "$work/backend-exists" "$work/worker-exists"
  echo true > "$work/backend-running"
  echo up > "$work/api-health"
  echo "$image" > "$work/backend-image"
  echo "$image" > "$work/worker-image"
  echo running > "$work/worker-status"
}
recover() { bash "$repo_dir/tools/deploy/aws-be-worker-recovery.sh" "$work/root"; }
no_restart() { test ! -f "$work/recovery-images" || fail "$1"; }
restarted() {
  test "$(cat "$work/recovery-images")" = "$image" || fail "$1"
  test "$(cat "$work/worker-status")" = running || fail 'worker must be running'
}

reset
recover
no_restart 'healthy worker must stay untouched'
echo exited > "$work/worker-status"
recover
restarted 'stopped worker must restart with the API image'
recover
test "$(wc -l < "$work/recovery-images")" -eq 1 || fail 'recovery must be idempotent'

reset
rm "$work/worker-exists"
recover
restarted 'missing worker must be created'
reset
echo "$old_image" > "$work/worker-image"
recover
restarted 'old worker must use the current API image after a partial deployment'

for status in restarting paused; do
  reset
  echo "$status" > "$work/worker-status"
  recover
  no_restart "worker in $status must be left alone"
done
reset
echo exited > "$work/worker-status"
echo down > "$work/api-health"
recover
no_restart 'unhealthy API must block worker startup during migration failure'
echo false > "$work/backend-running"
recover
no_restart 'stopped API must not bring the service back'
rm "$work/backend-exists"
recover
no_restart 'absent API must not create a deployment'

reset
echo exited > "$work/worker-status"
touch "$work/root/.deploy/worker-recovery.paused"
recover
no_restart 'maintenance pause must suppress recovery'
reset
echo exited > "$work/worker-status"
touch "$work/legacy-deploy"
recover
no_restart 'legacy deployment must not race with recovery'
reset
echo exited > "$work/worker-status"
touch "$work/change-api"
recover
no_restart 'API replacement during health check must defer recovery'

reset
echo exited > "$work/worker-status"
echo 'ubot-be:latest' > "$work/backend-image"
if recover; then fail 'mutable API image must be refused'; fi
no_restart 'unknown image must not cause rollback'
reset
echo exited > "$work/worker-status"
rm "$release/docker-compose.worker.yml"
if recover; then fail 'missing release bundle must fail closed'; fi
no_restart 'missing release bundle must not recreate any service'
touch "$release/docker-compose.worker.yml"

reset
echo exited > "$work/worker-status"
touch "$work/fail-start"
if recover; then fail 'failed worker startup must propagate'; fi
rm "$work/fail-start" "$work/recovery-images"
recover
restarted 'next timer tick must retry a failed startup'

# 잠금이 없는 구버전 배포도 실제 프로세스 명령행으로 감지함
reset
echo exited > "$work/worker-status"
mkdir -p "$work/legacy/tools/deploy"
printf '#!/usr/bin/env bash\ntouch "$TEST_ROOT/legacy-ready"\nwhile :; do sleep 1; done\n' > "$work/legacy/tools/deploy/aws-be-deploy.sh"
setsid bash "$work/legacy/tools/deploy/aws-be-deploy.sh" &
deploy_pid=$!
for _ in $(seq 1 100); do
  test ! -f "$work/legacy-ready" || break
  sleep 0.05
done
test -f "$work/legacy-ready" || fail 'legacy deployment process must start'
TEST_REAL_PGREP=1 recover
no_restart 'real legacy deployment process must suppress recovery'
kill -KILL -- "-$deploy_pid"
wait "$deploy_pid" 2>/dev/null || true
deploy_pid=''
TEST_REAL_PGREP=1 recover
restarted 'legacy deployment exit must allow recovery'

# 실제 배포 스크립트를 워커 정지 다음 단계에서 멈춘 뒤 프로세스 그룹 전체를 죽임
reset
UBOT_BE_STATE_DIR="$work/root/.deploy" setsid bash "$release/tools/deploy/aws-be-deploy.sh" \
  "${image%@*}" "${image##*@}" > "$work/deploy.log" 2>&1 &
deploy_pid=$!
for _ in $(seq 1 100); do
  test ! -f "$work/deploy-blocked" || break
  sleep 0.05
done
test -f "$work/deploy-blocked" || { cat "$work/deploy.log"; fail 'deployment must reach the interrupted stage'; }
test -f "$work/worker-stopped" || fail 'deployment must stop the worker before replacing API'
test -f "$work/units/ubot-be-worker-recovery.timer" || fail 'timer must be installed before stopping the worker'
recover
no_restart 'active deployment lock must exclude recovery'
kill -KILL -- "-$deploy_pid"
wait "$deploy_pid" 2>/dev/null || true
deploy_pid=''
recover
restarted 'SIGKILL of SSM/deployment must release the lock and allow external recovery'

grep -q '^OnUnitInactiveSec=30s$' "$work/units/ubot-be-worker-recovery.timer" || fail 'timer must repeat'
grep -q '^TimeoutStartSec=90$' "$work/units/ubot-be-worker-recovery.service" || fail 'recovery attempt must be bounded'
grep -q 'enable --now ubot-be-worker-recovery.timer' "$work/systemctl-calls" || fail 'timer must be enabled across reboot'
echo 'PASS: AWS worker recovery, API image consistency, migration/maintenance/deploy exclusions, retry, and deployment SIGKILL'
