#!/usr/bin/env bash
set -euo pipefail

# This script runs on the existing BE EC2 through SSM AWS-RunShellScript (aws-be-ssm-command.sh).
# Those commands extract these files from the exact image and link the server .env.
if [ "$#" -ne 2 ]; then
  echo 'usage: aws-be-deploy.sh <ecr-repository-url> <sha256:image-digest>' >&2
  exit 2
fi

repository_url="$1"
image_digest="$2"
if ! [[ "$repository_url" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/[a-z0-9._/-]+$ ]]; then
  echo 'Invalid Seoul-region ECR repository URL' >&2
  exit 2
fi
if ! [[ "$image_digest" =~ ^sha256:[a-f0-9]{64}$ ]]; then
  echo 'Invalid image digest' >&2
  exit 2
fi

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_dir"
if [ ! -f .env ]; then
  echo 'BE .env is missing' >&2
  exit 1
fi
for command in curl sudo flock; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
sudo docker compose version >/dev/null

# Same counseling checks as tools/deploy/lib/counsel.sh, before any container changes.
# A missing COUNSEL_ENABLED means true, as in the application.
env_value() { sed -n "s/^$1=//p" .env | tail -n 1; }
counsel_enabled="$(env_value COUNSEL_ENABLED)"
counsel_enabled="${counsel_enabled:-true}"
case "$counsel_enabled" in
  false) ;;
  true)
    if [ "$(env_value COUNSEL_EMBEDDING_WORKER_ENABLED)" = false ]; then
      echo 'Counseling requires COUNSEL_EMBEDDING_WORKER_ENABLED=true' >&2
      exit 1
    fi
    transport="$(env_value COUNSEL_EMBEDDING_TRANSPORT)"
    if [ "${transport:-worker}" != worker ]; then
      echo 'Deployed counseling requires COUNSEL_EMBEDDING_TRANSPORT=worker' >&2
      exit 1
    fi
    ;;
  *)
    echo 'COUNSEL_ENABLED must be true or false' >&2
    exit 1
    ;;
esac

export BACKEND_IMAGE="${repository_url}@${image_digest}"
compose=(sudo env "BACKEND_IMAGE=$BACKEND_IMAGE" docker compose -p ubot-api --env-file .env
  -f docker-compose.api.yml -f docker-compose.worker.yml -f docker-compose.monitoring.yml)
"${compose[@]}" config --quiet
sudo docker image inspect "$BACKEND_IMAGE" >/dev/null

state_dir="${UBOT_BE_STATE_DIR:-$repo_dir/.deploy}"
mkdir -p "$state_dir"
# 배포와 외부 복구가 동시에 컨테이너를 바꾸지 못하게 잠금 유지함
exec 9>"$state_dir/deploy.lock"
flock -w 120 9 || { echo 'Another BE deployment or recovery is running' >&2; exit 1; }

# The first transition must provision the BE Nginx Compose service before enabling CD.
nginx_id="$("${compose[@]}" ps -q nginx)"
if [ -z "$nginx_id" ] || [ "$(sudo docker inspect -f '{{.State.Running}}' "$nginx_id")" != true ]; then
  echo 'BE Nginx is not running; complete one-time provisioning first' >&2
  exit 1
fi

backend_id="$("${compose[@]}" ps -q backend)"
if [ -n "$backend_id" ]; then
  sudo docker inspect -f '{{.Config.Image}}' "$backend_id" > "$state_dir/previous-image"
fi

# SSM과 배포 셸이 죽어도 남는 복구 타이머를 워커 정지 전에 설치함
bash "$repo_dir/tools/deploy/install-aws-be-recovery.sh" "$(dirname "$state_dir")" "$(id -un)"

# Stop the old Worker before Flyway can apply a new schema in the API process.
# A migration may make an image-only rollback unsafe, so failure needs review.
"${compose[@]}" stop ai-worker
"${compose[@]}" up -d --no-deps --force-recreate backend

api_healthy=false
for _ in $(seq 1 36); do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null; then
    api_healthy=true
    break
  fi
  sleep 5
