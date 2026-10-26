#!/usr/bin/env python3
"""Keep Linux jpackage initialization data small without changing dependency order.

JDK 21 LinuxLauncher.c accepts a short pipe read as a complete initialization
buffer. A manifest-only JAR replaces the repeated launcher classpath entries.
"""
import argparse
from pathlib import Path
from urllib.parse import quote
import zipfile


CLASSPATH = "app.classpath="
COMPACT = CLASSPATH + "$APPDIR/skerry-classpath.jar"


def compact(config: Path) -> None:
    original = config.read_bytes()
    text = original.decode("utf-8")
    lines = text.splitlines(keepends=True)
    entries = [line.rstrip("\r\n")[len(CLASSPATH):] for line in lines if line.startswith(CLASSPATH)]
    if entries == [COMPACT[len(CLASSPATH):]]:
        with zipfile.ZipFile(config.parent / "skerry-classpath.jar") as jar:
            jar.read("META-INF/MANIFEST.MF")
        return
    if not entries:
        raise ValueError("No launcher classpath entries")
    names = []
    for entry in entries:
        if not entry.startswith("$APPDIR/"):
            raise ValueError("Expected an APPDIR-relative classpath")
        name = entry[len("$APPDIR/"):]
        if not name or Path(name).is_absolute() or ".." in Path(name).parts or name == "skerry-classpath.jar":
            raise ValueError("Invalid dependency path")
        if not (config.parent / name).is_file():
            raise ValueError(f"Missing dependency: {name}")
        names.append(quote(name, safe="/"))
    # Percent-encoded URLs are ASCII. Continuation lines include their leading
    # space in the manifest's 72-byte physical-line limit.
    header = ("Class-Path: " + " ".join(names)).encode("ascii")
    folded = header[:72] + b"\r\n"
    for start in range(72, len(header), 71):
        folded += b" " + header[start:start + 71] + b"\r\n"
    manifest = b"Manifest-Version: 1.0\r\n" + folded + b"\r\n"
    jar_path = config.parent / "skerry-classpath.jar"
    with zipfile.ZipFile(jar_path, "w") as jar:
        jar.writestr(zipfile.ZipInfo("META-INF/MANIFEST.MF", (1980, 1, 1, 0, 0, 0)), manifest)
    replacement = []
    inserted = False
    for line in lines:
        if line.startswith(CLASSPATH):
            if not inserted:
                replacement.append(COMPACT + ("\r\n" if line.endswith("\r\n") else "\n"))
                inserted = True
        else:
            replacement.append(line)
    config.write_bytes("".join(replacement).encode("utf-8"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    compact(parser.parse_args().config)
