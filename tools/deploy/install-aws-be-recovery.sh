#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ] || ! [[ "$1" =~ ^/[A-Za-z0-9._/-]+$ ]] || [[ "$1" == *..* ]] \
    || ! [[ "$2" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]]; then
  echo 'usage: install-aws-be-recovery.sh <deploy-root> <deploy-user>' >&2
  exit 2
fi
root="$1"
deploy_user="$2"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
id "$deploy_user" >/dev/null
command -v flock >/dev/null
command -v pgrep >/dev/null
sudo -n systemctl --version >/dev/null
mkdir -p "$root/.deploy"

# 현재 릴리스 삭제와 SSM 종료에 영향받지 않도록 고정 경로에 복사함
install -m 0755 "$script_dir/aws-be-worker-recovery.sh" "$root/.deploy/aws-be-worker-recovery.sh"
unit_dir="$(mktemp -d)"
trap 'rm -rf "$unit_dir"' EXIT
cat > "$unit_dir/ubot-be-worker-recovery.service" <<EOF
[Unit]
Description=Recover the UBot AWS AI worker
After=docker.service

[Service]
Type=oneshot
User=$deploy_user
ExecStart=/usr/bin/bash $root/.deploy/aws-be-worker-recovery.sh $root
TimeoutStartSec=90
EOF
cat > "$unit_dir/ubot-be-worker-recovery.timer" <<'EOF'
[Unit]
Description=Check the UBot AWS AI worker every 30 seconds

[Timer]
OnBootSec=30s
OnUnitInactiveSec=30s
AccuracySec=1s

[Install]
WantedBy=timers.target
EOF
sudo -n install -m 0644 "$unit_dir/ubot-be-worker-recovery.service" /etc/systemd/system/
sudo -n install -m 0644 "$unit_dir/ubot-be-worker-recovery.timer" /etc/systemd/system/
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now ubot-be-worker-recovery.timer
echo 'AWS BE worker recovery timer is enabled.'
