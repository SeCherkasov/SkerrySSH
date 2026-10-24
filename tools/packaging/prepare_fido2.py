#!/usr/bin/env python3
"""Build and stage the desktop FIDO runtime; downloads occur only during packaging.

Requires Python 3.11+, CMake and a native C compiler (Visual Studio on Windows).
Unix also needs pkg-config; Linux needs libudev development headers. Nothing is
installed globally. Invoke through the same bounded, serial runner as Gradle.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import struct
import sys
import tarfile
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MANIFEST = HERE / "fido2-sources.json"
MAX_ARCHIVE = 80 * 1024 * 1024


def host() -> tuple[str, str]:
    systems = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}
    architectures = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}
    try:
        return systems[platform.system()], architectures[platform.machine().lower()]
    except KeyError as error:
        raise RuntimeError("FIDO packaging supports Linux/macOS/Windows x86_64 and aarch64") from error


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verified_archive(source: dict, cache: Path) -> Path:
    archive = cache / (source["root"] + ".tar.gz")
    if archive.exists() and digest(archive) != source["sha256"]:
        raise RuntimeError(f"Cached source digest mismatch: {archive.name}")
    if not archive.exists():
        cache.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(source["url"], timeout=120) as response:
            data = response.read(MAX_ARCHIVE + 1)
        if len(data) > MAX_ARCHIVE or hashlib.sha256(data).hexdigest() != source["sha256"]:
            raise RuntimeError(f"Source digest/size mismatch: {source['root']}")
        archive.write_bytes(data)
    return archive


def extract(archive: Path, source: dict, destination: Path) -> Path:
    root = destination / source["root"]
    if root.exists():
        shutil.rmtree(root)
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        if sum(item.size for item in members) > 300 * 1024 * 1024:
            raise RuntimeError("Source archive exceeds extraction budget")
        for item in members:
            path = PurePosixPath(item.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != source["root"]:
                raise RuntimeError("Source archive path escapes its root")
            if not (item.isdir() or item.isfile()):
                raise RuntimeError("Source archive contains a link or special file")
        # Only bounded regular files and directories survived validation above.
        tar.extractall(destination, members=members, filter="data")
    return root


def run(*args: str, env: dict | None = None, cwd: Path | None = None) -> str:
    print("+", " ".join(map(str, args)), flush=True)
    return subprocess.check_output(args, env=env, cwd=cwd, text=True, stderr=subprocess.STDOUT)


def windows_environment(arch: str) -> dict:
    installer = Path(os.environ["ProgramFiles(x86)"]) / "Microsoft Visual Studio/Installer/vswhere.exe"
    installation = run(str(installer), "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath").strip()
    script = Path(installation) / "Common7/Tools/VsDevCmd.bat"
    if not script.is_file() or any(char in str(script) for char in '\"&|<>^%!\r\n'):
        raise RuntimeError("Trusted Visual Studio development environment not found")
    architecture = "arm64" if arch == "aarch64" else "x64"
    # Only the validated Visual Studio installation path enters cmd.exe syntax.
    command = f'call "{script}" -no_logo -host_arch=x64 -arch={architecture} && set'
    # cmd.exe parses shell syntax, not C argv. Passing a sequence would escape
    # the embedded quotes as \" and break Visual Studio paths containing spaces.
    result = subprocess.check_output(f'cmd.exe /d /s /c "{command}"', text=True, stderr=subprocess.STDOUT)
    return dict(line.split("=", 1) for line in result.splitlines() if "=" in line and not line.startswith("="))


def openssl(source: Path, build: Path, prefix: Path, system: str, arch: str, env: dict) -> None:
    build.mkdir(parents=True, exist_ok=True)
    targets = {("linux", "x86_64"): "linux-x86_64", ("linux", "aarch64"): "linux-aarch64",
               ("macos", "x86_64"): "darwin64-x86_64-cc", ("macos", "aarch64"): "darwin64-arm64-cc",
               ("windows", "x86_64"): "VC-WIN64A", ("windows", "aarch64"): "VC-WIN64-ARM"}
    flags = ["no-shared", "no-module", "no-dso", "no-autoload-config", "no-engine", "no-pinshared", "no-tests", "no-apps", "no-docs", "no-asm"]
    if system != "windows":
        flags += ["-fPIC"]
    print(run("perl", str(source / "Configure"), targets[system, arch], *flags,
              f"--prefix={prefix}", "--libdir=lib", env=env, cwd=build))
    if system == "windows":
        print(run("nmake", "/NOLOGO", "build_libs", env=env, cwd=build))
        print(run("nmake", "/NOLOGO", "install_dev", env=env, cwd=build))
    else:
        print(run("make", "-j2", "build_libs", env=env, cwd=build))
        print(run("make", "install_dev", env=env, cwd=build))


def cmake(source: Path, build: Path, prefix: Path, flags: list[str], system: str, arch: str, env: dict) -> None:
    common = [
        "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_INSTALL_PREFIX={prefix}",
        "-DCMAKE_INSTALL_LIBDIR=lib", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5", "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded",
    ]
    if system == "windows":
        # VsDevCmd already selected the compiler and target architecture. NMake
        # uses that environment without requiring a particular Visual Studio IDE.
        generator = ["-G", "NMake Makefiles"]
        common += ["-DCMAKE_C_FLAGS_RELEASE=/O2 /MT /DNDEBUG"]
    else:
        generator = ["-G", "Unix Makefiles"]
    if system == "macos":
        common += ["-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0"]
    print(run("cmake", "-S", str(source), "-B", str(build), *generator, *common, *flags, env=env))
    parallel = [] if system == "windows" else ["--parallel", "2"]
    print(run("cmake", "--build", str(build), "--config", "Release", *parallel, env=env))
    print(run("cmake", "--install", str(build), "--config", "Release", env=env))


def windows_imports(library: Path) -> set[str]:
    """Read PE import names without executing or depending on a developer-shell tool."""
    data = library.read_bytes()
    if data[:2] != b"MZ":
        raise RuntimeError("Not a Windows PE library")
    pe = struct.unpack_from("<I", data, 60)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise RuntimeError("Invalid PE signature")
    sections, optional_size = struct.unpack_from("<H12xH", data, pe + 6)
    optional = pe + 24
    magic = struct.unpack_from("<H", data, optional)[0]
    if magic != 0x20b:
        raise RuntimeError("Expected a 64-bit PE library")
    import_rva = struct.unpack_from("<I", data, optional + 120)[0]
    section_table = optional + optional_size

    def offset(rva: int) -> int:
        for index in range(sections):
            size, address, raw_size, raw = struct.unpack_from("<4I", data, section_table + index * 40 + 8)
            if address <= rva < address + max(size, raw_size):
                return raw + rva - address
        raise RuntimeError("Invalid PE import address")

    imports = set()
    descriptor = offset(import_rva)
    for index in range(128):
        fields = struct.unpack_from("<5I", data, descriptor + index * 20)
        if not any(fields):
            return imports
        name = offset(fields[3])
        end = data.index(b"\0", name, name + 256)
        imports.add(data[name:end].decode("ascii").lower())
    raise RuntimeError("Unbounded PE import table")


def audit(library: Path, system: str) -> None:
    if system == "linux":
        dynamic = run("readelf", "-d", str(library))
        dependencies = re.findall(r"Shared library: \[([^]]+)\]", dynamic)
        allowed = {"libudev.so.1", "libc.so.6", "libm.so.6", "libpthread.so.0", "librt.so.1", "libdl.so.2",
                   "ld-linux-x86-64.so.2", "ld-linux-aarch64.so.1"}
        if set(dependencies) - allowed:
            raise RuntimeError(f"Unbundled native dependencies: {dependencies}")
        if "(RPATH)" in dynamic or "(RUNPATH)" in dynamic:
            raise RuntimeError("FIDO binary retained a build-machine runtime search path")
    elif system == "macos":
        dependencies = run("otool", "-L", str(library)).splitlines()[2:]
        if any(not line.strip().startswith(("/usr/lib/", "/System/Library/")) for line in dependencies):
            raise RuntimeError(f"Unbundled dylib dependencies: {dependencies}")
    elif system == "windows":
        allowed = {"kernel32.dll", "ntdll.dll", "ws2_32.dll", "wsock32.dll", "bcrypt.dll", "setupapi.dll", "hid.dll",
                   "advapi32.dll", "user32.dll", "crypt32.dll", "ole32.dll", "shell32.dll", "secur32.dll"}
        dependencies = windows_imports(library)
        if any(name not in allowed and not name.startswith("api-ms-win-") for name in dependencies):
            raise RuntimeError(f"Unbundled DLL dependencies: {dependencies}")
    # A short-lived process releases the DLL before Windows moves/deletes staging.
    print(run(sys.executable, str(Path(__file__).resolve()), "--abi-smoke", str(library.resolve())))


def smoke(library: Path) -> None:
    system, _ = host()
    # Load by absolute path; Windows transitive resolution is restricted to the DLL's
    # own directory and System32. /MT removes any VC++ redistributable prerequisite.
    native = ctypes.CDLL(str(library.resolve()), **({"winmode": 0x1100} if system == "windows" else {}))
    native.fido_init.argtypes = [ctypes.c_int]
    native.fido_init(0)
    native.fido_cred_new.restype = ctypes.c_void_p
    pointer = ctypes.c_void_p(native.fido_cred_new())
    if not pointer.value:
        raise RuntimeError("libfido2 ABI smoke allocation failed")
    native.fido_cred_free.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
    native.fido_cred_free(ctypes.byref(pointer))
    if pointer.value:
        raise RuntimeError("libfido2 ABI smoke free failed")


def verify(bundle: Path, sources: dict, system: str, arch: str) -> None:
    record = json.loads((bundle / "bundle.json").read_text())
    if record["sources"] != sources or record["platform"] != f"{system}-{arch}":
        raise RuntimeError("Staged FIDO runtime does not match pinned sources/platform")
    filename = {"linux": "libfido2.so", "macos": "libfido2.dylib", "windows": "fido2.dll"}[system]
    expected_files = {filename} | {f"LICENSE-{name}.txt" for name in sources}
    if record["library"] != filename or set(record["files"]) != expected_files:
        raise RuntimeError("Staged FIDO runtime is incomplete")
    for name, expected in record["files"].items():
        if Path(name).name != name or digest(bundle / name) != expected:
            raise RuntimeError(f"Staged FIDO runtime digest mismatch: {name}")
    audit(bundle / record["library"], system)


def prepare(output: Path, work: Path, sources: dict, system: str, arch: str) -> Path:
    bundle = output / "common" / "fido2" / f"{system}-{arch}"
    for directory in ("openssl", "libcbor", "zlib", "libfido2", "install"):
        if (work / directory).exists():
            shutil.rmtree(work / directory)
    prefix = work / "install"
    source_root = work / "sources"
    source_root.mkdir(parents=True, exist_ok=True)
    trees = {name: extract(verified_archive(item, work / "downloads"), item, source_root) for name, item in sources.items()}
    env = windows_environment(arch) if system == "windows" else os.environ.copy()
    env["PKG_CONFIG_PATH"] = str(prefix / "lib" / "pkgconfig")
    if system == "windows":
        env["_CL_"] = "/MP2"
    # Never resolve a dependency from LD_LIBRARY_PATH/DYLD_LIBRARY_PATH during the smoke.
    env.pop("LD_LIBRARY_PATH", None)
    env.pop("DYLD_LIBRARY_PATH", None)
    static = ["-DBUILD_SHARED_LIBS=OFF"]
    openssl(trees["openssl"], work / "openssl", prefix, system, arch, env)
    cmake(trees["libcbor"], work / "libcbor", prefix, static + ["-DWITH_EXAMPLES=OFF", "-DWITH_TESTS=OFF"], system, arch, env)
    cmake(trees["zlib"], work / "zlib", prefix, ["-DZLIB_BUILD_SHARED=OFF", "-DZLIB_BUILD_STATIC=ON", "-DZLIB_BUILD_TESTING=OFF"], system, arch, env)
    flags = [
        "-DBUILD_SHARED_LIBS=ON", "-DBUILD_STATIC_LIBS=OFF", "-DBUILD_TESTS=OFF",
        "-DBUILD_EXAMPLES=OFF", "-DBUILD_TOOLS=OFF", "-DBUILD_MANPAGES=OFF",
        "-DNFC_LINUX=OFF", "-DCMAKE_SKIP_RPATH=ON", f"-DCMAKE_PREFIX_PATH={prefix}",
    ]
    if system == "windows":
        for dependency in ("CBOR", "CRYPTO", "ZLIB"):
            flags += [f"-D{dependency}_INCLUDE_DIRS={prefix / 'include'}", f"-D{dependency}_LIBRARY_DIRS={prefix / 'lib'}"]
        flags += [f"-DCBOR_LIBRARIES={prefix / 'lib' / 'cbor.lib'}", f"-DCRYPTO_LIBRARIES={prefix / 'lib' / 'libcrypto.lib'};ws2_32;crypt32;advapi32;user32;bcrypt", f"-DZLIB_LIBRARIES={prefix / 'lib' / 'zs.lib'}"]
    cmake(trees["libfido2"], work / "libfido2", prefix, flags, system, arch, env)
    filename = {"linux": "libfido2.so", "macos": "libfido2.dylib", "windows": "fido2.dll"}[system]
    native = prefix / ("bin" if system == "windows" else "lib") / filename
    # Staging is replaced only after a successful link and load; failed builds cannot
    # leave a half-populated runtime eligible for packaging.
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        stage = Path(temporary) / "bundle"
        stage.mkdir()
        shutil.copyfile(native, stage / filename)
        if system == "macos":
            run("install_name_tool", "-id", "@rpath/libfido2.dylib", str(stage / filename))
            run("codesign", "--force", "--sign", "-", str(stage / filename))
        for name, item in sources.items():
            shutil.copyfile(trees[name] / item["license"], stage / f"LICENSE-{name}.txt")
        record = {"sources": sources, "platform": f"{system}-{arch}", "library": filename,
                  "files": {path.name: digest(path) for path in stage.iterdir()}}
        (stage / "bundle.json").write_text(json.dumps(record, indent=2) + "\n")
        verify(stage, sources, system, arch)
        if bundle.exists():
            shutil.rmtree(bundle)
        bundle.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(stage, bundle)
    return bundle


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=REPO / "composeApp/build/generated/fido2-app-resources")
    parser.add_argument("--work", type=Path, default=REPO / "composeApp/build/native-fido2")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--abi-smoke", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.abi_smoke:
        smoke(args.abi_smoke.resolve())
        return
    output, work = args.output.resolve(), args.work.resolve()
    sources = json.loads(MANIFEST.read_text())
    system, arch = host()
    bundle = output / "common" / "fido2" / f"{system}-{arch}"
    if not args.verify_only:
        output.parent.mkdir(parents=True, exist_ok=True)
        bundle = prepare(output, work, sources, system, arch)
    verify(bundle, sources, system, arch)
    print(f"FIDO runtime ready: {bundle}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        print(failure.output, file=sys.stderr)
        raise SystemExit(failure.returncode)
