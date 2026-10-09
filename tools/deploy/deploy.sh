#!/usr/bin/env bash
set -euo pipefail

# 서버에서 실행되는 배포 스크립트입니다. 단계별 함수는 lib/ 아래 파일에 있습니다.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DEPLOY_DIR="${HOME}/ubot"
ARTIFACT_DIR="${HOME}/ubot-deploy"
ENV_FILE="${DEPLOY_DIR}/.env"

IMAGE_NAME="${IMAGE_NAME:?IMAGE_NAME is required}"
GITHUB_SHA="${GITHUB_SHA:?GITHUB_SHA is required}"
BACKEND_IMAGE="${IMAGE_NAME}:${GITHUB_SHA}"

APP_NETWORK="ubot_default"

export BACKEND_IMAGE

# 서버에는 예전 배포의 파일이 남아 있을 수 있으므로, 불러올 파일을 이름으로 하나씩 적는다.
source "${SCRIPT_DIR}/lib/env.sh"
source "${SCRIPT_DIR}/lib/compose.sh"
source "${SCRIPT_DIR}/lib/ai-runtime.sh"
source "${SCRIPT_DIR}/lib/embeddings.sh"
source "${SCRIPT_DIR}/lib/backend.sh"

cd "${DEPLOY_DIR}"


validate_environment


echo "== Load backend Docker image =="

gunzip -c \
  "${ARTIFACT_DIR}/ubot-be.tar.gz" \
  | docker load


build_compose_command

stop_unused_managed_runtimes

start_common_infrastructure

start_managed_ai

validate_ai_from_app_network

prepare_embeddings_before_switch

start_application

health_check

cleanup_old_backend_images

finish_embeddings_after_switch

echo "== Container status =="

"${COMPOSE[@]}" \
  ps -a


echo "== Deployment completed =="

echo "BACKEND_IMAGE=${BACKEND_IMAGE}"
echo "CHAT_ENGINE=${CHAT_ENGINE}"
echo "EMBEDDING_ENGINE=${EMBEDDING_ENGINE}"