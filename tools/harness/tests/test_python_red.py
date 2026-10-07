"""RED accepts a real single-test assertion and rejects execution errors."""
from harness import runner
from support import SandboxCase


class TestPythonRed(SandboxCase):
    def fixture(self, body):
        self.change("tools/harness/tests/test_fixture_red.py",
                    "import unittest\nclass FixtureSingleRed(unittest.TestCase):\n"
                    "    def test_failure(self):\n        " + body + "\n")
        return "tools/harness/tests/test_fixture_red.py"

    def test_single_assertion_is_valid_red(self):
        file = self.fixture("self.assertEqual(1, 2)")
        try:
            runner.red(file, "FixtureSingleRed", self.cwd)
        except ValueError as exc:
            self.fail(f"A real single-test assertion must be accepted as RED: {exc}")

    def test_single_execution_error_is_not_red(self):
        file = self.fixture("raise RuntimeError('not an assertion')")
        with self.assertRaises(ValueError):
            runner.red(file, "FixtureSingleRed", self.cwd)
