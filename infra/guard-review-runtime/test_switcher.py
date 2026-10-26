import unittest

from switcher import (ADMIN, CHAT, IDLE_SECONDS, MIN_FREE_GPU_MIB, RELEASE_GRACE_SECONDS, TO_ADMIN, TO_CHAT,
                      ModeSwitcher, authorized)

SELF = "i-ondemand"
SPOT = "i-spot"
CHAT_TG, EMBEDDING_TG = "arn:chat", "arn:embedding"


class FakeAws:
    def __init__(self):
        self.groups = {CHAT_TG: {SELF: "healthy"}, EMBEDDING_TG: {SELF: "healthy"}}
        self.calls = []

    def targets(self, arn):
        return list(self.groups[arn].items())

    def register(self, arn, instance_id, port):
        self.calls.append(("register", arn, instance_id, port))
        self.groups[arn][instance_id] = "healthy"

    def deregister(self, arn, instance_id, port):
        self.calls.append(("deregister", arn, instance_id, port))
        self.groups[arn].pop(instance_id, None)

    def spot(self, state):
        for group in self.groups.values():
            group[SPOT] = state


class FakeHost:
    def __init__(self):
        self.chat = True
        self.admin = False
        self.calls = []

    def stop_chat(self):
        self.calls.append("stop_chat")
        self.chat = False

    def start_chat(self):
        self.calls.append("start_chat")
        self.chat = True

    def chat_ready(self):
        return self.chat

    def admin_running(self):
        return self.admin

    def gpu_free_mib(self):
        return 1000 if self.chat else MIN_FREE_GPU_MIB + 1000


class Harness:
    # 시계를 손으로 움직이고, 백그라운드 작업은 run()에서 바로 실행함
    def __init__(self):
        self.now = 0.0
        self.tasks = []
        self.aws = FakeAws()
        self.host = FakeHost()
        self.switcher = ModeSwitcher(self.aws, self.host, SELF, {CHAT_TG: 8000, EMBEDDING_TG: 8001},
                                     clock=lambda: self.now, sleep=self.advance,
                                     start=self.tasks.append, log=lambda message: None)
        self.switcher.mode = CHAT

    def advance(self, seconds):
        self.now += seconds

    def run(self):
        while self.tasks:
            self.tasks.pop(0)()


