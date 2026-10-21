#!/usr/bin/env bash
set -euo pipefail
# The command lines are sent as text and expand on the BE EC2, not here.
# shellcheck disable=SC2016

# Prints AWS-RunShellScript parameters (JSON) for one BE deployment.
# On the BE EC2 the commands pull the exact image, extract its deployment bundle,
# and run aws-be-deploy.sh as the deploy user that owns the server .env.
if [ "$#" -ne 4 ]; then
  echo 'usage: aws-be-ssm-command.sh <ecr-repository-url> <sha256:image-digest> <deploy-path> <deploy-user>' >&2
  exit 2
fi

repository_url="$1"
image_digest="$2"
deploy_path="$3"
deploy_user="$4"
# Every value is pasted into shell text, so allow only characters that need no quoting.
if ! [[ "$repository_url" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/[a-z0-9._/-]+$ ]]; then
  echo 'Invalid Seoul-region ECR repository URL' >&2
  exit 2
fi
if ! [[ "$image_digest" =~ ^sha256:[a-f0-9]{64}$ ]]; then
  echo 'Invalid image digest' >&2
  exit 2
fi
if ! [[ "$deploy_path" =~ ^/[A-Za-z0-9._/-]+$ ]] || [[ "$deploy_path" == *..* ]]; then
  echo 'Invalid deploy path' >&2
  exit 2
fi
if ! [[ "$deploy_user" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]]; then
  echo 'Invalid deploy user' >&2
  exit 2
fi

# AWS-RunShellScript joins these lines into one sh script and runs it as root.
commands=(
  "id '$deploy_user' >/dev/null || { echo 'Deploy user $deploy_user does not exist' >&2; exit 1; }"
  "sudo -u '$deploy_user' bash <<'UBOT_DEPLOY'"
  'set -euo pipefail'
  "root='$deploy_path'"
  "repository='$repository_url'"
  "digest='$image_digest'"
  'image="$repository@$digest"'
  # A colon in a bind-mount source breaks Docker volume parsing, so drop the sha256: prefix.
  'release_dir="$root/releases/${digest#sha256:}"'
  'test -f "$root/.env" || { echo "BE .env is missing: $root/.env" >&2; exit 1; }'
  'mkdir -p "$release_dir"'
  'aws ecr get-login-password --region ap-northeast-2 | sudo docker login --username AWS --password-stdin "${repository%%/*}" >/dev/null'
  'sudo docker pull "$image"'
  'container_id="$(sudo docker create "$image")"'
  "trap 'sudo docker rm -f \"\$container_id\" >/dev/null 2>&1 || true' EXIT"
  'sudo docker cp "$container_id:/opt/ubot-deploy/." "$release_dir/"'
  'ln -sfn "$root/.env" "$release_dir/.env"'
  'UBOT_BE_STATE_DIR="$root/.deploy" bash "$release_dir/tools/deploy/aws-be-deploy.sh" "$repository" "$digest"'
  'UBOT_DEPLOY'
)

jq -n '{commands: $ARGS.positional, executionTimeout: ["1200"]}' --args "${commands[@]}"
