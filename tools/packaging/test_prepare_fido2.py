"""Offline trust-boundary tests; does not build or load native libraries."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

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


class NativeToolchainTest(unittest.TestCase):
    def test_windows_tools_resolve_from_the_supplied_developer_path(self):
        compiler = "C:/Visual Studio/VC/bin/nmake.exe"
        environment = {"Path": "C:/Visual Studio/VC/bin", "PERL": "C:/Strawberry/perl.exe"}
        with patch.object(PACKAGING.os, "name", "nt"), \
                patch.object(PACKAGING.shutil, "which", return_value=compiler) as locate, \
                patch.object(PACKAGING.subprocess, "check_output", return_value="built") as execute:
            self.assertEqual("built", PACKAGING.run("nmake", "/NOLOGO", "build_libs", env=environment))
        locate.assert_called_once_with("nmake", path=environment["Path"])
        self.assertEqual((compiler, "/NOLOGO", "build_libs"), execute.call_args.args[0])
        self.assertEqual(environment, execute.call_args.kwargs["env"])

    def test_windows_missing_developer_tool_does_not_fall_back_to_parent_path(self):
        with patch.object(PACKAGING.os, "name", "nt"), \
                patch.object(PACKAGING.shutil, "which", return_value=None), \
                patch.object(PACKAGING.subprocess, "check_output", return_value="unexpected") as execute:
            with self.assertRaises(FileNotFoundError):
                PACKAGING.run("nmake", env={"PATH": "C:/empty"})
        execute.assert_not_called()

    def test_linux_system_loaders_are_allowed(self):
        for loader in ("ld-linux-x86-64.so.2", "ld-linux-aarch64.so.1"):
            with self.subTest(loader=loader):
                dynamic = "\n".join(f"(NEEDED) Shared library: [{name}]" for name in
                                    ("libudev.so.1", "libc.so.6", loader))
                with patch.object(PACKAGING, "run", side_effect=[dynamic, "ABI ok"]):
                    try:
                        PACKAGING.audit(Path("libfido2.so"), "linux")
                    except RuntimeError as error:
                        self.fail(f"System loader rejected: {error}")

    def test_linux_non_system_dependencies_and_search_paths_still_fail(self):
        for dynamic in ("(NEEDED) Shared library: [libcrypto.so.3]",
                        "(NEEDED) Shared library: [ld-linux-untrusted.so.2]",
                        "(RUNPATH) Library runpath: [/tmp/build]"):
            with self.subTest(dynamic=dynamic), patch.object(PACKAGING, "run", return_value=dynamic):
                with self.assertRaises(RuntimeError):
                    PACKAGING.audit(Path("libfido2.so"), "linux")

    def test_windows_developer_command_preserves_cmd_quotes(self):
        installation = "C:/Program Files/Microsoft Visual Studio/18/Enterprise"
        with patch.dict(PACKAGING.os.environ, {"ProgramFiles(x86)": "C:/Program Files (x86)"}), \
                patch.object(PACKAGING.Path, "is_file", return_value=True), \
                patch.object(PACKAGING.subprocess, "check_output", side_effect=[installation, "PATH=compiler\n=hidden=value\n"]) as execute:
            result = PACKAGING.windows_environment("x86_64")
        command = execute.call_args_list[1].args[0]
        self.assertIsInstance(command, str, "cmd.exe syntax must not pass through Windows argv escaping")
        script = Path(installation) / "Common7/Tools/VsDevCmd.bat"
        self.assertEqual(f'cmd.exe /d /s /c "call "{script}" -no_logo -host_arch=x64 -arch=x64 && set"', command)
        self.assertEqual({"PATH": "compiler"}, result)

    def test_windows_developer_path_cannot_expand_cmd_syntax(self):
        for character in ('"', '&', '|', '<', '>', '^', '%', '!', '\r', '\n'):
            with self.subTest(character=character), \
                    patch.dict(PACKAGING.os.environ, {"ProgramFiles(x86)": "C:/Program Files (x86)"}), \
                    patch.object(PACKAGING.Path, "is_file", return_value=True), \
                    patch.object(PACKAGING, "run", return_value=f"C:/Visual{character}Studio") as execute:
                with self.assertRaisesRegex(RuntimeError, "Trusted Visual Studio"):
                    PACKAGING.windows_environment("x86_64")
                self.assertEqual(1, execute.call_count)

    def test_windows_cmake_uses_selected_developer_environment(self):
        with patch.object(PACKAGING, "run", return_value="") as execute:
            PACKAGING.cmake(Path("source"), Path("build"), Path("install"), [], "windows", "aarch64", {"PATH": "compiler"})
        configure = execute.call_args_list[0].args
        self.assertEqual("NMake Makefiles", configure[configure.index("-G") + 1])
        self.assertNotIn("-A", configure)
        self.assertNotIn("--parallel", execute.call_args_list[1].args)

    def test_windows_openssl_keeps_the_selected_native_perl(self):
        native_perl = "C:/Strawberry/perl/bin/perl.exe"
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(PACKAGING.shutil, "which", return_value="C:/msys64/usr/bin/perl.exe"), \
                patch.object(PACKAGING, "run", return_value="") as execute:
            PACKAGING.openssl(Path("source"), Path(temporary) / "build", Path("install"),
                              "windows", "x86_64", {"PATH": "C:/msys64/usr/bin",
                                                     "SKERRY_FIDO_PERL": native_perl, "PERL": "perl"})
        self.assertEqual(native_perl, execute.call_args_list[0].args[0],
                         "VsDevCmd must not replace the selected Windows Perl with MSYS Perl")
        for call in execute.call_args_list:
            self.assertEqual(native_perl, call.kwargs["env"]["PERL"],
                             "OpenSSL's generated Makefile must also use the selected native Perl")


if __name__ == "__main__":
    unittest.main()