class ModeSwitcherTest(unittest.TestCase):
    def test_first_lease_is_busy_and_switches_once_spot_serves_chat(self):
        h = Harness()
        h.aws.spot("healthy")
        self.assertFalse(h.switcher.lease_request())
        self.assertEqual(h.switcher.mode, TO_ADMIN)
        self.assertFalse(h.switcher.lease_request())
        self.assertEqual(len(h.tasks), 1)
        h.run()
        self.assertEqual(h.switcher.mode, ADMIN)
        self.assertNotIn(SELF, h.aws.groups[CHAT_TG])
        self.assertNotIn(SELF, h.aws.groups[EMBEDDING_TG])
        self.assertEqual(h.host.calls, ["stop_chat"])
        self.assertTrue(h.switcher.lease_request())
        self.assertTrue(h.switcher.model_open())

    def test_waits_for_replacement_spot_without_leaving_the_load_balancer(self):
        h = Harness()
        h.aws.spot("unhealthy")
        h.switcher.lease_request()
        original_sleep = h.switcher.sleep
        polls = []

        def sleep(seconds):
            polls.append(seconds)
            original_sleep(seconds)
            if len(polls) == 3:
                h.aws.spot("healthy")
            h.switcher.last_request = h.now

        h.switcher.sleep = sleep
        h.run()
        self.assertEqual(h.switcher.mode, ADMIN)
        self.assertGreaterEqual(len(polls), 3)
        self.assertEqual(h.aws.calls[0][0], "deregister")

    def test_gives_up_waiting_for_spot_when_requests_stop(self):
        h = Harness()
        h.aws.spot("unhealthy")
        h.switcher.lease_request()
        h.run()
        self.assertEqual(h.switcher.mode, CHAT)
        self.assertGreater(h.now, IDLE_SECONDS)
        self.assertEqual(h.aws.calls, [])
        self.assertEqual(h.host.calls, [])
        self.assertIn(SELF, h.aws.groups[CHAT_TG])

    def admin(self):
        h = Harness()
        h.aws.spot("healthy")
        h.switcher.lease_request()
        h.run()
        h.aws.calls.clear()
        h.host.calls.clear()
        return h

    def test_returns_to_chat_after_the_administrator_model_stops(self):
        h = self.admin()
        h.host.admin = True
        h.switcher.tick()
        h.host.admin = False
        h.advance(RELEASE_GRACE_SECONDS + 1)
        h.switcher.tick()
        self.assertEqual(h.switcher.mode, ADMIN)  # 멈춘 것을 처음 본 시점부터 유예를 셈
        h.advance(RELEASE_GRACE_SECONDS)
        h.switcher.tick()
        self.assertEqual(h.switcher.mode, TO_CHAT)
        self.assertFalse(h.switcher.model_open())
        self.assertFalse(h.switcher.lease_request())
        h.run()
        self.assertEqual(h.switcher.mode, CHAT)
        self.assertEqual(h.host.calls, ["start_chat"])
        self.assertEqual(sorted(call[1] for call in h.aws.calls if call[0] == "register"), [CHAT_TG, EMBEDDING_TG])

    def test_new_lease_right_after_release_keeps_admin_mode(self):
        h = self.admin()
        h.host.admin = True
        h.switcher.tick()
        h.host.admin = False
        h.switcher.tick()
        h.advance(RELEASE_GRACE_SECONDS + 1)
        self.assertTrue(h.switcher.lease_request())
        h.switcher.tick()
        self.assertEqual(h.switcher.mode, ADMIN)

    def test_admin_mode_without_any_model_start_returns_after_idle(self):
        h = self.admin()
        h.advance(IDLE_SECONDS / 2)
        h.switcher.tick()
        self.assertEqual(h.switcher.mode, ADMIN)
        h.advance(IDLE_SECONDS)
        h.switcher.tick()
        h.advance(RELEASE_GRACE_SECONDS)
        h.switcher.tick()
        self.assertIn(h.switcher.mode, (TO_CHAT, CHAT))
        h.run()
        self.assertEqual(h.switcher.mode, CHAT)

    def test_failed_switch_returns_to_chat_and_the_load_balancer(self):
        h = Harness()
        h.aws.spot("healthy")

        def broken():
            raise RuntimeError("systemctl failed")

        h.host.stop_chat = broken
        h.switcher.lease_request()
        h.run()
        self.assertEqual(h.switcher.mode, CHAT)
        self.assertIn(SELF, h.aws.groups[CHAT_TG])
        self.assertIn(SELF, h.aws.groups[EMBEDDING_TG])

    def test_restart_takes_over_a_running_administrator_model(self):
        h = Harness()
        h.switcher.mode = TO_CHAT
        h.host.admin = True
        h.host.chat = False
        h.switcher.reconcile()
        self.assertEqual(h.switcher.mode, ADMIN)
        self.assertEqual(h.tasks, [])

    def test_restart_without_administrator_model_serves_chat(self):
        h = Harness()
        h.aws.groups = {CHAT_TG: {}, EMBEDDING_TG: {}}
        h.switcher.mode = TO_CHAT
        h.switcher.reconcile()
        h.run()
        self.assertEqual(h.switcher.mode, CHAT)
        self.assertIn(SELF, h.aws.groups[CHAT_TG])
        self.assertIn(SELF, h.aws.groups[EMBEDDING_TG])

    def test_only_the_shared_token_can_start_a_switch(self):
        token = "t" * 32
        self.assertTrue(authorized("Bearer " + token, token))
        self.assertFalse(authorized("Bearer " + "x" * 32, token))
        self.assertFalse(authorized(None, token))


if __name__ == "__main__":
    unittest.main()
