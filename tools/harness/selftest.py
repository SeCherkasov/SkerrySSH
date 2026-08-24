#!/usr/bin/env python3
"""Tests for the harness itself.

The previous version had none: both holes found in it (a redirected log the recorder could not
read, an escape hatch that did not work from inside the session) were found in production, on a
branch that could not be committed. Everything here runs against throwaway git repositories in a
temp dir — no Gradle, no network, about a second in total.

    python3 tools/harness/selftest.py
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

HARNESS_DIR = os.path.dirname(os.path.abspath(__file__))
TOOLS_DIR = os.path.dirname(HARNESS_DIR)
REPO_ROOT = os.path.dirname(TOOLS_DIR)
sys.path.insert(0, TOOLS_DIR)

from harness import checks, gate, policy, state  # noqa: E402


def run_git(cwd: str, *args: str) -> str:
    out = subprocess.run(["git"] + list(args), cwd=cwd, capture_output=True, text=True, check=False)
    return out.stdout


class Sandbox:
    """A git repository with the harness installed, standing in for the real worktree."""

    def __init__(self) -> None:
        self.path = tempfile.mkdtemp(prefix="skerry-harness-")
        run_git(self.path, "init", "-q", "-b", "main")
        run_git(self.path, "config", "user.email", "harness@test")
        run_git(self.path, "config", "user.name", "Harness")
        shutil.copytree(HARNESS_DIR, os.path.join(self.path, "tools", "harness"))
        os.makedirs(os.path.join(self.path, ".claude", "hooks"), exist_ok=True)
        for hook in ("guard-git.py", "record-review.py"):
            shutil.copy2(os.path.join(REPO_ROOT, ".claude", "hooks", hook),
                         os.path.join(self.path, ".claude", "hooks", hook))
        # The repo-local reviewers are part of the clone, so the sandbox has to have them too:
        # without them the gate degrades to the plugin agents and tests silently check less.
        shutil.copytree(os.path.join(REPO_ROOT, ".claude", "agents"),
                        os.path.join(self.path, ".claude", "agents"))
        self.write("README.md", "seed\n")
        self.commit("seed")

    def write(self, rel: str, body: str) -> str:
        full = os.path.join(self.path, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as fh:
            fh.write(body)
        return full

    def commit(self, message: str) -> None:
        run_git(self.path, "add", "-A")
        run_git(self.path, "commit", "-q", "-m", message)

    def branch(self, name: str) -> None:
        run_git(self.path, "checkout", "-q", "-b", name)

    def guard(self, command: str, env: dict | None = None) -> subprocess.CompletedProcess:
        payload = json.dumps({"tool_name": "Bash", "tool_input": {"command": command}})
        environment = dict(os.environ)
        environment.pop("SKERRY_GATE_OVERRIDE", None)
        environment.update(env or {})
        return subprocess.run(
            [sys.executable, os.path.join(self.path, ".claude", "hooks", "guard-git.py")],
            input=payload, capture_output=True, text=True, cwd=self.path,
            env=environment, check=False,
        )

    def recorder(self, subagent: str, response) -> subprocess.CompletedProcess:
        """Drive the PostToolUse recorder exactly as the session does — the layer the review gate
        was recorded from intent in, and the one no test used to reach."""
        return self.raw_hook({"tool_name": "Agent", "tool_input": {"subagent_type": subagent},
                              "tool_response": response})

    def raw_hook(self, payload: dict) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, os.path.join(self.path, ".claude", "hooks", "record-review.py")],
            input=json.dumps(payload), capture_output=True, text=True, cwd=self.path, check=False,
        )

    def gate(self, *args: str, stdin: str = "") -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, os.path.join(self.path, "tools", "harness", "gate.py"), *args],
            input=stdin, capture_output=True, text=True, cwd=self.path, check=False,
        )

    def cleanup(self) -> None:
        shutil.rmtree(self.path, ignore_errors=True)


class SandboxCase(unittest.TestCase):
    def setUp(self) -> None:
        self.box = Sandbox()
        self.addCleanup(self.box.cleanup)
        self.cwd = self.box.path


class TestRelevance(unittest.TestCase):
    def test_code_files_count(self):
        for path in ("shared/src/commonMain/kotlin/A.kt", "build.gradle.kts",
                     "composeApp/src/commonMain/composeResources/values/strings.xml",
                     "gradle/libs.versions.toml"):
            self.assertTrue(state.is_code(path), path)

    def test_prose_and_generated_files_do_not(self):
        for path in ("README.md", "docs/coding-guidelines.md", ".github/workflows/ci.yml",
                     "composeApp/build/generated/Thing.kt", "docs/img/x.png"):
            self.assertFalse(state.is_code(path), path)

    def test_the_harness_counts_as_code_it_gates(self):
        # It used to be ignored, so deleting a rule moved no digest and the stage that had already
        # run against the old rule set stayed green.
        for path in ("tools/harness/checks.py", ".claude/hooks/guard-git.py"):
            self.assertTrue(state.is_code(path), path)

    def test_a_reviewer_definition_is_code_the_gate_watches(self):
        # The agent files are the executable content of the review gate: prose everywhere else,
        # but editing one changes what a reviewer looks for.
        self.assertTrue(state.is_code(".claude/agents/skerry-security-reviewer.md"))
        self.assertFalse(state.is_code("docs/design/notes.md"))
        self.assertEqual(policy.areas([".claude/agents/skerry-reviewer.md"]), ["harness"])

    def test_a_slash_command_is_code_the_gate_watches(self):
        # `/gate` and `/task` drive the fan-out and the kind declaration: the same argument that
        # made agent definitions code.
        self.assertTrue(state.is_code(".claude/commands/gate.md"))
        self.assertEqual(policy.areas([".claude/commands/gate.md"]), ["harness"])

    def test_the_invisible_byte_list_covers_the_bidi_and_c1_families(self):
        # ALM is a bidi control like LRM; C1 opens CSI/OSC in a UTF-8 xterm; the word-joiner block
        # and the BOM are invisible in review the same way the zero-width space is.
        for point in (0x061C, 0x2060, 0xFEFF, 0x007F, 0x009B, 0x202E, 0x200B,
                      0x2028, 0x2029, 0x000C, 0x0085):
            self.assertIsNotNone(state.CONTROL_CHARS.search(chr(point)), hex(point))
        for point in (0x0009, 0x000A, 0x0041, 0x0410, 0x4E2D, 0x00A0):
            self.assertIsNone(state.CONTROL_CHARS.search(chr(point)), hex(point))

    def test_test_sources_are_marked(self):
        self.assertTrue(state.is_test("shared/src/commonTest/kotlin/AT.kt"))
        self.assertTrue(state.is_test("composeApp/src/desktopTest/kotlin/B.kt"))
        self.assertFalse(state.is_test("shared/src/commonMain/kotlin/A.kt"))


class TestDigest(SandboxCase):
    def test_edit_outside_the_session_invalidates(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        # The hole this replaces: a change made by sed, git apply or an outside editor left the
        # timestamp-based recorder none the wiser.
        subprocess.run(["sed", "-i", "s/1/2/", "shared/src/commonMain/kotlin/A.kt"],
                       cwd=self.cwd, check=True)
        self.assertNotEqual(before, state.tree_digest("all", self.cwd))

    def test_commit_does_not_invalidate(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        self.box.commit("work")
        self.assertEqual(before, state.tree_digest("all", self.cwd),
                         "committing changes HEAD, not content — a gated tree stays gated")

    def test_committing_a_deletion_does_not_invalidate(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.write("shared/src/commonMain/kotlin/B.kt", "val b = 1\n")
        self.box.commit("two files")
        os.remove(os.path.join(self.cwd, "shared/src/commonMain/kotlin/B.kt"))
        before = state.tree_digest("all", self.cwd)
        self.box.commit("drop one")
        self.assertEqual(before, state.tree_digest("all", self.cwd),
                         "a deleted file is gone either way — the commit only records that")

    def test_committing_a_rename_does_not_invalidate(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.commit("one file")
        os.remove(os.path.join(self.cwd, "shared/src/commonMain/kotlin/A.kt"))
        self.box.write("shared/src/commonMain/kotlin/Moved.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        self.box.commit("move it")
        self.assertEqual(before, state.tree_digest("all", self.cwd),
                         "the hole this closes: a gate green before the commit reopened after it")

    def test_deleting_a_file_still_invalidates(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.write("shared/src/commonMain/kotlin/B.kt", "val b = 1\n")
        self.box.commit("two files")
        before = state.tree_digest("all", self.cwd)
        os.remove(os.path.join(self.cwd, "shared/src/commonMain/kotlin/B.kt"))
        self.assertNotEqual(before, state.tree_digest("all", self.cwd),
                            "deleting code is an edit like any other")

    def test_revert_restores_the_digest(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        original = state.tree_digest("all", self.cwd)
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 999\n")
        self.assertNotEqual(original, state.tree_digest("all", self.cwd))
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(original, state.tree_digest("all", self.cwd))

    def test_prose_does_not_move_the_digest(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        self.box.write("docs/notes.md", "prose\n")
        self.assertEqual(before, state.tree_digest("all", self.cwd))

    def test_src_scope_ignores_tests(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("src", self.cwd)
        self.box.write("shared/src/commonTest/kotlin/ATest.kt", "// test\n")
        self.assertEqual(before, state.tree_digest("src", self.cwd))
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 2\n")
        self.assertNotEqual(before, state.tree_digest("src", self.cwd))


class TestClassification(SandboxCase):
    def test_branch_prefix_decides_the_kind(self):
        for prefix, kind in (("fix", "bug"), ("feat", "feature"), ("refactor", "refactor")):
            run_git(self.cwd, "checkout", "-q", "main")
            self.box.branch(f"{prefix}/thing-{prefix}")
            self.box.write("shared/src/commonMain/kotlin/A.kt", f"val a = \"{prefix}\"\n")
            task = policy.classify(self.cwd)
            self.assertEqual(task["kind"], kind, prefix)

    def test_no_code_means_docs_whatever_the_branch_says(self):
        self.box.branch("feat/readme")
        self.box.write("README.md", "prose\n")
        self.assertEqual(policy.classify(self.cwd)["kind"], "docs")

    def test_a_docs_branch_does_not_disarm_a_code_change(self):
        # The declaration is floored by the branch; the branch has to be floored by the diff, or
        # `git checkout -b docs/tidy` is a quieter override than SKERRY_GATE_OVERRIDE.
        self.box.branch("docs/tidy")
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "val a = 1\n")
        task = policy.classify(self.cwd)
        self.assertEqual(task["kind"], "feature")
        self.assertTrue(policy.required_stages(task))

    def test_unknown_prefix_defaults_to_feature(self):
        self.box.branch("wip")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        task = policy.classify(self.cwd)
        self.assertEqual(task["kind"], "feature")
        self.assertEqual(task["source"], "default")

    def test_declaration_wins_on_its_branch_only(self):
        self.box.branch("wip/unnamed")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        st = state.load(self.cwd)
        st["task"] = {"kind": "bug", "ref": "#133", "branch": "wip/unnamed"}
        state.save(st, self.cwd)
        self.assertEqual(policy.classify(self.cwd)["kind"], "bug")

        run_git(self.cwd, "checkout", "-q", "main")
        self.box.branch("wip/other")
        self.box.write("shared/src/commonMain/kotlin/B.kt", "val b = 1\n")
        self.assertEqual(policy.classify(self.cwd)["kind"], "feature",
                         "a declaration must not follow the worktree onto the next branch")

    def test_declaring_docs_cannot_disarm_a_code_change(self):
        self.box.branch("wip/sneaky")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        st = state.load(self.cwd)
        st["task"] = {"kind": "docs", "branch": "wip/sneaky"}
        state.save(st, self.cwd)
        task = policy.classify(self.cwd)
        self.assertEqual(task["kind"], "feature")
        self.assertNotEqual(policy.gate_debt(self.cwd)[1], [])

    def test_a_docs_branch_cannot_declare_itself_docs_over_code(self):
        # The undeclared path floors `docs/` to feature. The declared path compared against the
        # unfloored branch kind, so `docs/tidy` + `gate.py task docs` accepted itself and the
        # whole gate went quiet — without the override having to be typed out loud.
        self.box.branch("docs/tidy")
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "val a = 1\n")
        self.box.gate("task", "docs")
        task = policy.classify(self.cwd)
        self.assertEqual(task["kind"], "feature")
        self.assertNotEqual(policy.gate_debt(self.cwd)[1], [])

    def test_a_declaration_cannot_drop_the_red_phase(self):
        # The module says a declaration can only tighten the gate. `task refactor` on a `fix/`
        # branch dropped `red`, and it does not announce itself the way the override does.
        self.box.branch("fix/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.gate("task", "refactor")
        task = policy.classify(self.cwd)
        self.assertEqual(task["kind"], "bug")
        self.assertIn("red", policy.required_stages(task))

    def test_declaring_a_stricter_kind_is_honoured(self):
        self.box.branch("chore/actually-a-bug")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(policy.classify(self.cwd)["kind"], "refactor")
        st = state.load(self.cwd)
        st["task"] = {"kind": "bug", "branch": "chore/actually-a-bug"}
        state.save(st, self.cwd)
        self.assertEqual(policy.classify(self.cwd)["kind"], "bug")
        self.assertIn("red", " ".join(policy.gate_debt(self.cwd)[1]))

    def test_areas_follow_the_paths(self):
        self.assertEqual(policy.areas(["composeApp/src/commonMain/kotlin/ui/S.kt"]), ["ui"])
        self.assertIn("server", policy.areas(["server/src/main/kotlin/R.kt"]))
        self.assertIn("android", policy.areas(["androidApp/src/main/AndroidManifest.xml"]))
        self.assertIn("i18n", policy.areas(
            ["composeApp/src/commonMain/composeResources/values-ru/strings.xml"]))
        # Without this the `harness` rule could be deleted and every case above still passed: the
        # stage it pulls in was pinned, the classification that pulls it in was not.
        self.assertIn("harness", policy.areas(["tools/harness/policy.py"]))
        self.assertIn("harness", policy.areas([".claude/hooks/record-review.py"]))


class TestRequirements(unittest.TestCase):
    def test_docs_owes_nothing(self):
        self.assertEqual(policy.required_stages({"kind": "docs", "areas": []}), [])
        self.assertEqual(policy.required_reviewers({"kind": "docs", "areas": []}), [])

    def test_bug_owes_a_red_phase(self):
        stages = policy.required_stages({"kind": "bug", "areas": ["shared"]})
        self.assertIn("red", stages)
        self.assertNotIn("red", policy.required_stages({"kind": "feature", "areas": ["shared"]}))

    def test_a_harness_change_owes_its_own_suite(self):
        # Gradle cannot see a line of Python, so this suite is the only stage that gates the gate.
        self.assertIn("selftest", policy.required_stages({"kind": "refactor", "areas": ["harness"]}))
        self.assertNotIn("selftest", policy.required_stages({"kind": "refactor", "areas": ["ui"]}))

    def test_the_hook_wiring_is_part_of_the_harness(self):
        # `.claude/settings.json` is where the recorder is registered: Gradle cannot see it, and
        # the suite is the only thing that can.
        self.assertEqual(policy.areas([".claude/settings.json"]), ["harness"])

    def test_a_harness_only_change_does_not_owe_gradle(self):
        # No Gradle stage can see a line of Python. Charging a five-line hook fix ten minutes of
        # `test allTests` and `build` is how a harness ends up routinely overridden.
        self.assertEqual(policy.required_stages({"kind": "refactor", "areas": ["harness"]}),
                         ["checks", "selftest", "review"])
        both = policy.required_stages({"kind": "refactor", "areas": ["harness", "ui"]})
        self.assertIn("tests", both)
        self.assertIn("selftest", both)

    def test_ui_pulls_in_the_android_compile_and_a11y(self):
        task = {"kind": "feature", "areas": ["ui"]}
        self.assertIn("android", policy.required_stages(task))
        self.assertIn("a11y-architect", policy.required_reviewers(task))

    def test_repo_local_reviewers_are_always_required(self):
        task = {"kind": "feature", "areas": ["shared"]}
        required = policy.required_reviewers(task, REPO_ROOT)
        self.assertIn("skerry-reviewer", required)
        self.assertIn("skerry-kotlin-reviewer", required)
        self.assertIn("skerry-security-reviewer", required)

    def test_server_pulls_in_the_java_reviewer(self):
        self.assertIn("java-reviewer",
                      policy.required_reviewers({"kind": "feature", "areas": ["server"]}))

    def test_shared_only_change_skips_the_android_compile(self):
        self.assertNotIn("android",
                         policy.required_stages({"kind": "refactor", "areas": ["shared"]}))


class TestRedProof(unittest.TestCase):
    def test_absent_record_is_not_a_proof(self):
        proven, why = policy.red_is_proven({}, "abc", "fix/x")
        self.assertFalse(proven)
        self.assertIn("gate.py red", why)

    def test_record_without_a_later_source_change_is_not_a_proof(self):
        st = {"red": [{"src_digest": "abc", "branch": "fix/x"}]}
        proven, why = policy.red_is_proven(st, "abc", "fix/x")
        self.assertFalse(proven, "the test is still red — nothing was fixed")
        self.assertIn("not changed", why)

    def test_record_plus_a_source_change_is_a_proof(self):
        st = {"red": [{"src_digest": "abc", "branch": "fix/x", "task": ":shared:desktopTest",
                       "pattern": "*Foo*"}]}
        proven, _ = policy.red_is_proven(st, "def", "fix/x")
        self.assertTrue(proven)

    def test_proof_does_not_cross_branches(self):
        st = {"red": [{"src_digest": "abc", "branch": "fix/other"}]}
        self.assertFalse(policy.red_is_proven(st, "def", "fix/x")[0])


class TestGateDebt(SandboxCase):
    def _gate_everything(self, task: dict) -> None:
        digest = state.tree_digest("all", self.cwd)
        st = state.load(self.cwd)
        st["stages"] = {stage: {"ok": True, "digest": digest}
                        for stage in policy.required_stages(task)
                        if stage not in ("red", "review")}
        st["reviews"] = {name: {"digest": policy.reviewer_digest(name, self.cwd),
                                "branch": state.current_branch(self.cwd)}
                         for name in policy.required_reviewers(task, self.cwd)}
        state.save(st, self.cwd)
        for name in policy.required_reviewers(task, self.cwd):
            state.save_review_report(name, f"# {name}\n\nfindings from a pass that really ran",
                                     self.cwd)

    def test_untouched_branch_owes_every_stage(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        _, debt = policy.gate_debt(self.cwd)
        owed = {item.split(" — ")[0] for item in debt}
        self.assertEqual(owed, {"checks", "tests", "build", "detekt", "review"})

    def test_a_fully_gated_branch_owes_nothing(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        task, _ = policy.gate_debt(self.cwd)
        self._gate_everything(task)
        _, debt = policy.gate_debt(self.cwd)
        self.assertEqual(debt, [])

    def test_an_edit_after_the_gate_reopens_it(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        task, _ = policy.gate_debt(self.cwd)
        self._gate_everything(task)
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 2\n")
        _, debt = policy.gate_debt(self.cwd)
        build_stages = [item for item in debt if not item.startswith("review")]
        self.assertTrue(build_stages and all("different code" in item for item in build_stages),
                        debt)
        self.assertTrue(any(item.startswith("review — missing") for item in debt), debt)

    def test_a_failed_stage_is_not_a_pass(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        task, _ = policy.gate_debt(self.cwd)
        self._gate_everything(task)
        st = state.load(self.cwd)
        st["stages"]["detekt"]["ok"] = False
        state.save(st, self.cwd)
        _, debt = policy.gate_debt(self.cwd)
        self.assertEqual(debt, ["detekt — failed"])

    def test_one_reviewer_does_not_close_the_fan_out(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        st = state.load(self.cwd)
        st["reviews"] = {"skerry-reviewer": {
            "digest": policy.reviewer_digest("skerry-reviewer", self.cwd),
            "branch": state.current_branch(self.cwd)}}
        state.save(st, self.cwd)
        state.save_review_report("skerry-reviewer", "# skerry-reviewer\n\nno findings", self.cwd)
        _, debt = policy.gate_debt(self.cwd)
        review = [item for item in debt if item.startswith("review")][0]
        missing = review.split("missing: ")[1]
        self.assertIn("skerry-kotlin-reviewer", missing)
        self.assertIn("pr-test-analyzer", missing)
        self.assertNotIn("skerry-reviewer,", missing + ",")

    def test_an_uninstalled_reviewer_is_skipped_not_owed(self):
        # A contributor without the ECC plugin must not face a gate no action of theirs can close.
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        os.remove(os.path.join(self.cwd, ".claude", "agents", "skerry-kotlin-reviewer.md"))
        task = policy.classify(self.cwd)
        self.assertNotIn("skerry-kotlin-reviewer", policy.required_reviewers(task, self.cwd))
        self.assertIn("skerry-kotlin-reviewer", policy.skipped_reviewers(task, self.cwd))
        _, debt = policy.gate_debt(self.cwd)
        review = [item for item in debt if item.startswith("review")][0]
        self.assertNotIn("skerry-kotlin-reviewer", review)

    def test_docs_branch_owes_nothing_at_all(self):
        self.box.branch("docs/readme")
        self.box.write("README.md", "prose\n")
        _, debt = policy.gate_debt(self.cwd)
        self.assertEqual(debt, [])


class TestGuardHook(SandboxCase):
    def test_commit_on_main_is_blocked(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        result = self.box.guard("git commit -m x")
        self.assertEqual(result.returncode, 2)
        self.assertIn("PR-only", result.stderr)

    def test_override_does_not_unprotect_main(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        result = self.box.guard("SKERRY_GATE_OVERRIDE=1 git commit -m x")
        self.assertEqual(result.returncode, 2, "main stays protected regardless of the override")

    def test_ungated_branch_is_blocked_with_the_debt_listed(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        result = self.box.guard("git commit -m x")
        self.assertEqual(result.returncode, 2)
        self.assertIn("feature", result.stderr)
        self.assertIn("tests", result.stderr)

    def test_override_in_the_command_text_works(self):
        # The environment a hook sees belongs to Claude Code, not to the command it is guarding,
        # so the documented `SKERRY_GATE_OVERRIDE=1 git push` had no effect at all until PR #115.
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        result = self.box.guard("SKERRY_GATE_OVERRIDE=1 git push")
        self.assertEqual(result.returncode, 0)

    def test_rtk_prefix_is_seen_through(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(self.box.guard("rtk git commit -m x").returncode, 2)

    def test_docs_change_commits_freely(self):
        self.box.branch("docs/readme")
        self.box.write("README.md", "prose\n")
        self.assertEqual(self.box.guard("git commit -m x").returncode, 0)

    def test_unrelated_commands_are_untouched(self):
        for command in ("ls -la", "git status", "./gradlew build", "git log --oneline"):
            self.assertEqual(self.box.guard(command).returncode, 0, command)

    def test_pr_create_asks_when_the_gate_is_clear(self):
        self.box.branch("docs/readme")
        self.box.write("README.md", "prose\n")
        result = self.box.guard("gh pr create --fill")
        self.assertEqual(result.returncode, 0)
        self.assertIn("permissionDecision", result.stdout)
        self.assertIn("ask", result.stdout)

    def test_pr_create_is_blocked_on_an_ungated_branch(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(self.box.guard("gh pr create --fill").returncode, 2)

    def test_a_broken_harness_does_not_wedge_the_repo(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        os.remove(os.path.join(self.cwd, "tools", "harness", "policy.py"))
        result = self.box.guard("git commit -m x")
        self.assertEqual(result.returncode, 0, "an unusable harness must fail open, not shut")


class TestChecks(SandboxCase):
    """Every blocking rule, proved both ways: it fires, and clean code does not trip it."""

    def _findings(self, rule: str) -> list:
        task = {"kind": "refactor", "areas": [], "paths": [], "code_paths": []}
        return [f for f in checks.run(self.cwd, task) if f.rule == rule]

    def setUp(self) -> None:
        super().setUp()
        self.box.branch("refactor/checks")

    def test_raw_text_and_icon_are_blocked(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Text(\"x\") }\nfun b() { Icon(y) }\n")
        rules = {f.rule for f in self._findings("design-primitives")}
        self.assertEqual(rules, {"design-primitives"})
        self.assertEqual(len(self._findings("design-primitives")), 2)

    def test_txt_and_sym_are_fine(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Txt(stringResource(Res.string.k)) }\nfun b() { Sym(Icons.X) }\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_similar_names_are_not_false_positives(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val a = BasicText(x)\nval b = TextField(y)\nval c = IconButton(z)\n"
                       "val d = TextStyle(w)\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_hardcoded_ui_string_is_blocked(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "fun a() { Txt(\"Connect\") }\n")
        self.assertEqual(len(self._findings("i18n-hardcoded")), 1)

    def test_punctuation_literal_is_not_a_string_to_translate(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "fun a() { Txt(\" · \") }\n")
        self.assertEqual(self._findings("i18n-hardcoded"), [])

    def test_interpolation_alone_is_not_prose(self):
        # Every false positive this rule produced across 25 merged PRs was of this shape.
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Txt(\"${state.index + 1}\") }\n"
                       "fun b() { Txt(\"#$tag\") }\n"
                       "fun c() { Txt(\"$count/$total\") }\n")
        self.assertEqual(self._findings("i18n-hardcoded"), [])

    def test_prose_around_interpolation_still_counts(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Txt(\"$count hosts online\") }\n")
        self.assertEqual(len(self._findings("i18n-hardcoded")), 1)

    def test_hex_colour_outside_the_theme_is_blocked(self):
        self.box.write("composeApp/src/commonMain/kotlin/ui/S.kt", "val c = Color(0xFF102030)\n")
        self.assertEqual(len(self._findings("design-hex")), 1)

    def test_hex_colour_inside_the_theme_is_allowed(self):
        self.box.write("composeApp/src/commonMain/kotlin/ui/theme/C.kt", "val c = Color(0xFF102030)\n")
        self.assertEqual(self._findings("design-hex"), [])

    def test_kotest_and_mockk_are_blocked(self):
        self.box.write("shared/src/commonTest/kotlin/T.kt",
                       "import io.kotest.matchers.shouldBe\nval m = mockk<Foo>()\n")
        self.assertEqual(len(self._findings("test-framework")), 2)

    def test_raw_dependency_coordinate_is_blocked(self):
        self.box.write("shared/build.gradle.kts",
                       "dependencies {\n  implementation(\"io.ktor:ktor-client:3.0.0\")\n}\n")
        self.assertEqual(len(self._findings("raw-dependency")), 1)

    def test_version_catalog_reference_is_fine(self):
        self.box.write("shared/build.gradle.kts",
                       "dependencies {\n  implementation(libs.ktor.client)\n}\n")
        self.assertEqual(self._findings("raw-dependency"), [])

    def test_plain_write_on_a_secret_path_is_blocked(self):
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "fun s() { file.writeText(pw) }\n")
        self.assertEqual(len(self._findings("secret-write")), 1)

    def test_atomic_write_is_fine(self):
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       "fun s() { atomicWriteUtf8(file, pw) }\n")
        self.assertEqual(self._findings("secret-write"), [])

    def test_invisible_characters_are_blocked_in_shipping_code(self):
        bidi = chr(0x202E)
        self.box.write("shared/src/commonMain/kotlin/A.kt", f"val a = \"x{bidi}y\"\n")
        found = self._findings("control-chars")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_invisible_characters_in_a_test_fixture_only_warn(self):
        # A sanitiser's test has to contain the byte it strips; that is the fixture, not a spoof.
        bidi = chr(0x202E)
        self.box.write("shared/src/commonTest/kotlin/ATest.kt", f"val a = \"ssh{bidi}d\"\n")
        found = self._findings("control-chars")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.WARN)

    def test_invisible_characters_are_blocked_in_a_comment_too(self):
        # Trojan source lives in comments by design: the payload has to sit where the reviewer
        # reads prose and the compiler reads nothing.
        bidi = chr(0x202E)
        self.box.write("shared/src/commonMain/kotlin/A.kt", f"// drop{bidi} the table\n")
        found = self._findings("control-chars")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_a_line_that_looks_like_a_diff_header_does_not_retarget_the_rules(self):
        # `git diff -U0` renders an added line `++ b/x` as `+++ b/x`, which the parser read as a
        # new file header: every rule after it was applied to the attacker's path instead.
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       'val sample = """\n'
                       "++ b/docs/notes.md\n"
                       '"""\n'
                       "fun leak() { file.writeText(secret) }\n")
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1,
                         "content cannot decide which path the rules are applied to")

    def test_the_line_that_does_the_spoofing_is_judged_like_any_other(self):
        # Skipping a `+++` line inside a hunk would let the same trick hide one line — its own.
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       'val sample = """\n'
                       '++ b/notes.md" + file.writeText(secret)\n'
                       '"""\n')
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1)

    def test_a_separator_python_invents_does_not_truncate_a_line(self):
        # `splitlines()` breaks on U+000C, U+001C-1E, U+0085, U+2028/9 — none of which git or
        # Kotlin treat as line ends — and eats the byte, so the rest of the line was never judged
        # and the invisible byte itself became invisible to the rule that looks for it.
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       "fun b(f: Path, s: String) { f\u000c.writeText(s) }\n")
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1)
        self.assertEqual(len(self._findings("control-chars")), 1)

    def test_a_gitattribute_cannot_hide_a_file_from_the_rules(self):
        # `*.kt -diff` makes git print "Binary files differ" instead of the content.
        self.box.write(".gitattributes", "*.kt -diff\n")
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       "fun b(f: Path, s: String) { f.writeText(s) }\n")
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1)

    def test_a_name_that_reads_as_a_pathspec_is_still_diffed(self):
        # git parses `:(icase)x.kt` as pathspec magic, not as a file name, so the per-file diff
        # came back empty and every line-based rule was applied to nothing.
        self.box.write(":(icase)Payload.kt", f"// drop{chr(0x202E)} the table\n")
        self.box.commit("payload")
        self.assertEqual(len(self._findings("control-chars")), 1)

    def test_a_file_name_that_is_not_utf8_does_not_disarm_the_gate(self):
        # git hands the name back as raw bytes; a strict decode raised out of `gate_debt`, and the
        # commit guard catches everything and allows — the whole gate off for one bad file name.
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        broken = os.path.join(self.cwd.encode(), b"shared/src/commonMain/kotlin/\xff.kt")
        with open(broken, "wb") as fh:
            fh.write(b"val b = 2\n")
        self.addCleanup(os.remove, broken)
        _, debt = policy.gate_debt(self.cwd)
        self.assertTrue(debt, "a file the harness cannot name is not a gate it can skip")
        self.assertNotEqual(state.tree_digest("all", self.cwd), "empty")

    def test_escaped_control_characters_are_fine(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = \"x\\u001Fy\"\n")
        self.assertEqual(self._findings("control-chars"), [])

    def test_allow_comment_silences_a_rule(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Text(\"x\") } // harness-allow: design-primitives\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_comments_are_not_code(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "// Text(\"x\") is what this used to be\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_shortcut_without_a_settings_row_is_blocked(self):
        self.box.write(checks.SHORTCUT_FILE, "val s = Key.F1\n")
        self.assertEqual(len(self._findings("shortcut-settings")), 1)

    def test_shortcut_with_a_settings_row_is_fine(self):
        self.box.write(checks.SHORTCUT_FILE, "val s = Key.F1\n")
        self.box.write(checks.KEYBOARD_SETTINGS, "val row = KeyboardBinding(label, \"F1\")\n")
        self.assertEqual(self._findings("shortcut-settings"), [])

    def test_a_version_name_without_a_code_is_blocked(self):
        self.box.write("gradle.properties", "skerry.versionName=0.4.1\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_a_version_name_with_a_code_is_fine(self):
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_version_code_alone_is_fine(self):
        # Seeding the code without touching the name is the safe direction — the rule is about a
        # release that moves the name and leaves the code behind, not the reverse.
        self.box.write("gradle.properties", "skerry.versionCode=116\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_version_code_that_does_not_grow_is_blocked(self):
        self._seed_version("0.4.0", 200)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)
        # The message proves which branch fired: a code equal to the base is absent from the diff
        # entirely, so counting findings cannot tell the comparison from the missing-code rule.
        self.assertIn("is not above 200", found[0].message)

    def test_a_version_code_that_grows_is_fine(self):
        self._seed_version("0.4.0", 116)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_code_a_sibling_branch_already_took_is_blocked(self):
        # main moved to 117 after this branch forked, so 117 is still an addition against the fork
        # point — only a comparison against main's tip can see that it is already spent.
        self._seed_version("0.4.1", 117, merge_back=False)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)
        self.assertIn("is not above 117", found[0].message)

    def test_a_baseline_without_a_code_is_reported_not_ignored(self):
        # The file exists but carries no code line: a real previous value may have been there and
        # the rule stopped comparing, so it says so instead of passing in silence.
        self._seed_version("0.4.0", None)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        # BLOCK, not WARN: a warning is print-only here — `checks.main` sets its exit code from the
        # blocking findings alone, so "could not compare" would commit exactly like "compared fine".
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_an_allow_comment_silences_the_version_rule(self):
        self._seed_version("0.4.0", 200)
        self.box.write("gradle.properties",
                       "# harness-allow: version-bump\n"
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_prose_about_the_hatch_does_not_invoke_it(self):
        # The file's own comment block documents this rule; a reworded paragraph must not disarm it.
        self._seed_version("0.4.0", 200)
        self.box.write("gradle.properties",
                       "# the version-bump check takes a harness-allow: version-bump line\n"
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        self.assertEqual(len(self._findings("version-bump")), 1)

    def test_a_stale_baseline_cannot_lower_the_bar(self):
        # main regressed below the branch point — a rewritten history, or a ref never pulled. The
        # highest code any baseline knows is the one to beat, so the lower ref buys nothing.
        self._seed_version("0.4.0", 200)
        run_git(self.box.path, "checkout", "-q", "main")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.0\nskerry.versionCode=100\n")
        self.box.commit("main regresses")
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=150\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertIn("is not above 200", found[0].message)

    def test_a_tree_with_no_baseline_at_all_is_reported(self):
        # Outside a repository both git calls fail, exactly as they do on an unborn HEAD — where the
        # old code read `git show :gradle.properties`, which is the *index*, and compared the value
        # being committed with itself.
        previous, problem = checks._previous_version_code(tempfile.gettempdir(), "")
        self.assertIsNone(previous)
        self.assertTrue(problem)

    def test_an_unrelated_ref_cannot_raise_the_bar(self):
        # A leftover origin/main from before a history rewrite: it resolves, and it carries a high
        # code that belongs to a lineage this branch is not on. Taking the maximum unfiltered would
        # let it block every legitimate bump until the number climbed past an abandoned one.
        self._seed_version("0.4.0", 116)
        run_git(self.box.path, "checkout", "-q", "--orphan", "abandoned")
        self.box.write("gradle.properties",
                       "skerry.versionName=9.0.0\nskerry.versionCode=9000\n")
        self.box.commit("abandoned lineage")
        head = run_git(self.box.path, "rev-parse", "HEAD").strip()
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        run_git(self.box.path, "update-ref", "refs/remotes/origin/main", head)
        # The ref has to actually exist, or the test passes on a baseline that was never consulted.
        self.assertEqual(
            run_git(self.box.path, "rev-parse", "--verify", "--quiet", "origin/main^{commit}").strip(),
            head)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_baseline_whose_code_line_does_not_parse_is_blocked(self):
        # The regex is the only reader of that line. A reformat at the baseline — a trailing
        # comment, a quoted value — is not "no previous code", it is a comparison that cannot be
        # made, and this rule is not allowed to fall silent on one.
        run_git(self.box.path, "checkout", "-q", "main")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.0\nskerry.versionCode=116 # bumped for 0.4.0\n")
        self.box.commit("seed an unreadable code")
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        run_git(self.box.path, "merge", "-q", "main")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)
        # Named once, not once per candidate ref: without dedup on the commit the same tree renders
        # as "at main, <sha>" and reads as two independent baselines failing.
        self.assertIn("gradle.properties at main carries no skerry.versionCode", found[0].message)

    def _seed_version(self, name: str, code: int | None, merge_back: bool = True) -> None:
        """Put a version on main, and by default on this branch's point as well.

        `merge_back=False` leaves main ahead: that is the sibling-branch case, where the code is
        already spent on main but still reads as an addition against the fork point.
        """
        body = f"skerry.versionName={name}\n" + (f"skerry.versionCode={code}\n" if code else "")
        run_git(self.box.path, "checkout", "-q", "main")
        self.box.write("gradle.properties", body)
        self.box.commit(f"seed {name}")
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        if merge_back:
            run_git(self.box.path, "merge", "-q", "main")

    def test_a_feature_without_tests_is_blocked(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        task = {"kind": "feature", "areas": ["shared"], "paths": [], "code_paths": []}
        found = [f for f in checks.run(self.cwd, task) if f.rule == "tests-present"]
        self.assertEqual(len(found), 1)

    def test_a_feature_with_tests_is_fine(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.write("shared/src/commonTest/kotlin/ATest.kt", "// covers it\n")
        task = {"kind": "feature", "areas": ["shared"], "paths": [], "code_paths": []}
        self.assertEqual([f for f in checks.run(self.cwd, task) if f.rule == "tests-present"], [])

    def test_a_harness_feature_owes_a_test_too(self):
        # The rule that gates every other change is the one place an untested rule costs most.
        self.box.branch("feat/gate-tweak")
        self.box.write("tools/harness/policy.py", "# a new rule\n")
        task = {"kind": "feature", "areas": ["harness"], "paths": [], "code_paths": []}
        found = [f for f in checks.run(self.cwd, task) if f.rule == "tests-present"]
        self.assertEqual(len(found), 1)

    def test_a_harness_feature_with_its_suite_touched_is_fine(self):
        self.box.branch("feat/gate-tweak")
        self.box.write("tools/harness/policy.py", "# a new rule\n")
        self.box.write("tools/harness/selftest.py", "# and the case that proves it\n")
        task = {"kind": "feature", "areas": ["harness"], "paths": [], "code_paths": []}
        self.assertEqual([f for f in checks.run(self.cwd, task) if f.rule == "tests-present"], [])

    def test_a_refactor_may_leave_tests_alone(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(self._findings("tests-present"), [])

    def test_committed_lines_still_count_as_added(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "fun a() { Text(\"x\") }\n")
        self.box.commit("wip")
        self.assertEqual(len(self._findings("design-primitives")), 1,
                         "the range is main...HEAD plus the worktree, not the worktree alone")

    def test_untouched_legacy_is_not_the_branch_debt(self):
        self.box.write("composeApp/src/commonMain/kotlin/Legacy.kt", "fun a() { Text(\"old\") }\n")
        self.box.commit("legacy on main")
        run_git(self.cwd, "checkout", "-q", "main")
        self.box.branch("refactor/other")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(self._findings("design-primitives"), [],
                         "rules apply to what this branch adds, not to what it inherited")

    def test_i18n_gap_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        self.box.write(f"{base}/values/strings.xml",
                       '<resources><string name="a">A</string></resources>')
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><string name="a">А</string></resources>')
        self.box.write(f"{base}/values-zh/strings.xml", "<resources></resources>")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1)
        self.assertIn("values-zh", found[0].message)

    def test_i18n_complete_is_clean(self):
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-zh"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        self.assertEqual(self._findings("i18n-parity"), [])

    def test_i18n_gap_in_plurals_and_arrays_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        def both(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d files</item>' for c in categories)
            return (f'<resources><plurals name="p">{items}</plurals>'
                    '<string-array name="a"><item>Jan</item></string-array></resources>')
        self.box.write(f"{base}/values/strings.xml", both("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", both("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", "<resources></resources>")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 2, "a plural and an array ship in three languages like a string")
        self.assertTrue(all("values-zh" in f.message for f in found))

    def test_a_plural_does_not_define_a_string_of_the_same_name(self):
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return f'<resources><plurals name="count">{items}</plurals></resources>'
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", body("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val t = stringResource(Res.string.count)\n")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "the namespaces are separate — a plural is not a string")
        self.assertIn("Res.string.count", found[0].message)

    def test_undefined_string_key_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-zh"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val t = stringResource(Res.string.missing_key)\n")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1)
        self.assertIn("missing_key", found[0].message)


    def test_a_string_and_a_plural_of_one_name_are_two_keys(self):
        base = "composeApp/src/commonMain/composeResources"
        self.box.write(f"{base}/values/strings.xml",
                       '<resources><string name="a">A</string></resources>')
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><plurals name="a">'
                       '<item quantity="one">А</item><item quantity="few">А</item>'
                       '<item quantity="many">А</item><item quantity="other">А</item>'
                       '</plurals></resources>')
        self.box.write(f"{base}/values-zh/strings.xml",
                       '<resources><string name="a">A</string></resources>')
        messages = [f.message for f in self._findings("i18n-parity")]
        self.assertEqual(len(messages), 2, "a plural does not translate a string of the same name")
        self.assertTrue(any(m.startswith("`a` has no values-ru") for m in messages), messages)
        self.assertTrue(any(m.startswith("`plurals a` exists only in values-ru") for m in messages), messages)

    def test_a_plural_missing_a_category_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        full = ('<item quantity="one">A</item><item quantity="few">A</item>'
                '<item quantity="many">A</item><item quantity="other">A</item>')
        self.box.write(f"{base}/values/strings.xml",
                       f'<resources><plurals name="n">{full}</plurals></resources>')
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><plurals name="n"><item quantity="other">A</item></plurals></resources>')
        self.box.write(f"{base}/values-zh/strings.xml",
                       '<resources><plurals name="n"><item quantity="other">A</item></plurals></resources>')
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "Chinese needs `other` alone; Russian needs one/few/many too")
        self.assertIn("one, few, many", found[0].message)

    def test_undefined_plural_and_array_keys_are_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return (f'<resources><plurals name="here">{items}</plurals>'
                    '<string-array name="also"><item>Jan</item></string-array></resources>')
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", body("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val a = pluralStringResource(Res.plurals.here, 1)\n"
                       "val b = stringArrayResource(Res.array.also)\n"
                       "val c = pluralStringResource(Res.plurals.gone, 1)\n"
                       "val d = stringArrayResource(Res.array.vanished)\n")
        messages = [f.message for f in self._findings("i18n-parity")]
        self.assertEqual(len(messages), 2, "the two defined accessors resolve, the two stale ones do not")
        self.assertIn("`Res.plurals.gone` is used but defined nowhere.", messages)
        self.assertIn("`Res.array.vanished` is used but defined nowhere.", messages)

    def test_an_attribute_before_the_name_does_not_exempt_a_string(self):
        base = "composeApp/src/commonMain/composeResources"
        self.box.write(f"{base}/values/strings.xml",
                       '<resources><string translatable="false" name="a">A</string></resources>')
        for locale in ("values-ru", "values-zh"):
            self.box.write(f"{base}/{locale}/strings.xml", "<resources></resources>")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 2, "a key the pattern misses is silently exempt from parity")

    def test_an_unreadable_resource_file_says_so(self):
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        os.makedirs(os.path.join(self.cwd, base, "values-zh/strings.xml"))
        found = self._findings("i18n-parity")
        warnings = [f for f in found if f.severity == checks.WARN]
        self.assertEqual(len(warnings), 1, "an incomplete sweep has to say it was incomplete")
        # Every locale is walked twice — once for names, once for plural categories. An uncached
        # read counted the same broken file once per walk and sent the reader hunting a second one.
        self.assertIn("1 resource file(s) could not be read", warnings[0].message)

    def test_a_resource_file_that_is_not_utf8_says_so(self):
        # The likeliest way a locale file becomes unreadable is an editor saving it in cp1251, and
        # that raises UnicodeDecodeError, not OSError — the warning built for it has to fire.
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        path = os.path.join(self.cwd, base, "values-zh", "strings.xml")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write('<resources><string name="a">Файл</string></resources>'.encode("cp1251"))
        warnings = [f for f in self._findings("i18n-parity") if f.severity == checks.WARN]
        self.assertEqual(len(warnings), 1, "a file the sweep cannot decode is not a clean sweep")

    def test_a_source_file_that_cannot_be_read_says_so(self):
        # An unreadable source hides a `Res.` usage, not a translation — a different warning from
        # the locale one, and the two counters must not be folded together.
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-zh"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        path = os.path.join(self.cwd, "composeApp/src/commonMain/kotlin/S.kt")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write("val a = Res.string.a // файл\n".encode("cp1251"))
        warnings = [f for f in self._findings("i18n-parity") if f.severity == checks.WARN]
        self.assertEqual(len(warnings), 1)
        self.assertIn("source file(s) could not be read", warnings[0].message)

    def test_an_empty_plural_form_is_not_a_form(self):
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return f'<resources><plurals name="n">{items}</plurals></resources>'
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><plurals name="n">'
                       '<item quantity="one">%1$d файл</item><item quantity="few"></item>'
                       '<item quantity="many">%1$d файлов</item>'
                       '<item quantity="other">%1$d файла</item></plurals></resources>')
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "an item with no text draws a blank where the number goes")
        self.assertIn("few", found[0].message)


