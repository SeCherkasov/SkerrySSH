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

from harness import checks, policy, state  # noqa: E402


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
        shutil.copy2(os.path.join(REPO_ROOT, ".claude", "hooks", "guard-git.py"),
                     os.path.join(self.path, ".claude", "hooks", "guard-git.py"))
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

    def test_prose_and_harness_do_not(self):
        for path in ("README.md", "docs/coding-guidelines.md", ".claude/hooks/guard-git.py",
                     "tools/harness/gate.py", ".github/workflows/ci.yml",
                     "composeApp/build/generated/Thing.kt", "docs/img/x.png"):
            self.assertFalse(state.is_code(path), path)

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


class TestRequirements(unittest.TestCase):
    def test_docs_owes_nothing(self):
        self.assertEqual(policy.required_stages({"kind": "docs", "areas": []}), [])
        self.assertEqual(policy.required_reviewers({"kind": "docs", "areas": []}), [])

    def test_bug_owes_a_red_phase(self):
        stages = policy.required_stages({"kind": "bug", "areas": ["shared"]})
        self.assertIn("red", stages)
        self.assertNotIn("red", policy.required_stages({"kind": "feature", "areas": ["shared"]}))

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
        st["reviews"] = {name: {"digest": policy.reviewer_digest(name, self.cwd)}
                         for name in policy.required_reviewers(task, self.cwd)}
        state.save(st, self.cwd)

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
            "digest": policy.reviewer_digest("skerry-reviewer", self.cwd)}}
        state.save(st, self.cwd)
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
            reviews[name] = {"digest": state.digest_of(entries), "files": entries}
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

    def test_an_empty_report_is_not_written(self):
        self.assertEqual(state.save_review_report("skerry-reviewer", "   ", self.cwd), "")

    def test_a_reviewer_name_cannot_escape_the_state_directory(self):
        path = state.review_report_path("../../etc/passwd", self.cwd)
        self.assertNotIn("..", path)


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
