"""Server tags publish Docker images independently of GitHub Releases."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class TestServerRelease(unittest.TestCase):
    def test_server_tag_publishes_without_a_github_release(self):
        workflow = (ROOT / ".github/workflows/server-image.yml").read_text()
        conditions = re.findall(r"^\s+if: (.*)$", workflow, re.MULTILINE)
        allowed = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/server-v')"
        self.assertEqual(conditions.count(allowed), 6,
                         "Server tag pushes must publish all registry/digest/manifest stages")
        self.assertIn('tags: ["server-v*"]', workflow)
        self.assertNotIn("  release:", workflow)
        self.assertNotIn("github.event.release", workflow)

    def test_server_tag_build_uses_immutable_event_commit(self):
        workflow = (ROOT / ".github/workflows/server-image.yml").read_text()
        self.assertIn("ref: ${{ github.sha }}", workflow)
        self.assertIn('version="${GITHUB_REF_NAME#server-v}"', workflow)
        self.assertNotIn("SERVER_TAG:", workflow)
