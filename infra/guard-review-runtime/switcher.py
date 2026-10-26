# 온디맨드 GPU 모드 전환기. controller.py 앞에서 BE Worker 요청을 받는 사설 프록시임
# 평소(chat)에는 채팅(8000)·임베딩(8001) vLLM이 NLB로 서비스함. 관리자 분석 사용권 요청이 오면
# 스폿이 채팅을 받는지 확인한 뒤 이 서버를 NLB에서 빼고 채팅·임베딩을 멈춰 14B 전용(admin)으로 바꿈
# controller가 14B를 멈추면(마지막 사용권 반납·만료) 채팅·임베딩을 다시 띄우고 NLB에 등록함
# 전환 중에는 controller와 같은 503 GPU_BUSY로 답해 Worker가 다시 시도하게 함
# 표준 라이브러리만 쓰고 AWS는 aws CLI로 호출함. 필요한 권한은 두 TG 등록·해제와 DescribeTargetHealth뿐임
# 이 모듈을 import해도 아무것도 시작하지 않음
from __future__ import annotations

import hmac
import http.client
import json
import os
import signal
import socket
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from socketserver import BaseRequestHandler, ThreadingTCPServer

CHAT, TO_ADMIN, ADMIN, TO_CHAT = "chat", "to-admin", "admin", "to-chat"
CONTROL_PORT = 18176
MODEL_PORT = 18175
ADMIN_CONTAINER = "ubot-admin-risk-qwen14"
# controller.py의 시작 기준과 같음
MIN_FREE_GPU_MIB = 21500
POLL_SECONDS = 10
# 스폿을 기다리는 동안이나 14B를 아직 안 띄운 admin 상태에서 요청이 이만큼 끊기면 그만둠
IDLE_SECONDS = 600
# 14B가 멈춘 뒤 이 시간 안에 새 사용권 요청이 오면 채팅으로 돌아가지 않고 이어서 씀
RELEASE_GRACE_SECONDS = 30
# NLB 연결 정리 시간(120초)보다 넉넉히 기다림
DRAIN_SECONDS = 300
CHAT_READY_SECONDS = 1200
RETRY_SECONDS = 60


def authorized(header, token):
    return hmac.compare_digest(header or "", "Bearer " + token)


class Aws:
    def __init__(self, region, runner=subprocess.run):
        self.region = region
        self.runner = runner

    def elbv2(self, *args):
        result = self.runner(["aws", "--region", self.region, "--output", "json", "elbv2", *args],
                             capture_output=True, text=True, timeout=60, check=False)
        if result.returncode:
            raise RuntimeError("aws elbv2 " + args[0] + " failed")
        return json.loads(result.stdout or "{}")

    def targets(self, group_arn):
        data = self.elbv2("describe-target-health", "--target-group-arn", group_arn)
        return [(item["Target"]["Id"], item["TargetHealth"]["State"])
                for item in data.get("TargetHealthDescriptions", [])]

    def register(self, group_arn, instance_id, port):
        self.elbv2("register-targets", "--target-group-arn", group_arn,
                   "--targets", f"Id={instance_id},Port={port}")

    def deregister(self, group_arn, instance_id, port):
        self.elbv2("deregister-targets", "--target-group-arn", group_arn,
                   "--targets", f"Id={instance_id},Port={port}")


class Host:
    def __init__(self, runner=subprocess.run):
        self.runner = runner

    def run(self, *args, timeout=120):
        result = self.runner(list(args), capture_output=True, text=True, timeout=timeout, check=False)
        if result.returncode:
            raise RuntimeError(args[0] + " failed")
        return result.stdout

    # ubot-vllm.service는 AMI에 있는 채팅·임베딩 compose 서비스임
    def stop_chat(self):
        self.run("systemctl", "stop", "ubot-vllm.service", timeout=180)

    def start_chat(self):
        self.run("systemctl", "start", "ubot-vllm.service", timeout=300)

    def chat_ready(self):
        for port in (8000, 8001):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=3) as response:
                    if response.status != 200:
                        return False
            except OSError:
                return False
        return True

    # 컨테이너가 없거나 조회가 실패하면 떠 있지 않은 것으로 봄
    def admin_running(self):
        try:
            return self.run("docker", "inspect", "-f", "{{.State.Running}}", ADMIN_CONTAINER).strip() == "true"
        except (RuntimeError, OSError, subprocess.TimeoutExpired):
            return False

    def gpu_free_mib(self):
        return int(self.run("nvidia-smi", "--id=0", "--query-gpu=memory.free",
                            "--format=csv,noheader,nounits").strip())


