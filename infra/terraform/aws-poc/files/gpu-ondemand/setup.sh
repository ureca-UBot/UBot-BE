#!/bin/bash
# 스폿 AMI로 띄운 온디맨드 GPU의 첫 부팅에서 cloud-init이 실행함
# 채팅·임베딩(ubot-vllm.service)은 AMI에 이미 있음. 여기서는 #282 관리자 분석 구성만 더함
# 끝나면 토큰 파일을 손으로 넣고 제어기와 전환기를 시작함(전환기가 시작해야 NLB에 등록됨)
set -euo pipefail
exec > >(tee -a /var/log/ubot-ondemand-setup.log) 2>&1
export DEBIAN_FRONTEND=noninteractive

model=Qwen/Qwen3-14B-AWQ
model_revision=31c69efc29464b6bb0aee1398b5a7b50a99340c3
model_dir=/opt/ubot-admin-risk/model

# 전환기가 NLB 등록·해제에 aws CLI를 씀
if ! command -v aws >/dev/null; then
  apt-get update
  apt-get install -y --no-install-recommends unzip
  workdir="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$workdir/awscli.zip" https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip
  unzip -q "$workdir/awscli.zip" -d "$workdir"
  "$workdir/aws/install"
  rm -rf "$workdir"
  apt-get clean
fi

# 제어기는 모델을 내려받지 않으므로 고정 리비전을 미리 둠. 내려받기는 AMI의 vLLM 이미지를 그대로 씀
image="$(grep '^VLLM_IMAGE=' /opt/ubot-gpu/.env | cut -d= -f2)"
if [ ! -f "$model_dir/config.json" ]; then
  install -d "$model_dir"
  docker run --rm --entrypoint python3 -v "$model_dir:/model" "$image" -c \
    "from huggingface_hub import snapshot_download; snapshot_download('${model}', revision='${model_revision}', local_dir='/model')"
fi
# PR #282 문서의 확인 항목(quant_method=awq, bits=4)
python3 - "$model_dir/config.json" <<'EOF'
import json, sys
quant = json.load(open(sys.argv[1])).get("quantization_config", {})
if quant.get("quant_method") != "awq" or quant.get("bits") != 4:
    sys.exit("Unexpected checkpoint quantization: " + json.dumps(quant))
EOF

cd /opt/ubot-admin-risk
cat > compose.env <<EOF
GUARD_REVIEW_RUNTIME_IMAGE=$(docker image inspect --format '{{.Id}}' "$image")
GUARD_REVIEW_AWQ_CHECKPOINT=${model_dir}
EOF
# up이 아니라 create만 함. 띄우고 멈추는 것은 제어기가 사용권에 따라 함
docker compose --project-name ubot-admin-risk -f compose.yaml --env-file compose.env create --no-recreate admin-risk-qwen14

systemctl daemon-reload
systemctl enable ubot-admin-risk-controller.service ubot-gpu-mode-switcher.service
df -h /
echo 'Setup finished. Put /etc/ubot-admin-risk/runtime.env, then start the controller and the switcher.'