class TestReviewerScope(SandboxCase):
    """A reviewer is owed again only when its own scope moved.

    The whole-tree comparison this replaces made every fix owe every reviewer, so a branch with
    ten review findings ran the full fan-out ten times over the same unchanged code.
    """

    def _reviewed(self, task: dict) -> None:
        st = state.load(self.cwd)
        reviews = st.setdefault("reviews", {})
        for name in policy.required_reviewers(task, self.cwd):
            entries = policy.reviewer_entries(name, self.cwd)
            reviews[name] = {"digest": state.digest_of(entries), "files": entries,
                             "branch": state.current_branch(self.cwd)}
            state.save_review_report(name, f"# {name}\n\nfindings from a pass that really ran",
                                     self.cwd)
        state.save(st, self.cwd)

    def _branch_with_ui_and_vault(self) -> dict:
        self.box.branch("feat/thing")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 1\n")
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "val v = 1\n")
        task = policy.classify(self.cwd)
        self._reviewed(task)
        return task

    def test_a_reviewed_branch_owes_nobody(self):
        task = self._branch_with_ui_and_vault()
        self.assertEqual(policy.missing_reviewers(state.load(self.cwd), task, self.cwd), [])

    def test_a_ui_fix_does_not_reopen_the_security_review(self):
        self._branch_with_ui_and_vault()
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertNotIn("skerry-security-reviewer", missing)
        self.assertIn("skerry-reviewer", missing)

    def test_a_vault_fix_does_reopen_it(self):
        self._branch_with_ui_and_vault()
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "val v = 2\n")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertIn("skerry-security-reviewer", missing)

    def test_a_reviewer_with_nothing_in_its_scope_is_not_owed(self):
        # A reviewer whose scope the change does not touch has nothing to read. Demanding it
        # anyway is what made a harness-only edit cost a full fan-out.
        self.box.branch("refactor/harness")
        self.box.write("tools/harness/policy.py", "# edited\n")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertNotIn("skerry-security-reviewer", missing)
        self.assertNotIn("skerry-kotlin-reviewer", missing)
        self.assertIn("skerry-reviewer", missing, "the whole-change passes always have something")

    ROUND = ("## Findings\n\n- `VaultStore.unlock` logs the unwrapped key at INFO in round {n}, "
             "so a support bundle carries it off the machine. Nothing else in the diff adds a "
             "primitive, drops a parity case, swallows a cancellation or ships a shortcut "
             "without its Settings row.\n")

    def _vault_branch(self) -> None:
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "val v = 1\n")

    def _two_rounds(self) -> None:
        for n in (1, 2):
            self.assertEqual(
                policy.record_review("skerry-security-reviewer", self.ROUND.format(n=n), self.cwd),
                "", f"round {n}")
            self.box.write("shared/src/commonMain/kotlin/vault/V.kt", f"val v = {n + 1}\n")

    def test_a_reviewer_is_owed_at_most_twice_on_one_branch(self):
        # Fixing what a reviewer found moves the files it read, which owes it again — and its next
        # findings are about that fix. Inside its own scope the loop does not converge: eleven
        # rounds on one branch, each one re-reading the diff. Two passes are the gate's; a third
        # is the operator's call.
        self._vault_branch()
        self._two_rounds()
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertNotIn("skerry-security-reviewer", missing)

    def test_the_cap_starts_over_on_the_next_branch(self):
        self._vault_branch()
        self._two_rounds()
        run_git(self.cwd, "add", "-A")
        run_git(self.cwd, "commit", "-q", "-m", "vault")
        run_git(self.cwd, "checkout", "-q", "main")
        self.box.branch("feat/other")
        self.box.write("shared/src/commonMain/kotlin/vault/W.kt", "val w = 1\n")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertIn("skerry-security-reviewer", missing)

    def test_what_the_cap_let_through_is_said_out_loud(self):
        # The gate stops demanding, it does not pretend the code was read. Silence here would be
        # the same defect as recording a reviewer on its launch.
        self._vault_branch()
        self._two_rounds()
        status = self.box.gate("status")
        self.assertIn("unreviewed", status.stdout)
        self.assertIn("skerry-security-reviewer", status.stdout)
        self.assertIn("shared/src/commonMain/kotlin/vault/V.kt", status.stdout)

    def test_a_review_does_not_follow_the_worktree_onto_the_next_branch(self):
        # RED records are scoped to their branch; review records were not, so a resources-only
        # branch inherited the pass made on the previous one — the files had not moved.
        self._branch_with_ui_and_vault()
        self.assertEqual(policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                                  self.cwd), [])
        run_git(self.cwd, "checkout", "-q", "-b", "feat/next")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertIn("skerry-reviewer", missing)
        self.assertEqual(policy.reviewer_delta(state.load(self.cwd), "skerry-reviewer", self.cwd),
                         [], "a pass made elsewhere is not a delta to re-read")

    def test_a_ui_fix_does_not_reopen_the_server_review(self):
        self.box.branch("feat/thing")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 1\n")
        self.box.write("server/src/main/kotlin/S.kt", "val s = 1\n")
        task = policy.classify(self.cwd)
        self._reviewed(task)
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertNotIn("java-reviewer", missing)

    def test_the_whole_change_reviewers_see_every_edit(self):
        # Parity, i18n and coverage are properties of the change, not of one directory: those two
        # passes stay global on purpose, and scoping must not quietly narrow them.
        self._branch_with_ui_and_vault()
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertIn("skerry-reviewer", missing)
        self.assertIn("pr-test-analyzer", missing)

    def test_the_delta_names_only_what_moved(self):
        self._branch_with_ui_and_vault()
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        delta = policy.reviewer_delta(state.load(self.cwd), "skerry-reviewer", self.cwd)
        self.assertEqual(delta, ["composeApp/src/commonMain/kotlin/S.kt"])

    def test_a_reviewer_that_never_ran_has_no_delta(self):
        self.box.branch("feat/thing")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 1\n")
        self.assertEqual(policy.reviewer_delta(state.load(self.cwd), "skerry-reviewer", self.cwd),
                         [])

    def test_a_deleted_file_is_part_of_the_delta(self):
        self._branch_with_ui_and_vault()
        os.remove(os.path.join(self.cwd, "composeApp/src/commonMain/kotlin/S.kt"))
        delta = policy.reviewer_delta(state.load(self.cwd), "skerry-reviewer", self.cwd)
        self.assertIn("composeApp/src/commonMain/kotlin/S.kt", delta)


