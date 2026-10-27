#!/bin/bash
# GPU AMI 빌더의 첫 부팅에서 cloud-init이 실행함. vLLM 채팅·임베딩에 필요한 것만 설치하고 재부팅함
# 재부팅하면 드라이버가 올라오고 ubot-vllm.service가 vLLM을 띄움. 모델은 이때 vLLM이 필요한 파일만 받음
set -euo pipefail
exec > >(tee -a /var/log/ubot-gpu-setup.log) 2>&1
export DEBIAN_FRONTEND=noninteractive

nvidia_driver=580-server

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg ubuntu-drivers-common

# 데이터센터 GPU용 서버 브랜치 드라이버를 사전 빌드 커널 모듈로 설치함. CUDA는 vLLM 이미지 안에 있음
ubuntu-drivers install --gpgpu "nvidia:${nvidia_driver}"
apt-get install -y --no-install-recommends "nvidia-utils-${nvidia_driver}"

curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
  | gpg --dearmor --yes -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
  | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
  > /etc/apt/sources.list.d/nvidia-container-toolkit.list
apt-get update
apt-get install -y --no-install-recommends docker.io docker-compose-v2 nvidia-container-toolkit

# containerd 이미지 저장소는 압축 레이어를 함께 남겨 vLLM 이미지가 압축 크기(약 9GB)만큼 디스크를 더 씀
install -d /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "features": { "containerd-snapshotter": false },
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
nvidia-ctk runtime configure --runtime=docker
systemctl restart docker

# ASG 사용자 데이터(gpu-cwagent.sh)는 에이전트가 있으면 설정만 하므로 부팅 때 내려받지 않음
deb="$(mktemp --suffix=.deb)"
curl -fsSL --retry 3 -o "$deb" https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb
dpkg -i -E "$deb"
rm -f "$deb"

cd /opt/ubot-gpu
docker compose -p llm pull vllm vllm-embedding
systemctl daemon-reload
systemctl enable ubot-vllm.service

apt-get clean
df -h /
docker system df
echo 'Setup finished; rebooting to load the NVIDIA driver.'
systemctl reboot
