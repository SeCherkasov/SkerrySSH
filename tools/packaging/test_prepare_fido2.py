"""Offline trust-boundary tests; does not build or load native libraries."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("prepare_fido2", Path(__file__).with_name("prepare_fido2.py"))
PACKAGING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGING)


class SourceVerificationTest(unittest.TestCase):
    def archive(self, temporary, path, content=b"safe", kind=tarfile.REGTYPE):
        archive = Path(temporary) / "test.tar.gz"
        with tarfile.open(archive, "w:gz") as target:
            member = tarfile.TarInfo(path)
            member.type = kind
            member.size = len(content) if kind == tarfile.REGTYPE else 0
            if kind == tarfile.SYMTYPE:
                member.linkname = "../../outside"
            target.addfile(member, io.BytesIO(content) if member.size else None)
        return archive

    def test_source_digest_checked_even_for_cached_downloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            (cache / "source.tar.gz").write_bytes(b"tampered")
            with self.assertRaisesRegex(RuntimeError, "digest mismatch"):
                PACKAGING.verified_archive({"root": "source", "sha256": "0" * 64}, cache)

    def test_archive_cannot_traverse_or_write_through_a_link(self):
        for path, kind in [("source/../../outside", tarfile.REGTYPE), ("/outside", tarfile.REGTYPE),
                           ("source/link", tarfile.SYMTYPE), ("other/file", tarfile.REGTYPE)]:
            with self.subTest(path=path), tempfile.TemporaryDirectory() as temporary:
                archive = self.archive(temporary, path, kind=kind)
                with self.assertRaises(RuntimeError):
                    PACKAGING.extract(archive, {"root": "source"}, Path(temporary) / "unpacked")

    def test_valid_source_extraction_stays_inside_declared_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = self.archive(temporary, "source/LICENSE")
            root = PACKAGING.extract(archive, {"root": "source"}, Path(temporary) / "unpacked")
            self.assertEqual(b"safe", (root / "LICENSE").read_bytes())

    def test_pins_include_every_shipped_library_and_license(self):
        sources = json.loads(PACKAGING.MANIFEST.read_text())
        self.assertEqual({"libfido2", "openssl", "libcbor", "zlib"}, set(sources))
        for source in sources.values():
            self.assertRegex(source["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(source["url"].startswith("https://"))
            self.assertNotIn("..", Path(source["license"]).parts)


if __name__ == "__main__":
    unittest.main()
