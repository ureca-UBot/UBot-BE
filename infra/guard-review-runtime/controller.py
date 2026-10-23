"""EC2-local controller. Owns ONLY the pre-created administrator 14B container.

No model download, Docker image pull, backend restart, or changes to other containers.
Run only after explicit deployment; importing this module starts nothing.
"""
from __future__ import annotations

import hmac
import json
import os
import re
import signal
import subprocess
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODEL = "Qwen/Qwen3-14B-AWQ"
OWNER_LABEL = "ubot.guard-review-runtime"
OWNER_VALUE = "qwen3-14b-awq"
LEASE_SECONDS = 900


class GpuBusy(RuntimeError):
    pass


class DockerRuntime:
    def __init__(self, container, model_url, min_gpu_mib=21500, startup_seconds=240):
        if not re.fullmatch(r"ubot-admin-risk-[a-z0-9-]+", container):
            raise ValueError("A dedicated administrator container name is required")
        if not model_url.startswith("http://127.0.0.1:"):
            raise ValueError("The model control health address must be loopback-only")
        self.container = container
        self.model_url = model_url.rstrip("/")
        self.min_gpu_mib = min_gpu_mib
        self.startup_seconds = startup_seconds

    def command(self, *args, timeout=30):
        result = subprocess.run(list(args), capture_output=True, text=True, timeout=timeout, check=False)
        if result.returncode:
            raise RuntimeError("Dedicated model runtime command failed")
        return result.stdout

    def owned(self):
        info = json.loads(self.command("docker", "inspect", self.container))[0]
        config = info.get("Config", {})
        if config.get("Labels", {}).get(OWNER_LABEL) != OWNER_VALUE or MODEL not in config.get("Cmd", []):
            raise RuntimeError("Refusing to control a container without the dedicated model identity")
        return info

    def ensure_started(self):
        info = self.owned()
        if info["State"]["Running"]:
            self.wait_ready()
            return
        free = self.command("nvidia-smi", "--id=0", "--query-gpu=memory.free",
                            "--format=csv,noheader,nounits")
        memory = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        available_ram_mib = int(memory["MemAvailable"].split()[0]) // 1024
        if int(free.strip()) < self.min_gpu_mib or available_ram_mib < 10000:
            raise GpuBusy("GPU_BUSY")
        self.command("docker", "start", info["Id"], timeout=45)
        self.wait_ready()

    def wait_ready(self):
        deadline = time.monotonic() + self.startup_seconds
        while time.monotonic() < deadline:
            if not self.owned()["State"]["Running"]:
                raise RuntimeError("Dedicated model stopped while loading")
            try:
                with urllib.request.urlopen(self.model_url + "/v1/models", timeout=3) as response:
                    data = json.load(response)
                if any(item.get("id") == MODEL for item in data.get("data", [])):
                    return
            except (OSError, ValueError):
                pass
            time.sleep(2)
        raise RuntimeError("Dedicated model readiness deadline exceeded")

    def stop(self):
        # The name alone is never sufficient permission to stop a container.
        info = self.owned()
        if info["State"]["Running"]:
            self.command("docker", "stop", "--time", "20", info["Id"], timeout=45)
            if self.owned()["State"]["Running"]:
                raise RuntimeError("Dedicated model did not stop")


class LeaseManager:
    def __init__(self, runtime, clock=time.monotonic):
        self.runtime = runtime
        self.clock = clock
        self.leases = {}
        self.lock = threading.RLock()

    def acquire(self, lease_id, model):
        if model != MODEL:
            raise ValueError("Unexpected model")
        with self.lock:
            self._expire()
            self.leases[lease_id] = self.clock() + LEASE_SECONDS
            try:
                self.runtime.ensure_started()
                self.leases[lease_id] = self.clock() + LEASE_SECONDS
            except Exception:
                self.leases.pop(lease_id, None)
                if not self.leases:
                    self.runtime.stop()
                raise
        return {"ready": True, "model": MODEL}

    def heartbeat(self, lease_id):
        with self.lock:
            self._expire()
            if lease_id not in self.leases:
                raise KeyError("Lease expired")
            self.leases[lease_id] = self.clock() + LEASE_SECONDS

    def release(self, lease_id):
        with self.lock:
            self.leases.pop(lease_id, None)
            self._expire()
            if not self.leases:
                self.runtime.stop()

    def sweep(self):
        with self.lock:
            self._expire()
            if not self.leases:
                self.runtime.stop()

    def _expire(self):
        now = self.clock()
        for key in [key for key, until in self.leases.items() if until <= now]:
            del self.leases[key]

    def shutdown(self):
        with self.lock:
            self.leases.clear()
            self.runtime.stop()


def handler_for(manager, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Never log Authorization headers, lease IDs, or input contents.

        def send_json(self, status, body):
            encoded = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def dispatch(self):
            expected = "Bearer " + token
            if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
                self.send_json(401, {"error": "UNAUTHORIZED"})
                return
            try:
                parts = self.path.strip("/").split("/")
                if len(parts) not in (2, 3) or parts[0] != "leases":
                    raise ValueError("Invalid endpoint")
                lease_id = str(uuid.UUID(parts[1]))
                if self.command == "PUT" and len(parts) == 2:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 1024:
                        raise ValueError("Invalid body size")
                    payload = json.loads(self.rfile.read(size))
                    result = manager.acquire(lease_id, payload.get("model"))
                elif self.command == "POST" and parts[2:] == ["heartbeat"]:
                    manager.heartbeat(lease_id)
                    result = {"ok": True}
                elif self.command == "DELETE" and len(parts) == 2:
                    manager.release(lease_id)
                    result = {"ok": True}
                else:
                    raise ValueError("Invalid operation")
                self.send_json(200, result)
            except GpuBusy:
                self.send_json(503, {"error": "GPU_BUSY"})
            except KeyError:
                self.send_json(409, {"error": "LEASE_EXPIRED"})
            except (ValueError, TypeError):
                self.send_json(400, {"error": "INVALID_REQUEST"})
            except Exception:
                self.send_json(500, {"error": "RUNTIME_FAILED"})

        do_PUT = dispatch
        do_POST = dispatch
        do_DELETE = dispatch
    return Handler


def main():
    import fcntl  # EC2 Linux only; tests import the pure lease manager on Windows.

    token = os.environ.get("GUARD_REVIEW_RUNTIME_TOKEN", "")
    if len(token) < 32:
        raise RuntimeError("A private runtime-control token of at least 32 characters is required")
    container = os.environ.get("GUARD_REVIEW_MODEL_CONTAINER", "ubot-admin-risk-qwen14")
    runtime = DockerRuntime(container, "http://127.0.0.1:18175",
                            int(os.environ.get("GUARD_REVIEW_MIN_GPU_MIB", "21500")))
    # One controller per dedicated model container, even if a different HTTP port is requested.
    lockfile = (Path("/tmp") / (container + ".runtime.lock")).open("a")
    fcntl.flock(lockfile.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    manager = LeaseManager(runtime)
    server = ThreadingHTTPServer(("127.0.0.1", 18176), handler_for(manager, token))
    server.daemon_threads = True
    runtime.stop()  # Recover an orphan of this dedicated runtime only; never load on startup.
    stopped = threading.Event()

    def watchdog():
        while not stopped.wait(10):
            try:
                manager.sweep()
            except Exception:
                print("Administrator model cleanup requires retry", flush=True)

    threading.Thread(target=watchdog, name="admin-model-cleanup", daemon=True).start()

    def terminate(*args):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, terminate)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        stopped.set()
        server.server_close()
        manager.shutdown()
        lockfile.close()


if __name__ == "__main__":
    main()
