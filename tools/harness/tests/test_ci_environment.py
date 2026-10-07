"""Exercise the suite under the environment inherited from GitHub Actions."""
import os
import subprocess
import sys

from support import SandboxCase


class TestCIEnvironment(SandboxCase):
    def run_case_under_ci(self, pattern):
        result = subprocess.run([sys.executable, "tools/harness/selftest.py", "-k", pattern],
                                cwd=self.cwd, env=dict(os.environ, CI="true"),
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Ran 1 test", result.stderr)

    def test_outer_ci_keeps_docs_plan_empty(self):
        self.run_case_under_ci("test_docs_are_free_but_ci_is_executable_policy")

    def test_outer_ci_keeps_docs_skill_plan_empty(self):
        self.run_case_under_ci("test_docs_only_change_has_no_automatic_ecc_work")

    def test_explicit_main_ci_still_selects_full_tree(self):
        self.run_case_under_ci("test_main_ci_does_not_skip_build_on_an_empty_diff")
