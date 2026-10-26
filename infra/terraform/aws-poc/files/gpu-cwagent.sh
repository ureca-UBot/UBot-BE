#!/bin/bash
# NVIDIA GPU 사용률을 1분마다 CloudWatch(CWAgent/nvidia_smi_utilization_gpu)에 보냄
# ubot-gpu-asg 목표 추적 정책에서 쓰도록 AutoScalingGroupName 차원으로 집계함
set -euo pipefail
exec > >(tee -a /var/log/ubot-cwagent.log) 2>&1

# 기존 AMI의 채팅 컨텍스트도 8192로 맞춤. 출력 가드가 예약하는 1024토큰과 입력이 함께 들어갈 공간 확보함
if [ -f /opt/ubot-gpu/.env ] && ! grep -qx 'LLM_MAX_MODEL_LEN=8192' /opt/ubot-gpu/.env; then
  sed -i 's/^LLM_MAX_MODEL_LEN=.*/LLM_MAX_MODEL_LEN=8192/' /opt/ubot-gpu/.env
  (
    cd /opt/ubot-gpu
    docker compose -p llm up -d --no-deps --no-build --pull never vllm
  )
fi

ctl=/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl
if [ ! -x "$ctl" ]; then
  deb="$(mktemp --suffix=.deb)"
  curl -fsSL --retry 3 -o "$deb" https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb
  dpkg -i -E "$deb"
  rm -f "$deb"
fi

config=/opt/aws/amazon-cloudwatch-agent/etc/ubot-gpu.json
cat > "$config" <<'EOF'
{
  "agent": { "metrics_collection_interval": 60, "run_as_user": "root" },
  "metrics": {
    "namespace": "CWAgent",
    "append_dimensions": {
      "AutoScalingGroupName": "${aws:AutoScalingGroupName}",
      "InstanceId": "${aws:InstanceId}"
    },
    "aggregation_dimensions": [["AutoScalingGroupName"]],
    "metrics_collected": {
      "nvidia_gpu": { "measurement": ["utilization_gpu", "memory_used", "memory_total"] }
    }
  }
}
EOF
"$ctl" -a fetch-config -m ec2 -s -c "file:$config"
"$ctl" -a status | grep -E '"status"|"configstatus"'
