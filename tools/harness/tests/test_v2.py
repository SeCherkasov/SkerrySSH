"""Behavioral contracts for the replacement harness, independent of real Gradle."""
import json
import os
import subprocess
import sys
import time
import unittest
import contextlib
import io
from pathlib import Path
from unittest.mock import patch

from harness import policy, state
from support import SandboxCase


class TestPlan(SandboxCase):
    def plan(self, mode="final"):
        return policy.plan(mode, self.cwd)

    def test_harness_change_never_launches_gradle(self):
        self.change("tools/harness/policy.py", "# replacement\n")
        self.assertEqual([s.name for s in self.plan()], ["checks", "selftest"])

    def test_server_change_does_not_build_android(self):
        self.change()
        commands = " ".join(" ".join(s.command) for s in self.plan())
        self.assertIn(":server:build", commands)
        self.assertIn("-PserverOnly", commands)
        self.assertNotIn("androidApp", commands)

    def test_wire_change_checks_both_consumers(self):
        self.change("sync-wire/src/main/kotlin/Wire.kt")
        names = {s.name for s in self.plan()}
        self.assertTrue({"tests:server", "tests:shared", "tests:composeApp", "build:androidApp"} <= names)

    def test_shared_change_checks_android_even_without_ui_diff(self):
        self.change("shared/src/commonMain/kotlin/A.kt")
        self.assertIn("build:androidApp", {s.name for s in self.plan()})

    def test_fast_results_do_not_imply_final_build(self):
        self.change()
        self.assertTrue(all(not s.name.startswith("build:") for s in self.plan("fast")))
        self.assertTrue(any(s.name.startswith("build:") for s in self.plan()))

    def test_docs_are_free_but_ci_is_executable_policy(self):
        self.change("docs/notes.md")
        self.assertEqual(self.plan(), [])
        self.box.write(".github/workflows/ci.yml", "name: CI\n")
        self.assertIn("selftest", {s.name for s in self.plan()})

    def test_root_build_change_checks_all_modules(self):
        self.change("settings.gradle.kts")
        self.assertEqual({s.name.split(":")[1] for s in self.plan() if s.name.startswith("build:")},
                         set(policy.MODULES))

    def test_unknown_executable_input_is_conservative(self):
        self.change("tools/package.sh", "#!/bin/sh\n")
        self.assertTrue(any(s.name.startswith("build:") for s in self.plan()))

    def test_main_ci_does_not_skip_build_on_an_empty_diff(self):
        self.box.write("server/src/main/kotlin/A.kt", "val a = 1\n")
        self.box.commit("source")
        with patch.dict(os.environ, {"CI": "true"}):
            self.assertIn("build:server", {s.name for s in self.plan()})

    def test_asset_and_wrapper_mode_changes_are_inputs(self):
        self.change("shared/src/commonMain/resources/logo.png", "asset")
        from harness import evidence
        stage = next(s for s in self.plan() if s.name == "build:shared")
        evidence.record_stage(stage, True, "built", self.cwd)
        self.box.write("shared/src/commonMain/resources/logo.png", "changed asset")
        self.assertFalse(evidence.current(stage, self.cwd))

    def test_review_regression_wrapper_jar_is_not_docs(self):
        self.change("gradle/wrapper/gradle-wrapper.jar", "binary")
        self.assertIn("build:server", {s.name for s in self.plan()})

    def test_review_regression_server_change_runs_shared_integration_without_android(self):
        self.change()
        names = {s.name for s in self.plan()}
        self.assertIn("tests:shared", names)
        self.assertNotIn("build:androidApp", names)
        shared = next(s for s in self.plan() if s.name == "tests:shared")
        self.assertIn("server", shared.modules)
        self.assertNotIn("-PserverOnly", shared.command)

    def test_shared_build_receipt_depends_on_server_fixture(self):
        from harness import evidence
        self.change("shared/src/commonMain/kotlin/A.kt")
        stage = next(s for s in self.plan() if s.name == "build:shared")
        evidence.record_stage(stage, True, "built", self.cwd)
        self.box.write("server/src/main/kotlin/Fixture.kt", "changed fixture")
        self.assertFalse(evidence.current(stage, self.cwd))