class ModeSwitcher:
    def __init__(self, aws, host, instance_id, groups, clock=time.monotonic, sleep=time.sleep,
                 start=lambda task: threading.Thread(target=task, daemon=True).start(),
                 log=lambda message: print(message, flush=True)):
        # groups: {Target Group ARN: 포트}
        self.aws = aws
        self.host = host
        self.instance_id = instance_id
        self.groups = groups
        self.clock = clock
        self.sleep = sleep
        self.start = start
        self.log = log
        self.lock = threading.RLock()
        self.mode = TO_CHAT
        self.last_request = clock()
        self.admin_since = None
        self.admin_seen = False
        self.stopped_since = None

    def set_mode(self, mode):
        with self.lock:
            self.mode = mode
        self.log("mode=" + mode)

    # 사용권 요청(PUT)마다 부름. True면 controller로 넘기고 False면 GPU_BUSY로 답함
    def lease_request(self):
        with self.lock:
            self.last_request = self.clock()
            if self.mode == ADMIN:
                return True
            if self.mode == CHAT:
                self.mode = TO_ADMIN
                self.log("mode=" + TO_ADMIN)
                self.start(self.run_to_admin)
            return False

    def model_open(self):
        with self.lock:
            return self.mode == ADMIN

    def others_healthy(self):
        for arn in self.groups:
            if not any(target != self.instance_id and state == "healthy"
                       for target, state in self.aws.targets(arn)):
                return False
        return True

    def registered(self):
        return any(target == self.instance_id for arn in self.groups for target, _ in self.aws.targets(arn))

    def run_to_admin(self):
        try:
            self.switch_to_admin()
        except Exception as error:
            self.log(f"switch to admin failed ({error}); returning to chat")
            self.set_mode(TO_CHAT)
            self.run_to_chat()

    def switch_to_admin(self):
        # 스폿이 두 TG 모두에서 healthy일 때만 이 서버를 뺌. 스폿이 회수 중이면 대체 스폿을 기다림
        while not self.others_healthy():
            if self.clock() - self.last_request > IDLE_SECONDS:
                self.log("no lease requests while waiting for a healthy Spot target")
                self.set_mode(CHAT)
                return
            self.sleep(POLL_SECONDS)
        for arn, port in self.groups.items():
            self.aws.deregister(arn, self.instance_id, port)
        deadline = self.clock() + DRAIN_SECONDS
        while self.registered() and self.clock() < deadline:
            self.sleep(POLL_SECONDS)
        self.host.stop_chat()
        deadline = self.clock() + 60
        while self.host.gpu_free_mib() < MIN_FREE_GPU_MIB and self.clock() < deadline:
            self.sleep(2)
        with self.lock:
            self.admin_since = self.clock()
            self.admin_seen = False
            self.stopped_since = None
        self.set_mode(ADMIN)

    # 채팅이 돌아와야 하므로 실패해도 계속 다시 시도함
    def run_to_chat(self):
        while True:
            try:
                self.switch_to_chat()
                return
            except Exception as error:
                self.log(f"switch to chat failed ({error}); retrying")
                self.sleep(RETRY_SECONDS)

    def switch_to_chat(self):
        self.host.start_chat()
        deadline = self.clock() + CHAT_READY_SECONDS
        while not self.host.chat_ready():
            if self.clock() > deadline:
                raise RuntimeError("chat and embedding did not become ready")
            self.sleep(POLL_SECONDS)
        for arn, port in self.groups.items():
            self.aws.register(arn, self.instance_id, port)
        with self.lock:
            self.admin_since = None
        self.set_mode(CHAT)

    # 감시 스레드가 POLL_SECONDS마다 부름. admin에서 14B가 끝났으면 채팅으로 돌아감
    def tick(self):
        with self.lock:
            if self.mode != ADMIN:
                return
        # docker 조회 동안 사용권 요청이 막히지 않게 잠금 밖에서 확인함
        running = self.host.admin_running()
        with self.lock:
            if self.mode != ADMIN:
                return
            now = self.clock()
            if running:
                self.admin_seen = True
                self.stopped_since = None
                return
            if self.stopped_since is None:
                self.stopped_since = now
            finished = self.admin_seen and now - self.stopped_since >= RELEASE_GRACE_SECONDS
            never_started = (not self.admin_seen and now - self.admin_since > IDLE_SECONDS
                             and now - self.last_request > IDLE_SECONDS)
            if not (finished or never_started) or now - self.last_request < RELEASE_GRACE_SECONDS:
                return
            self.mode = TO_CHAT
        self.log("mode=" + TO_CHAT)
        self.start(self.run_to_chat)

    # 시작할 때 14B가 떠 있으면 admin을 이어받고, 아니면 채팅을 띄우고 NLB에 등록함
    def reconcile(self):
        if self.host.admin_running():
            with self.lock:
                self.admin_since = self.clock()
                self.admin_seen = True
            self.set_mode(ADMIN)
            return
        self.set_mode(TO_CHAT)
        self.start(self.run_to_chat)