class TestReviewReports(SandboxCase):
    """Findings on disk — the one thing a context compaction must not be able to lose."""

    def test_findings_are_written_where_the_next_round_can_read_them(self):
        path = state.save_review_report("skerry-reviewer", "FINDING: vault key logged", self.cwd)
        self.assertTrue(path and os.path.exists(path))
        with open(path, encoding="utf-8") as fh:
            body = fh.read()
        self.assertIn("FINDING: vault key logged", body)
        self.assertIn("skerry-reviewer", body)

    REPORT = ("## Findings\n\n- `VaultStore.unlock` logs the unwrapped key at INFO, so a support "
              "bundle carries it off the machine. The rest of the diff is clean: no new "
              "primitive, no parity gap, no swallowed cancellation, no shortcut without its "
              "Settings row.\n")

    def _branch_with_ui(self) -> None:
        self.box.branch("feat/thing")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 1\n")

    def test_the_recorder_refuses_a_launch_that_reviewed_nothing(self):
        # The bug this whole path exists for lived in the hook, not in policy: it recorded on the
        # Agent tool returning, and a backgrounded fan-out returns before reading anything.
        self._branch_with_ui()
        done = self.box.recorder("skerry-reviewer",
                                 "Async agent launched successfully.\nagentId: abc123\n"
                                 "The agent is working in the background.")
        st = state.load(self.cwd)
        self.assertNotIn("skerry-reviewer", st.get("reviews") or {})
        self.assertIn("skerry-reviewer", st.get("pending_reviews") or {})
        self.assertIn("not recorded", done.stdout)

    def test_a_report_arriving_after_the_code_moved_is_refused(self):
        self._branch_with_ui()
        self.box.recorder("skerry-reviewer", "Async agent launched successfully. agentId: abc")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        self.assertEqual(policy.review_drift("skerry-reviewer", self.cwd),
                         ["composeApp/src/commonMain/kotlin/S.kt"])
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "moved",
                         "a reviewer cannot vouch for code it never read")

    def test_a_refused_report_leaves_the_drift_snapshot_where_it_was(self):
        # The refusal used to re-snapshot the scope on its way out, so the retry it recommends
        # found no drift and recorded the stale report green — the guard cancelled itself.
        self._branch_with_ui()
        self.box.recorder("skerry-reviewer", "Async agent launched successfully. agentId: abc")
        before = (state.load(self.cwd)["pending_reviews"]["skerry-reviewer"])["files"]
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        refused = self.box.recorder("skerry-reviewer", self.REPORT)
        self.assertIn("moved", refused.stdout)
        after = (state.load(self.cwd)["pending_reviews"]["skerry-reviewer"])["files"]
        self.assertEqual(before, after, "the snapshot is the evidence — a refusal must not reset it")
        self.assertEqual(self.box.gate("review", "skerry-reviewer", stdin=self.REPORT).returncode,
                         2, "the retry the refusal advertises cannot be the way around it")

    def test_a_stored_report_cannot_be_recorded_twice(self):
        self._branch_with_ui()
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")
        stored = policy.stored_report("skerry-reviewer", self.cwd)
        self.assertEqual(policy.record_review("skerry-reviewer", stored, self.cwd), "recycled",
                         "re-feeding the file `gate.py reviewers` prints re-dates a stale pass")

    def test_the_file_the_gate_prints_cannot_be_handed_back_as_a_pass(self):
        # `gate.py reviewers` prints the previous findings' path right under MISSING. Once the
        # scope moves, the stored round is no longer a replay — `_already_recorded` is false — so
        # only where the text came from can tell an honest repeat verdict from a recycled file.
        self._branch_with_ui()
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        report = state.review_report_path("skerry-reviewer", self.cwd)
        done = self.box.gate("review", "skerry-reviewer", "--file", report)
        self.assertEqual(done.returncode, 2, done.stdout)
        self.assertIn("skerry-reviewer",
                      policy.missing_reviewers(state.load(self.cwd),
                                               policy.classify(self.cwd), self.cwd))

    def test_the_cli_lets_the_next_report_through_like_the_hook_does(self):
        # The CLI refused on drift before `record_review` could see the report, so the retry the
        # refusal advertises was refused too — the two paths disagreed about the same tree.
        self._branch_with_ui()
        self.box.recorder("skerry-reviewer", "Async agent launched successfully. agentId: abc")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        stale = self.box.gate("review", "skerry-reviewer", stdin=self.REPORT)
        self.assertEqual(stale.returncode, 2, stale.stdout)
        self.assertIn("moved", stale.stdout)
        fresh = self.box.gate("review", "skerry-reviewer",
                              stdin=self.REPORT.replace("INFO", "DEBUG"))
        self.assertEqual(fresh.returncode, 0, fresh.stdout)

    def test_a_spent_refusal_re_arms_the_snapshot_instead_of_dropping_it(self):
        # Dropping it left every later move for that reviewer unmeasured: a report refused for
        # some other reason after the discharge, then a stale one, and nothing checks the tree.
        self._branch_with_ui()
        self.box.recorder("skerry-reviewer", "Async agent launched successfully. agentId: abc")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        self.assertTrue(policy.moved_under_the_reviewer("skerry-reviewer", self.REPORT, self.cwd))
        second = self.REPORT.replace("INFO", "DEBUG")
        self.assertFalse(policy.moved_under_the_reviewer("skerry-reviewer", second, self.cwd))
        pending = (state.load(self.cwd).get("pending_reviews") or {}).get("skerry-reviewer")
        self.assertIsNotNone(pending, "the snapshot is what measures the next report")
        self.assertNotIn("refused", pending)
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 3\n")
        self.assertEqual(policy.review_drift("skerry-reviewer", self.cwd),
                         ["composeApp/src/commonMain/kotlin/S.kt"])

    def test_one_reviewers_report_does_not_record_another(self):
        self._branch_with_ui()
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")
        stored = policy.stored_report("skerry-reviewer", self.cwd)
        self.assertEqual(policy.record_review("skerry-security-reviewer", stored, self.cwd),
                         "recycled")

    def test_a_record_that_never_reached_the_disk_is_not_success(self):
        self._branch_with_ui()
        original = state.save
        state.save = lambda *args, **kwargs: False
        self.addCleanup(lambda: setattr(state, "save", original))
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd),
                         "not saved")

    def test_findings_that_cannot_be_written_leave_the_reviewer_owed(self):
        # State first, report second left the entry behind when the report write failed: the
        # structural check then read the *previous* round's file and called the reviewer done.
        self._branch_with_ui()
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        original = state.save_review_report
        state.save_review_report = lambda *args, **kwargs: ""
        self.addCleanup(lambda: setattr(state, "save_review_report", original))
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT + "second round\n",
                                              self.cwd), "not saved")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertIn("skerry-reviewer", missing,
                      "last round's file on disk is not this round's review")

    def test_a_repeat_verdict_on_moved_code_is_a_new_pass(self):
        # Reports are appended, so a reviewer whose second round reads the same ("checked and
        # clean") was refused as recycled and could not close the gate without being reworded.
        self._branch_with_ui()
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "",
                         "the same verdict about different code is a different pass")

    def test_a_fresh_report_after_a_drift_refusal_is_recorded(self):
        # The snapshot refuses the report it was taken against. Re-running the reviewer inline
        # produces a different text and no new launch, so nothing refreshed the snapshot and the
        # reviewer could never be recorded again.
        self._branch_with_ui()
        self.box.recorder("skerry-reviewer", "Async agent launched successfully. agentId: abc")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "moved")
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "moved",
                         "the report that was refused stays refused")
        rerun = self.REPORT + "\nRe-read after the fix: the unwrapped key is no longer logged.\n"
        self.assertEqual(policy.record_review("skerry-reviewer", rerun, self.cwd), "")

    def test_a_report_stored_on_another_branch_cannot_close_this_one(self):
        # The findings file is per-reviewer and spans branches, so on a fresh branch the reviewer
        # has no entry — and the previous branch's report was accepted as this branch's pass.
        self._branch_with_ui()
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")
        stored = policy.stored_report("skerry-reviewer", self.cwd)
        run_git(self.cwd, "checkout", "-q", "-b", "feat/next")
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "val v = 1\n")
        self.assertEqual(policy.record_review("skerry-reviewer", stored, self.cwd), "recycled")

    def test_a_report_kept_after_a_failed_state_write_can_still_be_recorded(self):
        # The report is appended before the entry, so a state write that fails once left the
        # findings on disk — and the retry was then refused as a recycled report, forever.
        self._branch_with_ui()
        original = state.save
        state.save = lambda *args, **kwargs: False
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd),
                         "not saved")
        state.save = original
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "",
                         "the file on disk is this round's own report, not a previous pass")

    def test_the_stored_report_is_readable_only_by_its_operator(self):
        # It is a verbatim copy of whatever path the operator named — a mistyped `--file` can put
        # a private key in there, and .git is never cleaned.
        path = state.save_review_report("skerry-reviewer", self.REPORT, self.cwd)
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(os.path.dirname(path)).st_mode & 0o777, 0o700)

    def test_a_report_cannot_carry_an_escape_sequence_into_the_terminal(self):
        # Reports quote what the reviewer read — terminal fixtures, SFTP names, an AI transcript.
        # `cat`-ing the file executes the escape, and the next round replays it into a context.
        path = state.save_review_report(
            "skerry-reviewer", f"{self.REPORT}\x1b]0;pwned\x07 and {chr(0x202E)}drop\n", self.cwd)
        with open(path, encoding="utf-8") as fh:
            body = fh.read()
        self.assertNotIn("\x1b", body)
        self.assertNotIn(chr(0x202E), body)
        self.assertIn("pwned", body, "the text is kept, only the bytes that act are not")

    def test_a_runaway_report_is_capped(self):
        path = state.save_review_report("skerry-reviewer", "x" * 400_000, self.cwd)
        self.assertLess(os.path.getsize(path), state.REPORT_CHARS + 500)

    def test_each_round_is_kept_rather_than_overwritten(self):
        # Findings live on disk so a compaction cannot lose what is still owed; a second round
        # that does not repeat a first-round item used to erase it.
        self._branch_with_ui()
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        second = self.REPORT.replace("VaultStore.unlock", "SnippetStore.render")
        self.assertEqual(policy.record_review("skerry-reviewer", second, self.cwd), "")
        stored = policy.stored_report("skerry-reviewer", self.cwd)
        self.assertIn("VaultStore.unlock", stored)
        self.assertIn("SnippetStore.render", stored)

    def test_findings_are_found_whichever_key_the_runtime_wraps_them_in(self):
        # A response shaped {"output": …} or {"result": …} is plausible for another agent runtime;
        # flattening it to "" would report "no findings" and snapshot a launch that never happened.
        for key in ("content", "text", "output", "result"):
            self.assertEqual(policy.review_findings({key: self.REPORT}), self.REPORT.strip(), key)

    def test_a_response_nested_beyond_reason_is_not_a_recursion_error(self):
        response = {"content": "the vault key is logged at INFO in VaultStore.unlock"}
        for _ in range(4_000):
            response = {"content": [response]}
        self.assertEqual(policy.review_findings(response), "",
                         "a response the walker gives up on carries no findings — it does not "
                         "take the hook down with it")

    def test_a_launch_acknowledgement_long_enough_to_pass_the_floor_is_still_not_a_review(self):
        # The length floor alone catches the acknowledgement this harness emits today; the denylist
        # is there for a wordier one, and nothing proved it discriminated at all.
        stub = ("Async agent launched successfully. agentId: aae1b895f4e4935a3. The agent is "
                "working in the background and you will be notified when it completes. Do not "
                "duplicate its work — avoid touching the same files while it runs. Its transcript "
                "is written to the task output file named above.")
        self.assertGreater(len(stub), policy.MIN_REPORT_CHARS)
        self.assertEqual(policy.review_findings(stub), "")

    def test_a_report_that_quotes_the_launch_wording_is_still_a_report(self):
        report = self.REPORT + ("\n\nThe hook's note says the agent `is working in the background` "
                                "and prints its agentId: that string appears in this report because "
                                "the report is about the hook, and a reviewer quoting the wording it "
                                "reviews must not be mistaken for the wording itself. This paragraph "
                                "exists to carry the pass over the stub length.\n")
        self.assertGreater(len(report), policy.STUB_CHARS)
        self.assertEqual(policy.review_findings(report), report.strip())

    def test_the_review_command_reads_the_report_from_the_file_it_is_given(self):
        # `--file` is the form the hook's own note tells the operator to use, and it was the one
        # path through the command that no test ever took.
        self._branch_with_ui()
        path = os.path.join(self.cwd, "report.md")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(self.REPORT)
        done = self.box.gate("review", "skerry-reviewer", "--file", path)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("VaultStore.unlock", policy.stored_report("skerry-reviewer", self.cwd))

    def test_a_report_file_it_cannot_read_is_not_a_recorded_review(self):
        self._branch_with_ui()
        done = self.box.gate("review", "skerry-reviewer", "--file",
                             os.path.join(self.cwd, "nothing-here.md"))
        self.assertEqual(done.returncode, 2)
        self.assertIn("cannot read the report", done.stdout)

    def test_a_reviews_directory_that_already_exists_is_tightened(self):
        # makedirs(mode=) and os.open(mode=) both ignore the mode when the target already exists,
        # so the hardening reached new state directories only — every report written before it
        # stayed world-readable, and so did the directory holding them.
        path = state.review_report_path("skerry-reviewer", self.cwd)
        os.makedirs(os.path.dirname(path), mode=0o755, exist_ok=True)
        os.chmod(os.path.dirname(path), 0o755)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("# skerry-reviewer\n\nan earlier round, written before the hardening\n")
        os.chmod(path, 0o644)
        state.save_review_report("skerry-reviewer", self.REPORT, self.cwd)
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(os.path.dirname(path)).st_mode & 0o777, 0o700)

    def test_a_snapshot_from_another_branch_does_not_refuse_this_branchs_report(self):
        # The drift snapshot outlived the branch it was taken on, so the first report on the next
        # branch was refused as "moved" — every file differs across a branch switch.
        self._branch_with_ui()
        self.box.recorder("skerry-reviewer", "Async agent launched successfully. agentId: abc")
        run_git(self.cwd, "checkout", "-q", "-b", "feat/next")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        self.assertEqual(policy.review_drift("skerry-reviewer", self.cwd), [])
        self.assertEqual(policy.record_review("skerry-reviewer", self.REPORT, self.cwd), "")

    def test_a_payload_field_of_the_wrong_type_does_not_break_the_session(self):
        done = self.box.raw_hook({"tool_name": "Agent", "tool_input": {"subagent_type": 123}})
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertNotIn("Traceback", done.stderr)

    def test_a_payload_the_hook_cannot_read_does_not_break_the_session(self):
        # Every other I/O path in the harness is wrapped; a hook that raises turns a bad payload
        # into a traceback on every Agent call.
        done = self.box.raw_hook({"tool_name": "Agent", "tool_input": "not a dict"})
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertNotIn("Traceback", done.stderr)

    def test_an_entry_with_no_findings_on_disk_is_still_owed(self):
        self._branch_with_ui()
        st = state.load(self.cwd)
        st["reviews"] = {"skerry-reviewer": {
            "digest": policy.reviewer_digest("skerry-reviewer", self.cwd),
            "branch": state.current_branch(self.cwd)}}
        state.save(st, self.cwd)
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertIn("skerry-reviewer", missing)

    def test_the_review_command_reports_what_it_refused(self):
        self._branch_with_ui()
        self.assertEqual(self.box.gate("review", "not-a-reviewer", stdin=self.REPORT).returncode, 2)
        stub = self.box.gate("review", "skerry-reviewer", stdin="ok")
        self.assertEqual(stub.returncode, 2)
        self.assertIn("no findings", stub.stdout)
        good = self.box.gate("review", "ecc:skerry-reviewer", stdin=self.REPORT)
        self.assertEqual(good.returncode, 0, good.stdout + good.stderr)
        self.assertIn("recorded", good.stdout)

    def test_the_review_command_names_the_files_to_re_read(self):
        self._branch_with_ui()
        self.box.recorder("skerry-reviewer", "Async agent launched successfully. agentId: abc")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 2\n")
        refused = self.box.gate("review", "skerry-reviewer", stdin=self.REPORT)
        self.assertEqual(refused.returncode, 2)
        self.assertIn("has since moved", refused.stdout)
        self.assertIn("composeApp/src/commonMain/kotlin/S.kt", refused.stdout)

    def test_a_state_directory_it_cannot_write_is_not_a_saved_state(self):
        self._branch_with_ui()
        directory = os.path.dirname(state.state_path(self.cwd))
        os.makedirs(directory, exist_ok=True)
        os.chmod(directory, 0o500)
        self.addCleanup(os.chmod, directory, 0o700)
        self.assertFalse(state.save({"stages": {}}, self.cwd),
                         "a write that raised is not a write that happened")

    def test_a_launch_acknowledgement_is_not_a_review(self):
        # The fan-out runs in the background, so the reply to the launch carries no findings. The
        # hook used to pin the tree to it, which made a green review gate mean "agents started".
        self.box.branch("feat/thing")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 1\n")
        stub = ("Async agent launched successfully.\nagentId: abc123\n"
                "The agent is working in the background.")
        self.assertEqual(policy.record_review("skerry-reviewer", stub, self.cwd), "no findings")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertIn("skerry-reviewer", missing)

    def test_findings_record_the_reviewer_against_the_tree_it_read(self):
        self.box.branch("feat/thing")
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "val a = 1\n")
        report = {"content": [{"text": "## Findings\n\n- the vault key is logged at INFO in "
                                       "VaultStore.unlock, so a support bundle carries it out of "
                                       "the machine. Everything else in the diff is clean: no new "
                                       "primitive, no parity gap, no swallowed cancellation.\n"}]}
        self.assertEqual(policy.record_review("skerry-reviewer", report, self.cwd), "")
        missing = policy.missing_reviewers(state.load(self.cwd), policy.classify(self.cwd),
                                           self.cwd)
        self.assertNotIn("skerry-reviewer", missing)
        with open(state.review_report_path("skerry-reviewer", self.cwd), encoding="utf-8") as fh:
            self.assertIn("the vault key is logged", fh.read())

    def test_an_empty_report_is_not_written(self):
        self.assertEqual(state.save_review_report("skerry-reviewer", "   ", self.cwd), "")

    def test_a_reviewer_name_cannot_escape_the_state_directory(self):
        path = state.review_report_path("../../etc/passwd", self.cwd)
        self.assertNotIn("..", path)