class TestAgents(SandboxCase):
    def test_profiles_are_explicit_and_never_maximum(self):
        from harness import agents
        for name in ("explorer", "worker", "skerry-reviewer", "skerry-kotlin-reviewer", "skerry-security-reviewer"):
            selected = agents.profile(name, self.cwd)
            self.assertTrue(selected["model"])
            self.assertIn(selected["reasoning_effort"], ("low", "medium", "high"))
            self.assertEqual(selected["fork_turns"], "none")
        self.assertEqual(agents.profile("explorer", self.cwd)["reasoning_effort"], "low")

    def test_native_configs_match_portable_profiles(self):
        import tomllib
        from harness import agents
        for file in agents.sync_configs(self.cwd):
            config = tomllib.loads(Path(file).read_text())
            selected = agents.profile(config["name"], self.cwd)
            self.assertEqual(config["model"], selected["model"])
            self.assertEqual(config["model_reasoning_effort"], selected["reasoning_effort"])
            self.assertIn("Harness v2 report", config["developer_instructions"])


class TestProcessLifecycle(SandboxCase):
    @unittest.skipUnless(Path("/proc").exists(), "Linux process inventory")
    def test_success_reaps_detached_children_but_preserves_unrelated_process(self):
        from harness import runner, resources
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        self.addCleanup(unrelated.wait)
        self.addCleanup(unrelated.terminate)
        script = ("import subprocess,sys; "
                  "p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True); "
                  "print(p.pid,flush=True)")
        log = Path(self.cwd, ".git/process.log")
        self.assertEqual(runner.execute([sys.executable, "-c", script], log, self.cwd), 0)
        child = int(log.read_text().splitlines()[0])
        status = Path(f"/proc/{child}/stat")
        self.assertTrue(not status.exists() or status.read_text().rsplit(") ", 1)[1].split()[0] == "Z")
        self.assertIsNone(unrelated.poll())

    def test_timeout_terminates_wrapper(self):
        from harness import runner
        log = Path(self.cwd, ".git/process.log")
        with self.assertRaises(subprocess.TimeoutExpired):
            runner.execute([sys.executable, "-c", "import time; time.sleep(60)"], log, self.cwd, timeout=0.1)


