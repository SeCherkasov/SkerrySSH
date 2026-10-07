from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

HARNESS = Path(__file__).resolve().parents[1]
ROOT = HARNESS.parents[1]


class Sandbox:
    def __init__(self):
        self.path = tempfile.mkdtemp(prefix="skerry-harness-")
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "harness@test")
        self.git("config", "user.name", "Harness")
        shutil.copytree(HARNESS, Path(self.path, "tools/harness"),
                        ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copytree(ROOT / ".agents/reviewers", Path(self.path, ".agents/reviewers"))
        self.write("README.md", "seed\n")
        self.commit("seed")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.path, capture_output=True,
                              text=True, check=True).stdout

    def write(self, path, body):
        full = Path(self.path, path)
        full.parent.mkdir(parents=True, exist_ok=True)
        full.write_text(body)
        return str(full)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def branch(self, name):
        self.git("switch", "-qc", name)

    def cleanup(self):
        shutil.rmtree(self.path)


class SandboxCase(unittest.TestCase):
    def setUp(self):
        self.box = Sandbox()
        self.addCleanup(self.box.cleanup)
        self.cwd = self.box.path
        # CI behavior is selected explicitly by its tests, never inherited from the suite host.
        isolated_cache = patch.dict(os.environ, {"CI": "false",
                                                "XDG_CACHE_HOME": str(Path(self.cwd, ".git/test-cache"))})
        isolated_cache.start()
        self.addCleanup(isolated_cache.stop)

    def change(self, path="server/src/main/kotlin/A.kt", text="val a = 1\n"):
        self.box.branch("refactor/example")
        return self.box.write(path, text)