class TestHarnessRed(SandboxCase):
    """A bug in the gate has to be provable the same way a bug in the client is.

    Making `.py` files code closed a hole and opened this one: on a `fix/` branch touching nothing
    but the harness, the gate demanded a RED record that no Gradle task could produce.
    """

    STUB = ("import unittest\n\n\nclass StubHarness(unittest.TestCase):\n"
            "    def test_the_bug_is_reproduced(self):\n        {body}\n\n\n"
            "if __name__ == '__main__':\n    unittest.main()\n")

    def _suite(self, body: str) -> None:
        self.box.branch("fix/hook-refusal")
        self.box.write("tools/harness/selftest.py", self.STUB.format(body=body))

    def test_a_failing_harness_test_records_the_red_phase(self):
        self._suite("self.fail('the report is recorded on a launch')")
        done = self.box.gate("red", "--tests", "*the_bug_is_reproduced*",
                             "--file", "tools/harness/policy.py")
        self.assertEqual(done.returncode, 0, done.stdout)
        record = state.load(self.cwd)["red"][0]
        self.assertEqual(record["task"], "selftest")
        self.assertEqual(record["branch"], "fix/hook-refusal")

    def test_a_harness_test_that_passes_proves_nothing(self):
        self._suite("self.assertTrue(True)")
        done = self.box.gate("red", "--tests", "*the_bug_is_reproduced*",
                             "--file", ".claude/hooks/record-review.py")
        self.assertEqual(done.returncode, 1)
        self.assertIn("PASSED", done.stdout)

    def test_editing_only_the_suite_does_not_count_as_the_fix(self):
        # The suite has to sit outside the `src` digest, exactly as Kotlin test sources do —
        # otherwise the RED phase can be closed by weakening the test that proved the bug.
        self._suite("self.fail('the report is recorded on a launch')")
        self.assertTrue(state.is_test("tools/harness/selftest.py"))
        done = self.box.gate("red", "--tests", "*the_bug_is_reproduced*",
                             "--file", "tools/harness/policy.py")
        self.assertEqual(done.returncode, 0, done.stdout)
        self.box.write("tools/harness/selftest.py", self.STUB.format(body="self.assertTrue(True)"))
        _, debt = policy.gate_debt(self.cwd)
        self.assertTrue(any(item.startswith("red") for item in debt),
                        "a test-only edit is not a fix")

    def test_a_pattern_that_is_an_option_is_refused(self):
        # `--tests '*-v*'` strips to `-v`, which unittest's own argparse rejects with exit 2 — a
        # usage error was recorded as a test that failed.
        self._suite("self.fail('never reached')")
        done = self.box.gate("red", "--tests", "*-v*", "--file", "tools/harness/policy.py")
        self.assertEqual(done.returncode, 2, done.stdout)
        self.assertNotIn("red", state.load(self.cwd))

    def test_a_suite_that_cannot_run_is_not_a_failing_test(self):
        self.box.branch("fix/hook-refusal")
        self.box.write("tools/harness/selftest.py", "import nothing_that_exists\n")
        done = self.box.gate("red", "--tests", "*the_bug_is_reproduced*",
                             "--file", "tools/harness/policy.py")
        self.assertEqual(done.returncode, 2, done.stdout)
        self.assertNotIn("red", state.load(self.cwd))

    def test_a_pattern_that_matches_no_harness_test_is_refused(self):
        self._suite("self.fail('never reached')")
        done = self.box.gate("red", "--tests", "*NoSuchTest*",
                             "--file", "tools/harness/state.py")
        self.assertEqual(done.returncode, 2)
        self.assertIn("matched no test", done.stdout)
        self.assertNotIn("red", state.load(self.cwd))