done
if [ "$api_healthy" != true ]; then
  echo 'API health check failed' >&2
  exit 1
fi

# Check the template while the API is healthy, then replace the proxy.
"${compose[@]}" run --rm --no-deps nginx nginx -t
"${compose[@]}" up -d --no-deps --force-recreate nginx
nginx_id="$("${compose[@]}" ps -q nginx)"
sudo docker exec "$nginx_id" nginx -t
sudo docker exec "$nginx_id" nginx -s reload
proxy_healthy=false
for _ in $(seq 1 12); do
  if curl -fsS --max-time 3 http://127.0.0.1/api/notices >/dev/null; then
    proxy_healthy=true
    break
  fi
  sleep 2
done
if [ "$proxy_healthy" != true ]; then
  echo 'BE Nginx /api/notices smoke test failed' >&2
  exit 1
fi

worker_start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"${compose[@]}" up -d --no-deps --force-recreate ai-worker
worker_healthy=false
for _ in $(seq 1 36); do
  worker_id="$("${compose[@]}" ps -q ai-worker)"
  if [ -n "$worker_id" ] \
      && [ "$(sudo docker inspect -f '{{.State.Running}}' "$worker_id")" = true ] \
      && sudo docker logs --since "$worker_start" "$worker_id" 2>&1 \
           | grep 'Started UbotBeApplication' >/dev/null; then
    worker_healthy=true
    break
  fi
  sleep 5
done
if [ "$worker_healthy" != true ]; then
  echo 'AI Worker did not complete startup' >&2
  exit 1
fi

printf '%s\n' "$BACKEND_IMAGE" > "$state_dir/current-image"
echo "BE image running: $BACKEND_IMAGE"
echo 'API /actuator/health, /api/notices, and Worker startup passed.'

# Counseling messages fail with PROFILE_NOT_READY until the safety index exists. The new API has
# applied the migrations and the Worker embeds the sentences, so index now in a one-off backend.
# The same profile, model and data hash is not indexed again.
if [ "$counsel_enabled" = true ]; then
  echo 'Preparing the counseling safety index'
  seed_status=0
  "${compose[@]}" run --rm -T --no-deps backend \
    --counsel.seed-on-start=true --counsel.seed-only=true </dev/null || seed_status=$?
  if [ "$seed_status" -ne 0 ]; then
    echo "The new BE image is running, but counseling index preparation failed (exit code $seed_status)" >&2
    exit "$seed_status"
  fi
  echo 'Counseling safety index is ready.'
fi

# Monitoring does not serve users, so it follows the app switch. Prometheus is recreated so a
# changed scrape config in this release takes effect; its data volume is kept.
"${compose[@]}" up -d --no-deps postgres-exporter redis-exporter cadvisor
"${compose[@]}" up -d --no-deps --force-recreate prometheus
prometheus_url="http://$("${compose[@]}" port prometheus 9090)"
prom_value() {
  curl -fsS --max-time 3 -G --data-urlencode "query=$1" "$prometheus_url/api/v1/query" 2>/dev/null \
    | sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p'
}
# All five scrape jobs up, and the exporters can reach PostgreSQL and Redis on the State EC2.
monitoring_ready=false
for _ in $(seq 1 24); do
  if [ "$(prom_value 'count(up == 1)')" = 5 ] \
      && [ "$(prom_value 'min(pg_up)')" = 1 ] && [ "$(prom_value 'min(redis_up)')" = 1 ]; then
    monitoring_ready=true
    break
  fi
  sleep 5
done
if [ "$monitoring_ready" != true ]; then
  echo "The new BE image is running, but monitoring is not ready at $prometheus_url" >&2
  curl -fsS --max-time 3 "$prometheus_url/api/v1/targets?state=active" >&2 || true
  exit 1
fi
echo "Prometheus scrapes API, Worker, PostgreSQL, Redis and containers at $prometheus_url"
echo 'Run an authenticated question-to-answer smoke test before declaring the release complete.'
