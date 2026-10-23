import unittest
from unittest.mock import Mock

from controller import DockerRuntime, GpuBusy, LEASE_SECONDS, LeaseManager, MODEL


class FakeRuntime:
    def __init__(self):
        self.running = False
        self.starts = 0
        self.stops = 0
        self.fail = False

    def ensure_started(self):
        if not self.running:
            self.running = True
            self.starts += 1
        if self.fail:
            raise RuntimeError("mock readiness failure")

    def stop(self):
        if self.running:
            self.running = False
            self.stops += 1


class LeaseManagerTest(unittest.TestCase):
    def test_idle_does_not_load_and_last_job_releases_model(self):
        runtime = FakeRuntime()
        manager = LeaseManager(runtime)
        manager.sweep()
        self.assertEqual(runtime.starts, 0)
        manager.acquire("a", MODEL)
        manager.acquire("b", MODEL)
        self.assertEqual(runtime.starts, 1)
        manager.release("a")
        self.assertTrue(runtime.running)
        manager.release("b")
        self.assertFalse(runtime.running)
        self.assertEqual(runtime.stops, 1)

    def test_crashed_worker_expires_but_renewed_job_keeps_its_model(self):
        now = [0]
        runtime = FakeRuntime()
        manager = LeaseManager(runtime, lambda: now[0])
        manager.acquire("crashed", MODEL)
        manager.acquire("live", MODEL)
        now[0] = LEASE_SECONDS - 1
        manager.heartbeat("live")
        now[0] = LEASE_SECONDS + 1
        manager.sweep()
        self.assertTrue(runtime.running)
        self.assertNotIn("crashed", manager.leases)
        now[0] = 2 * LEASE_SECONDS
        manager.sweep()
        self.assertFalse(runtime.running)

    def test_failed_start_releases_partial_model_and_usage(self):
        runtime = FakeRuntime()
        runtime.fail = True
        manager = LeaseManager(runtime)
        with self.assertRaises(RuntimeError):
            manager.acquire("a", MODEL)
        self.assertFalse(runtime.running)
        self.assertFalse(manager.leases)

    def test_busy_gpu_does_not_stop_existing_user_services(self):
        runtime = FakeRuntime()
        runtime.ensure_started = Mock(side_effect=GpuBusy("GPU_BUSY"))
        manager = LeaseManager(runtime)
        with self.assertRaises(GpuBusy):
            manager.acquire("a", MODEL)
        self.assertFalse(manager.leases)
        self.assertEqual(runtime.starts, 0)
        self.assertEqual(runtime.stops, 0)

    def test_foreign_container_label_cannot_be_stopped(self):
        runtime = DockerRuntime("ubot-admin-risk-qwen14", "http://127.0.0.1:18175")
        runtime.command = Mock(return_value='[{"Config":{"Labels":{},"Cmd":[]},"State":{"Running":true}}]')
        with self.assertRaises(RuntimeError):
            runtime.stop()
        self.assertEqual(runtime.command.call_count, 1)
        # Even a name reuse cannot redirect the stop operation to another container.
        labels = {"ubot.guard-review-runtime": "qwen3-14b-awq"}
        import json
        def info(running):
            return json.dumps([{"Id": "verified-container-id", "Config": {"Labels": labels, "Cmd": [MODEL]},
                                "State": {"Running": running}}])
        runtime.command = Mock(side_effect=[info(True), "", info(False)])
        runtime.stop()
        self.assertEqual(runtime.command.call_args_list[1].args,
                         ("docker", "stop", "--time", "20", "verified-container-id"))


if __name__ == "__main__":
    unittest.main()