class TestRedEvidence(unittest.TestCase):
    """A non-zero exit is not a failing test — in either runner."""

    def test_a_gradle_run_that_never_reached_a_test_is_not_red(self):
        compile_error = ("> Task :shared:compileKotlinJvm FAILED\n"
                         "e: file:///s/A.kt:3:1 expecting a top level declaration\n"
                         "FAILURE: Build failed with an exception.\n")
        self.assertFalse(gate.proves_a_failing_test(":shared:jvmTest", compile_error))
        failing = ("> Task :shared:jvmTest FAILED\n\nAT > a() FAILED\n\n"
                   "3 tests completed, 1 failed\n\nFAILURE: Build failed with an exception.\n"
                   "> There were failing tests. See the report at: file:///s/index.html\n")
        self.assertTrue(gate.proves_a_failing_test(":shared:jvmTest", failing))

    def test_the_harness_suite_keeps_its_own_evidence(self):
        self.assertTrue(gate.proves_a_failing_test(
            gate.HARNESS_SUITE, "Ran 3 tests in 1.0s\n\nFAILED (failures=1)\n"))
        self.assertFalse(gate.proves_a_failing_test(
            gate.HARNESS_SUITE, "ImportError: cannot import name policy\n"))


class TestStateFile(SandboxCase):
    def test_the_state_file_is_readable_only_by_its_operator(self):
        # It carries the branch, every path in the change and the reviewers' verdicts; on a shared
        # machine the default 0644 hands all of that to anyone with an account.
        state.save({"reviews": {}}, self.cwd)
        path = state.state_path(self.cwd)
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(os.path.dirname(path)).st_mode & 0o777, 0o700)

    def test_a_state_directory_that_already_exists_is_tightened(self):
        path = state.state_path(self.cwd)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        os.chmod(os.path.dirname(path), 0o755)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("{}")
        os.chmod(path, 0o644)
        state.save({"reviews": {}}, self.cwd)
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(os.path.dirname(path)).st_mode & 0o777, 0o700)