def control_handler(switcher, token, upstream=("127.0.0.1", CONTROL_PORT)):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Authorization 헤더와 사용권 ID를 남기지 않음

        def reply(self, status, body, content_type="application/json"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def dispatch(self):
            size = int(self.headers.get("Content-Length") or 0)
            if size > 1024:
                self.reply(400, b'{"error":"INVALID_REQUEST"}')
                return
            body = self.rfile.read(size) if size else None
            if self.command == "PUT":
                # 인증되지 않은 요청으로는 전환을 시작하지 않음
                if not authorized(self.headers.get("Authorization"), token):
                    self.reply(401, b'{"error":"UNAUTHORIZED"}')
                    return
                if not switcher.lease_request():
                    self.reply(503, b'{"error":"GPU_BUSY"}')
                    return
            headers = {name: self.headers[name] for name in ("Authorization", "Content-Type") if self.headers.get(name)}
            try:
                # controller의 모델 준비 제한(240초)을 기다릴 수 있게 둠
                connection = http.client.HTTPConnection(*upstream, timeout=300)
                connection.request(self.command, self.path, body=body, headers=headers)
                response = connection.getresponse()
                self.reply(response.status, response.read(), response.getheader("Content-Type", "application/json"))
                connection.close()
            except OSError:
                self.reply(503, b'{"error":"GPU_BUSY"}')

        do_PUT = dispatch
        do_POST = dispatch
        do_DELETE = dispatch
    return Handler


def model_handler(switcher, upstream=("127.0.0.1", MODEL_PORT)):
    class Handler(BaseRequestHandler):
        # admin일 때만 14B로 TCP를 그대로 넘기고, 아니면 연결을 바로 닫음
        def handle(self):
            if not switcher.model_open():
                return
            try:
                target = socket.create_connection(upstream, timeout=10)
            except OSError:
                return
            target.settimeout(None)

            def pipe(source, sink):
                try:
                    while chunk := source.recv(65536):
                        sink.sendall(chunk)
                except OSError:
                    pass
                finally:
                    for end in (source, sink):
                        try:
                            end.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass

            back = threading.Thread(target=pipe, args=(target, self.request), daemon=True)
            back.start()
            pipe(self.request, target)
            back.join()
            target.close()
    return Handler


def metadata(path, token):
    request = urllib.request.Request("http://169.254.169.254/latest/" + path,
                                     headers={"X-aws-ec2-metadata-token": token})
    with urllib.request.urlopen(request, timeout=3) as response:
        return response.read().decode()


def main():
    token = os.environ.get("GUARD_REVIEW_RUNTIME_TOKEN", "")
    if len(token) < 32:
        raise RuntimeError("A private runtime-control token of at least 32 characters is required")
    groups = {os.environ["SWITCHER_CHAT_TARGET_GROUP_ARN"]: 8000,
              os.environ["SWITCHER_EMBEDDING_TARGET_GROUP_ARN"]: 8001}
    request = urllib.request.Request("http://169.254.169.254/latest/api/token", method="PUT",
                                     headers={"X-aws-ec2-metadata-token-ttl-seconds": "300"})
    with urllib.request.urlopen(request, timeout=3) as response:
        imds_token = response.read().decode()
    instance_id = metadata("meta-data/instance-id", imds_token)
    # 공인 IP가 있는 서브넷이라 사설 IP에만 염. 보안 그룹은 Worker에서 오는 연결만 허용함
    private_ip = metadata("meta-data/local-ipv4", imds_token)

    switcher = ModeSwitcher(Aws(os.environ.get("AWS_REGION", "ap-northeast-2")), Host(), instance_id, groups)
    ThreadingHTTPServer.daemon_threads = True
    ThreadingTCPServer.daemon_threads = True
    ThreadingTCPServer.allow_reuse_address = True
    control = ThreadingHTTPServer((private_ip, CONTROL_PORT), control_handler(switcher, token))
    model = ThreadingTCPServer((private_ip, MODEL_PORT), model_handler(switcher))
    stopped = threading.Event()

    def watchdog():
        while not stopped.wait(POLL_SECONDS):
            try:
                switcher.tick()
            except Exception as error:
                print(f"mode check failed ({error})", flush=True)

    threading.Thread(target=model.serve_forever, name="model-proxy", daemon=True).start()
    threading.Thread(target=watchdog, name="mode-watchdog", daemon=True).start()
    switcher.reconcile()

    def terminate(*args):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, terminate)
    try:
        control.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        stopped.set()
        control.server_close()
        model.shutdown()
        model.server_close()


if __name__ == "__main__":
    main()
