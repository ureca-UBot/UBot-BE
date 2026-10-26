#!/bin/bash
# BE 연결 확인을 마친 빌더에서 AMI를 만들기 직전에 관리자 권한으로 실행함
# 받아 둔 모델로 고정하고 인스턴스마다 다른 흔적(자격 증명, 키, 로그, cloud-init 기록)을 지움
set -euo pipefail
cd /opt/ubot-gpu

for port in 8000 8001; do
  curl -fsS "http://127.0.0.1:${port}/health" >/dev/null \
    || { echo "vLLM on port ${port} is not healthy; finish the checks before making the AMI." >&2; exit 1; }
done

# AMI로 부팅한 서버는 Hugging Face에 다시 묻지 않고 받아 둔 모델만 씀. 모델이 바뀌지 않고 부팅도 빨라짐
# 끝 줄바꿈이 없는 .env여도 마지막 줄에 붙지 않게 앞에 줄바꿈을 넣음
grep -q '^COMPOSE_FILE=' .env || printf '\nCOMPOSE_FILE=docker-compose.yml:compose.offline.yaml\n' >> .env

# 컨테이너는 지우고 이미지와 모델 볼륨(llm_hf-cache)만 남김. 부팅 때 ubot-vllm.service가 다시 만듦
systemctl stop ubot-vllm.service
docker compose -p llm rm -f vllm vllm-embedding
docker image prune -f
docker builder prune -af

apt-get clean
rm -rf /var/lib/apt/lists/*
rm -rf /root/.aws /root/.cache /root/.bash_history /home/ubuntu/.bash_history
# 빌더 키가 AMI에 남으면 그 AMI로 띄운 서버가 모두 같은 키를 받아들임
rm -f /home/ubuntu/.ssh/authorized_keys
journalctl --rotate
journalctl --vacuum-time=1s
rm -f /var/log/ubot-gpu-setup.log
cloud-init clean --logs

df -h /
du -sh /var/lib/docker/overlay2 /var/lib/docker/volumes/llm_hf-cache 2>/dev/null || true
echo 'Ready for create-image.'
