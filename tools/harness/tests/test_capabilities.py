"""ECC routing is explicit, bounded and independent of plugin installation."""
import json
import subprocess
import sys
from pathlib import Path
from unittest.mock import patch

from harness import agents
from support import SandboxCase


class TestCapabilities(SandboxCase):
    def selection(self, **kwargs):
        from harness import capabilities
        return capabilities.plan(self.cwd, **kwargs)

    def names(self, plan):
        return {item["name"] for item in plan["skills"]}

    def test_harness_task_selects_harness_skill_without_kotlin_fanout(self):
        self.change("tools/harness/policy.py", "# change\n")
        self.assertEqual(self.names(self.selection()), {"ecc:agent-harness-construction"})
        self.assertEqual(self.selection()["specialists"], [])

    def test_kotlin_ui_selects_stack_and_accessibility(self):
        self.change("composeApp/src/commonMain/kotlin/ui/Form.kt")
        names = self.names(self.selection())
        self.assertTrue({"ecc:kotlin-patterns", "ecc:kotlin-testing",
                         "ecc:compose-multiplatform-patterns", "ecc:accessibility"} <= names)
        self.assertNotIn("ecc:kotlin-coroutines-flows", names)

    def test_semantic_focus_adds_coroutines_and_architecture_explicitly(self):
        self.change("shared/src/commonMain/kotlin/Controller.kt")
        selected = self.selection(focus=("coroutines", "architecture"))
        self.assertIn("ecc:kotlin-coroutines-flows", self.names(selected))
        self.assertEqual({s["name"] for s in selected["specialists"]},
                         {"ecc:code-explorer", "ecc:code-architect"})

    def test_sql_and_trust_boundaries_select_specialists(self):
        self.change("server/src/main/resources/db/migration/V2.sql")
        selected = self.selection()
        self.assertIn("ecc:security-review", self.names(selected))
        self.assertIn("ecc:database-migrations", self.names(selected))
        self.assertIn("ecc:database-reviewer", {s["name"] for s in selected["specialists"]})

    def test_plugin_absence_is_explicit_and_does_not_change_gate_reviewers(self):
        from harness import policy
        self.change("tools/harness/policy.py", "# change\n")
        with patch("harness.capabilities.plugin_root", return_value=None):
            selected = self.selection()
        self.assertEqual(selected["skills"][0]["status"], "unresolved")
        self.assertIsNone(selected["skills"][0]["path"])
        self.assertEqual(policy.reviewers(self.cwd), ["skerry-reviewer"])

    def test_resolved_sources_and_missing_files_are_distinguished(self):
        self.change("shared/src/commonMain/kotlin/A.kt")
        root = Path(self.cwd, ".git/ecc")
        source = root / "skills/kotlin-testing/SKILL.md"
        source.parent.mkdir(parents=True)
        source.write_text("# Installed test guidance\n")
        items = {i["name"]: i for i in self.selection(ecc_root=root)["skills"]}
        self.assertEqual(items["ecc:kotlin-testing"]["path"], str(source))
        self.assertEqual(items["ecc:kotlin-testing"]["status"], "available")
        self.assertEqual(items["ecc:kotlin-patterns"]["status"], "missing")

    def test_kotlin_reviewer_receives_only_stack_skills_and_load_instruction(self):
        self.change("composeApp/src/commonMain/kotlin/ui/Form.kt")
        selected = agents.profile("skerry-kotlin-reviewer", self.cwd, focus=("coroutines", "security"))
        self.assertIn("ecc:kotlin-coroutines-flows", self.names(selected["capabilities"]))
        self.assertNotIn("ecc:security-review", self.names(selected["capabilities"]))
        self.assertIn("Read", selected["capability_instruction"])
        self.assertEqual(selected["reasoning_effort"], "medium")

    def test_build_resolver_is_bounded_and_keeps_builds_in_parent(self):
        self.change("build.gradle.kts")
        selected = self.selection(focus=("build",))
        resolver = next(s for s in selected["specialists"] if s["name"] == "ecc:kotlin-build-resolver")
        self.assertEqual(resolver["profile"], "worker")
        self.assertIn("parent", resolver["constraints"])
        self.assertIn("read-only", resolver["constraints"])

    def test_cli_focus_and_source_options_are_propagated_into_review_dispatch(self):
        self.change("tools/harness/fixture.py", "# change\n")
        result = subprocess.run([sys.executable, "tools/harness/gate.py", "review-start",
                                 "skerry-reviewer", "--focus", "architecture", "--ecc-root",
                                 str(Path(self.cwd, ".git/ecc")), "--json"],
                                cwd=self.cwd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        dispatch = json.loads(result.stdout)["data"]["dispatch"]
        self.assertIn("architecture", dispatch["capabilities"]["focus"])
        self.assertTrue(all(i["status"] == "missing" for i in dispatch["capabilities"]["skills"]))

    def test_docs_only_change_has_no_automatic_ecc_work(self):
        self.change("docs/notes.md", "prose\n")
        self.assertEqual(self.selection()["skills"], [])
        self.assertEqual(self.selection()["specialists"], [])

    def test_invalid_focus_is_refused(self):
        self.change("README.md")
        with self.assertRaises(ValueError):
            self.selection(focus=("everything-at-max",))

    def test_terminal_recommends_read_only_performance_review(self):
        self.change("shared/src/commonMain/kotlin/terminal/Terminal.kt")
        selected = self.selection()
        self.assertIn("ecc:performance-optimizer", {s["name"] for s in selected["specialists"]})
        self.assertIn("ecc:security-review", self.names(selected))

    def test_multiple_cached_versions_require_explicit_selection(self):
        from harness import capabilities
        from unittest.mock import patch
        root = Path(self.cwd, ".git/codex")
        for version in ("1.0", "2.0"):
            (root / "plugins/cache/ecc/ecc" / version / "skills").mkdir(parents=True)
        with patch.dict("os.environ", {"CODEX_HOME": str(root), "ECC_PLUGIN_ROOT": ""}):
            self.assertIsNone(capabilities.plugin_root())
            selected = root / "plugins/cache/ecc/ecc/2.0"
            self.assertEqual(capabilities.plugin_root(selected), selected)