class TestGitAdapters(SandboxCase):
    def test_native_hook_blocks_commit_with_missing_evidence(self):
        from harness import environment
        self.change("AGENTS.md", "instructions\n")
        environment.install_hooks(self.cwd)
        self.box.git("add", "-A")
        result = subprocess.run(["git", "commit", "-qm", "blocked"], cwd=self.cwd, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("delivery blocked", result.stdout + result.stderr)

    def test_hook_installation_preserves_existing_hooks(self):
        from harness import environment
        self.box.git("config", "core.hooksPath", "custom-hooks")
        with self.assertRaises(ValueError):
            environment.install_hooks(self.cwd)
        self.assertEqual(self.box.git("config", "--get", "core.hooksPath").strip(), "custom-hooks")

    def test_main_is_protected_even_for_docs(self):
        from harness import environment
        with self.assertRaises(ValueError):
            environment.guard("commit", cwd=self.cwd)

    def test_push_cannot_validate_another_ref_with_current_head_receipts(self):
        from harness import environment
        self.box.branch("docs/example")
        with self.assertRaises(ValueError):
            environment.guard("push", "refs/heads/other " + "a"*40 + " refs/heads/other " + "0"*40, self.cwd)


class TestEvidence(SandboxCase):
    def setUp(self):
        super().setUp()
        from harness import evidence
        self.evidence = evidence
        self.change()

    def stage(self, prefix="tests:"):
        return next(s for s in policy.plan("final", self.cwd) if s.name.startswith(prefix))

    def record(self, stage):
        self.evidence.record_stage(stage, True, "test", self.cwd)

    def test_harness_edit_preserves_product_test_evidence(self):
        stage = self.stage()
        self.record(stage)
        self.box.write("AGENTS.md", "new instructions\n")
        self.assertTrue(self.evidence.current(stage, self.cwd))

    def test_upstream_input_invalidates_and_revert_restores(self):
        stage = self.stage()
        self.record(stage)
        self.box.write("server/src/main/kotlin/A.kt", "val a = 2\n")
        self.assertFalse(self.evidence.current(stage, self.cwd))
        self.box.write("server/src/main/kotlin/A.kt", "val a = 1\n")
        self.assertTrue(self.evidence.current(stage, self.cwd))

    def test_input_changed_during_run_is_refused(self):
        stage = self.stage()
        before = self.evidence.snapshot(stage, self.cwd)
        self.box.write("server/src/main/kotlin/A.kt", "val a = 2\n")
        with self.assertRaises(ValueError):
            self.evidence.record_stage(stage, True, "test", self.cwd, before=before)

    def test_command_change_invalidates(self):
        from dataclasses import replace
        stage = self.stage()
        self.record(stage)
        changed = replace(stage, command=stage.command + ("--stacktrace",))
        self.assertFalse(self.evidence.current(changed, self.cwd))

    def test_old_gate_state_is_never_imported_as_green(self):
        Path(self.cwd, ".git/skerry-gate").mkdir()
        Path(self.cwd, ".git/skerry-gate/state.json").write_text('{"stages":{"tests":{"ok":true}}}')
        self.assertFalse(self.evidence.current(self.stage(), self.cwd))

    def test_corrupt_new_state_fails_explicitly(self):
        self.evidence.update(lambda data: data.update({"test": 1}), self.cwd)
        self.evidence.path(self.cwd).write_text("broken")
        with self.assertRaises(ValueError):
            self.evidence.load(self.cwd)

    def test_concurrent_updates_preserve_both_writers(self):
        script = ("from harness import evidence; import sys; "
                  "evidence.update(lambda d: d.update({sys.argv[1]:True}))")
        env = dict(os.environ, PYTHONPATH=str(Path(self.cwd, "tools")))
        processes = [subprocess.Popen([sys.executable, "-c", script, str(i)], cwd=self.cwd, env=env)
                     for i in range(6)]
        self.assertEqual([p.wait() for p in processes], [0] * 6)
        self.assertTrue(all(self.evidence.load(self.cwd)[str(i)] for i in range(6)))

    def test_second_runner_is_blocked(self):
        with self.evidence.build_lock(self.cwd):
            with self.assertRaises(RuntimeError):
                with self.evidence.build_lock(self.cwd):
                    self.fail("a concurrent runner acquired the lock")

    def test_receipts_are_private(self):
        self.record(self.stage())
        self.assertEqual(self.evidence.path(self.cwd).stat().st_mode & 0o777, 0o600)


class TestReviews(SandboxCase):
    def setUp(self):
        super().setUp()
        from harness import reviews
        self.reviews = reviews
        self.change("tools/harness/policy.py", "# change\n")

    def report(self, token, findings=None):
        return {"schema": 1, "token": token, "reviewer": "skerry-reviewer",
                "summary": "Checked changed policy and its callers.", "findings": findings or []}

    def test_report_requires_launch_snapshot(self):
        with self.assertRaises(ValueError):
            self.reviews.record(self.report("made-up"), self.cwd)

    def test_no_findings_closes_current_review(self):
        token = self.reviews.start("skerry-reviewer", self.cwd)["token"]
        self.reviews.record(self.report(token), self.cwd)
        self.assertEqual(self.reviews.debt(self.cwd), [])

    def test_drift_is_rejected(self):
        token = self.reviews.start("skerry-reviewer", self.cwd)["token"]
        self.box.write("tools/harness/policy.py", "# moved\n")
        with self.assertRaises(ValueError):
            self.reviews.record(self.report(token), self.cwd)

    def test_findings_need_resolution_and_reason(self):
        token = self.reviews.start("skerry-reviewer", self.cwd)["token"]
        finding = {"id": "F1", "priority": 1, "path": "tools/harness/policy.py", "line": 1,
                   "title": "Missing validation", "body": "Malformed input can pass the gate."}
        self.reviews.record(self.report(token, [finding]), self.cwd)
        self.assertTrue(self.reviews.debt(self.cwd))
        with self.assertRaises(ValueError):
            self.reviews.resolve(token, "F1", "rejected", "", self.cwd)
        self.reviews.resolve(token, "F1", "rejected", "The caller validates before this path.", self.cwd)
        self.assertEqual(self.reviews.debt(self.cwd), [])

    def test_two_rounds_never_make_changed_code_green(self):
        for i in range(2):
            token = self.reviews.start("skerry-reviewer", self.cwd)["token"]
            self.reviews.record(self.report(token), self.cwd)
            self.box.write("tools/harness/policy.py", f"# moved {i}\n")
        self.assertTrue(self.reviews.debt(self.cwd))
        with self.assertRaises(RuntimeError):
            self.reviews.start("skerry-reviewer", self.cwd)

    def test_new_review_does_not_erase_open_finding(self):
        token = self.reviews.start("skerry-reviewer", self.cwd)["token"]
        finding = {"id": "F1", "priority": 2, "path": "tools/harness/policy.py", "line": 1,
                   "title": "Lost evidence", "body": "State overwrite loses a recorded result."}
        self.reviews.record(self.report(token, [finding]), self.cwd)
        self.box.write("tools/harness/policy.py", "# fix\n")
        next_token = self.reviews.start("skerry-reviewer", self.cwd)["token"]
        self.reviews.record(self.report(next_token), self.cwd)
        self.assertTrue(any("F1" in d for d in self.reviews.debt(self.cwd)))


class TestResults(SandboxCase):
    def setUp(self):
        super().setUp()
        from harness import runner
        self.runner = runner

    def xml(self, failures=0, tests=1, skipped=0):
        self.box.write("server/build/test-results/test/TEST-A.xml",
                       f'<testsuite tests="{tests}" failures="{failures}" errors="0" skipped="{skipped}"/>')

    def test_zero_exit_without_results_is_not_evidence(self):
        with self.assertRaises(ValueError):
            self.runner.test_verdict("server", "test", self.cwd)

    def test_failed_suite_is_refused(self):
        self.xml(failures=1)
        with self.assertRaises(ValueError):
            self.runner.test_verdict("server", "test", self.cwd)

    def test_entirely_skipped_suite_is_not_verified(self):
        self.xml(tests=2, skipped=2)
        with self.assertRaises(ValueError):
            self.runner.test_verdict("server", "test", self.cwd)

    def test_results_are_scoped_to_the_expected_task(self):
        self.xml()
        self.assertIn("1", self.runner.test_verdict("server", "test", self.cwd))
        with self.assertRaises(ValueError):
            self.runner.test_verdict("shared", "desktopTest", self.cwd)


class TestCLI(SandboxCase):
    def call(self, *args):
        return subprocess.run([sys.executable, "tools/harness/gate.py", *args], cwd=self.cwd,
                              text=True, capture_output=True)

    def test_doctor_and_plan_return_json(self):
        self.change("AGENTS.md", "# instructions\n")
        for command in ("doctor", "plan"):
            result = self.call(command, "--json")
            data = json.loads(result.stdout)
            self.assertIn("summary", data)
            self.assertIn("next_actions", data)

    def test_run_does_not_say_success_with_review_debt(self):
        self.change("tools/harness/policy.py", "# change\n")
        # Test the result contract without recursively running the suite.
        from harness import gate
        with patch("harness.runner.run", return_value=True):
            old = os.getcwd()
            os.chdir(self.cwd)
            self.addCleanup(os.chdir, old)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(gate.main(["run"]), 1)

    def test_review_regression_git_errors_never_verify_as_docs(self):
        self.change("AGENTS.md", "instructions\n")
        broken = self.box.write(".git/broken-index", "invalid index")
        with patch.dict(os.environ, {"GIT_INDEX_FILE": broken}):
            result = self.call("verify", "--json")
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            self.assertEqual(json.loads(result.stdout)["status"], "error")
