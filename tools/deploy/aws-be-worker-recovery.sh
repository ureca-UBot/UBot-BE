#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ] || ! [[ "$1" =~ ^/[A-Za-z0-9._/-]+$ ]] || [[ "$1" == *..* ]]; then
  echo 'usage: aws-be-worker-recovery.sh <deploy-root>' >&2
  exit 2
fi
root="$1"
state_dir="$root/.deploy"
test -d "$state_dir" || exit 0

# 배포 중에는 건드리지 않음. 배포 프로세스가 종료되면 잠금도 풀림
exec 9>"$state_dir/deploy.lock"
flock -n 9 || exit 0
test ! -f "$state_dir/worker-recovery.paused" || exit 0
# 잠금 도입 전 이미지로 배포하는 동안에도 기존 워커를 먼저 올리지 않음
if pgrep -f 'bash [^ ]*/tools/deploy/aws-be-deploy[.]sh' >/dev/null; then exit 0; fi

docker=(sudo -n docker)
container_id() {
  "${docker[@]}" ps -aq --filter label=com.docker.compose.project=ubot-api \
    --filter "label=com.docker.compose.service=$1" --filter label=com.docker.compose.oneoff=False
}
backend_id="$(container_id backend)"
if [ -z "$backend_id" ] || [[ "$backend_id" == *$'\n'* ]]; then exit 0; fi
if [ "$("${docker[@]}" inspect -f '{{.State.Running}}' "$backend_id")" != true ]; then exit 0; fi
image="$("${docker[@]}" inspect -f '{{.Config.Image}}' "$backend_id")"
if ! [[ "$image" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]]; then
  echo 'Worker recovery skipped: API is not using an immutable ECR image' >&2
  exit 1
fi

worker_id="$(container_id ai-worker)"
if [[ "$worker_id" == *$'\n'* ]]; then exit 1; fi
if [ -n "$worker_id" ]; then
  worker_image="$("${docker[@]}" inspect -f '{{.Config.Image}}' "$worker_id")"
  worker_status="$("${docker[@]}" inspect -f '{{.State.Status}}' "$worker_id")"
  # 기동 중이거나 정상 실행 중인 같은 버전의 워커는 다시 만들지 않음
  if [ "$worker_image" = "$image" ] && [[ "$worker_status" = running || "$worker_status" = restarting ]]; then
    exit 0
  fi
  # 운영자가 일시 정지한 컨테이너는 유지함
  if [ "$worker_status" = paused ]; then exit 0; fi
fi

# API가 Flyway를 마치고 정상 응답할 때만 같은 이미지의 워커를 올림
if ! curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health >/dev/null; then
  echo 'Worker recovery deferred: API is not healthy yet' >&2
  exit 0
fi
release_dir="$root/releases/${image##*@sha256:}"
for file in .env docker-compose.api.yml docker-compose.worker.yml docker-compose.monitoring.yml; do
  test -f "$release_dir/$file" || { echo "Worker recovery bundle missing: $file" >&2; exit 1; }
done
# 수동 교체가 겹쳤으면 다음 주기에 새 API를 다시 확인함
if [ "$(container_id backend)" != "$backend_id" ] \
    || [ "$("${docker[@]}" inspect -f '{{.State.Running}}' "$backend_id")" != true ] \
    || [ "$("${docker[@]}" inspect -f '{{.Config.Image}}' "$backend_id")" != "$image" ]; then exit 0; fi

echo "Recovering AI Worker with the running API image: $image"
cd "$release_dir"
"${docker[@]}" image inspect "$image" >/dev/null
sudo -n env "BACKEND_IMAGE=$image" docker compose -p ubot-api --env-file .env \
  -f docker-compose.api.yml -f docker-compose.worker.yml -f docker-compose.monitoring.yml \
  up -d --no-deps --pull never ai-worker
