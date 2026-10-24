"""Keep server image publication behind the human release-publish action."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class TestServerRelease(unittest.TestCase):
    def test_server_image_publish_requires_published_release(self):
        workflow = (ROOT / ".github/workflows/server-image.yml").read_text()
        publication_conditions = re.findall(r"^\s+if: (.*)$", workflow, re.MULTILINE)
        allowed = "github.event_name == 'release' && startsWith(github.event.release.tag_name, 'server-v')"
        self.assertEqual(publication_conditions.count(allowed), 6,
                         "All registry login/push/digest/publish operations need a published server release")
        self.assertNotIn("if: startsWith(github.ref, 'refs/tags/server-v')", workflow)
        self.assertIn("  release:\n    types: [published]", workflow)

    def test_release_build_checks_out_the_published_server_tag(self):
        workflow = (ROOT / ".github/workflows/server-image.yml").read_text()
        self.assertIn("github.event_name != 'release' || startsWith(github.event.release.tag_name, 'server-v')", workflow)
        self.assertIn("SERVER_TAG: ${{ github.event.release.tag_name || github.ref_name }}", workflow)
        self.assertIn('version="${SERVER_TAG#server-v}"', workflow)
        self.assertNotIn('version="${GITHUB_REF_NAME#server-v}"', workflow)

    def test_published_server_tag_cannot_resolve_to_a_same_named_branch(self):
        workflow = (ROOT / ".github/workflows/server-image.yml").read_text()
        self.assertIn("ref: ${{ github.sha }}", workflow)
        self.assertNotIn("ref: ${{ github.event.release.tag_name || github.ref }}", workflow)
