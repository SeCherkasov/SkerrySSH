"""Keep the independently copyable development kit covered by the harness/CI."""
import os
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1] / "portable-skills"


class PortableSkillsTests(unittest.TestCase):
    def run_helper(self, name):
        result = subprocess.run(
            [sys.executable, str(ROOT / "tools" / name)],
            cwd=ROOT,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"),
            text=True,
            capture_output=True,
            timeout=60,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout + result.stderr

    def test_bundle_integrity_and_local_links(self):
        self.run_helper("verify_bundle.py")

    def test_installer_and_environment_helpers(self):
        self.assertIn("\nOK\n", self.run_helper("test_helpers.py"))