class TestSharedDefinitions(unittest.TestCase):
    def test_the_invisible_byte_list_has_one_definition(self):
        # One list keeps the byte out of source, the other strips it from a stored report before
        # the operator cats it. Two copies means a byte added to one is caught by neither.
        # Identity, not equality: `re.compile` caches by pattern string, so two independent
        # definitions of the same ranges hand back the same compiled object and an equality test
        # would pass while the duplication stood.
        self.assertIs(checks.CONTROL_RANGES, state.CONTROL_RANGES)


class TestStageRecording(SandboxCase):
    def test_a_recorded_stage_is_current_only_for_its_own_tree(self):
        self.box.branch("feat/thing")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        cwd = os.getcwd()
        os.chdir(self.cwd)
        try:
            state.record_stage("tests", True)
            digest = state.tree_digest("all")
            self.assertTrue(state.stage_is_current(state.load(), "tests", digest))
            self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 2\n")
            self.assertFalse(state.stage_is_current(state.load(), "tests",
                                                    state.tree_digest("all")))
        finally:
            os.chdir(cwd)


if __name__ == "__main__":
    started = time.time()
    result = unittest.main(exit=False, verbosity=2).result
    print(f"\n{result.testsRun} tests in {time.time() - started:.1f}s")
    sys.exit(0 if result.wasSuccessful() else 1)
