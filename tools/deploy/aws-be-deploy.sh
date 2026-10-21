#!/usr/bin/env bash
set -euo pipefail

# This script is invoked on the existing BE EC2 by the dedicated SSM document.
# The document extracts these files from the exact image and links the server .env.
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
for command in curl sudo; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
sudo docker compose version >/dev/null

export BACKEND_IMAGE="${repository_url}@${image_digest}"
compose=(sudo env "BACKEND_IMAGE=$BACKEND_IMAGE" docker compose -p ubot-api --env-file .env -f docker-compose.api.yml -f docker-compose.worker.yml)
"${compose[@]}" config --quiet
sudo docker image inspect "$BACKEND_IMAGE" >/dev/null

state_dir="${UBOT_BE_STATE_DIR:-$repo_dir/.deploy}"
mkdir -p "$state_dir"

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
echo 'Run an authenticated question-to-answer smoke test before declaring the release complete.'
