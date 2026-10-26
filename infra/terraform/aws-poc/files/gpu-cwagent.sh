#!/bin/bash
# NVIDIA GPU 사용률을 1분마다 CloudWatch(CWAgent/nvidia_smi_utilization_gpu)에 보냄
# ubot-gpu-asg 목표 추적 정책에서 쓰도록 AutoScalingGroupName 차원으로 집계함
set -euo pipefail
exec > >(tee -a /var/log/ubot-cwagent.log) 2>&1

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
