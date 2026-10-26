"""Regression for the Linux jpackage initialization pipe overflow."""
import email
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "tools/packaging/linux_classpath.py"


class LinuxClasspathTest(unittest.TestCase):
    def test_order_options_folding_and_repeat(self):
        self.assertTrue(SCRIPT.is_file(), "Linux packaging has no classpath compaction")
        with tempfile.TemporaryDirectory(prefix="skerry package ") as directory:
            app = Path(directory)
            jars = [f"dependency-{i:03d}-long-name.jar" for i in range(94)]
            jars[2] = "dependency space-юникод.jar"
            for name in jars:
                (app / name).write_bytes(b"unchanged dependency")
            entries = "".join(f"app.classpath=$APPDIR/{name}\n" for name in jars)
            before = "[Application]\napp.mainclass=app.skerry.ui.MainKt\n" + entries + "\n[JavaOptions]\njava-options=-Xmx2g\njava-options=-Dvalue=with spaces\n"
            cfg = app / "Skerry.cfg"
            cfg.write_text(before)
            result = subprocess.run(["python3", str(SCRIPT), str(cfg)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            after = cfg.read_text()
            self.assertEqual(after, before.replace(entries, "app.classpath=$APPDIR/skerry-classpath.jar\n"))
            with zipfile.ZipFile(app / "skerry-classpath.jar") as jar:
                manifest = jar.read("META-INF/MANIFEST.MF")
            self.assertTrue(all(len(line) <= 72 for line in manifest.split(b"\r\n")))
            from urllib.parse import unquote
            cp = str(email.message_from_bytes(manifest)["Class-Path"]).replace("\r\n ", "").replace("\n ", "")
            self.assertEqual([unquote(item) for item in cp.split()], jars)
            self.assertTrue(manifest.endswith(b"\r\n\r\n"))
            for name in jars:
                self.assertEqual((app / name).read_bytes(), b"unchanged dependency")
            subprocess.run(["python3", str(SCRIPT), str(cfg)], check=True)
            self.assertEqual(cfg.read_text(), after)

    def test_missing_dependency_leaves_config_unchanged(self):
        self.assertTrue(SCRIPT.is_file(), "Linux packaging has no classpath compaction")
        with tempfile.TemporaryDirectory() as directory:
            cfg = Path(directory) / "Skerry.cfg"
            before = "[Application]\napp.classpath=$APPDIR/missing.jar\n"
            cfg.write_text(before)
            result = subprocess.run(["python3", str(SCRIPT), str(cfg)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(cfg.read_text(), before)
