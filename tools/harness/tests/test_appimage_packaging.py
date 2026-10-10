"""Exercise AppDir assembly without downloading tools or starting a JVM."""
import os
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class TestAppImagePackaging(unittest.TestCase):
    def test_catalog_screenshot_is_embedded_in_appdir(self):
        with tempfile.TemporaryDirectory(prefix="skerry appimage ") as directory:
            work = Path(directory)
            app = work / "app"
            app.mkdir()
            icon = work / "icon.png"
            icon.write_bytes(b"fixture icon")
            tool = work / "appimagetool"
            tool.write_text('#!/bin/sh\ntouch "$2"\n')
            tool.chmod(0o755)
            output = work / "output"
            result = subprocess.run(
                ["bash", str(ROOT / "composeApp/appimage/package-appimage.sh")],
                env={**os.environ, "APP_DIR": str(app), "APPIMAGE_DIR": str(output),
                     "ICON_PNG": str(icon), "ASSET_DIR": str(ROOT / "composeApp/appimage"),
                     "VERSION": "0.5.1", "APPIMAGETOOL": str(tool)},
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            appdir = output / "Skerry.AppDir"
            metainfo = appdir / "usr/share/metainfo/io.github.SeCherkasov.SkerrySSH.metainfo.xml"
            self.assertTrue(metainfo.is_file(), "AppImage has no AppStream catalog metadata")
            component = ET.parse(metainfo).getroot()
            self.assertEqual(component.get("type"), "desktop-application")
            self.assertEqual(component.findtext("id"), "io.github.SeCherkasov.SkerrySSH")
            launchable = component.find("launchable")
            self.assertEqual(launchable.get("type"), "desktop-id")
            self.assertTrue((appdir / launchable.text).is_file())
            self.assertEqual(component.findtext("metadata_license"), "CC0-1.0")
            self.assertEqual(component.findtext("project_license"), "GPL-3.0-only")
            screenshot = component.find("screenshots/screenshot[@type='default']/image")
            self.assertIsNotNone(screenshot)
            self.assertEqual(
                screenshot.text,
                "https://github.com/user-attachments/assets/8305709e-f876-4187-9b5b-799a72a2c235",
            )


if __name__ == "__main__":
    unittest.main()
