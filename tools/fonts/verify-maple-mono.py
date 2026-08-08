#!/usr/bin/env python3
"""Verifies the bundled Maple Mono subset against the upstream release.

The two `maplemono_*.ttf` in composeResources are not upstream binaries but a subset, so nothing in
the repository proves what they actually contain. This script is that proof: every bundled glyph
must exist in the upstream release with the same outline and the same advance, and the 2:1 CJK/ASCII
ratio the terminal grid relies on must hold.

    pip install fonttools
    tools/fonts/verify-maple-mono.py                        # downloads the upstream release (~140 MB)
    tools/fonts/verify-maple-mono.py --upstream DIR         # reuses an unpacked release

Exit code 0 — the bundled fonts are a faithful subset.
"""

import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.ttLib import TTFont

REPO = pathlib.Path(__file__).resolve().parents[2]
BUNDLED = REPO / "composeApp/src/commonMain/composeResources/font"
UPSTREAM_REPO = "subframe7536/maple-font"
# The bundled faces report "Version 7.900" (name ID 5), which is this release.
UPSTREAM_TAG = "v7.9"
UPSTREAM_ASSET = "MapleMono-CN.zip"
WEIGHTS = {"maplemono_regular.ttf": "MapleMono-CN-Regular.ttf", "maplemono_bold.ttf": "MapleMono-CN-Bold.ttf"}


def download_upstream(dest: pathlib.Path) -> pathlib.Path:
    """Fetches and unpacks the upstream release; `gh` avoids the API rate limit on unauthenticated pulls."""
    if shutil.which("gh") is None:
        sys.exit(f"gh not found — download {UPSTREAM_ASSET} from {UPSTREAM_REPO} {UPSTREAM_TAG} and pass --upstream")
    archive = dest / UPSTREAM_ASSET
    print(f"downloading {UPSTREAM_REPO} {UPSTREAM_TAG} / {UPSTREAM_ASSET} (~140 MB)...")
    subprocess.run(
        ["gh", "release", "download", UPSTREAM_TAG, "--repo", UPSTREAM_REPO, "--pattern", UPSTREAM_ASSET,
         "--dir", str(dest), "--clobber"],
        check=True,
    )
    unpacked = dest / "unpacked"
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(unpacked)
    return unpacked


def outline(font: TTFont, glyph_set, name: str):
    """Absolute contours: composites are decomposed, so a differing component index is not a difference."""
    pen = DecomposingRecordingPen(glyph_set)
    glyph_set[name].draw(pen)
    return pen.value


def verify(bundled_path: pathlib.Path, upstream_path: pathlib.Path) -> list[str]:
    problems = []
    sub, up = TTFont(bundled_path), TTFont(upstream_path)
    sub_name = {r.nameID: str(r) for r in sub["name"].names}
    up_name = {r.nameID: str(r) for r in up["name"].names}
    for name_id, label in ((1, "family"), (5, "version"), (0, "copyright")):
        if sub_name.get(name_id) != up_name.get(name_id):
            problems.append(f"{label}: bundled {sub_name.get(name_id)!r} != upstream {up_name.get(name_id)!r}")

    sub_cmap, up_cmap = sub.getBestCmap(), up.getBestCmap()
    extra = set(sub_cmap) - set(up_cmap)
    if extra:
        problems.append(f"{len(extra)} codepoints not in upstream, e.g. {sorted(hex(c) for c in extra)[:5]}")

    sub_glyphs, up_glyphs = sub.getGlyphSet(), up.getGlyphSet()
    mismatched_outline, mismatched_advance = [], []
    for cp in sorted(set(sub_cmap) & set(up_cmap)):
        sub_g, up_g = sub_cmap[cp], up_cmap[cp]
        if sub["hmtx"][sub_g][0] != up["hmtx"][up_g][0]:
            mismatched_advance.append(cp)
        elif outline(sub, sub_glyphs, sub_g) != outline(up, up_glyphs, up_g):
            mismatched_outline.append(cp)
    if mismatched_advance:
        problems.append(f"{len(mismatched_advance)} advances differ, e.g. {[hex(c) for c in mismatched_advance[:5]]}")
    if mismatched_outline:
        problems.append(f"{len(mismatched_outline)} outlines differ, e.g. {[hex(c) for c in mismatched_outline[:5]]}")

    # The terminal draws a wide cell across exactly two columns, so anything but 2:1 shows as drift.
    ascii_w = {sub["hmtx"][sub_cmap[cp]][0] for cp in range(0x20, 0x7F) if cp in sub_cmap}
    cjk_w = {sub["hmtx"][sub_cmap[cp]][0] for cp in (0x4E2D, 0x65E5, 0x3001, 0xFF08) if cp in sub_cmap}
    if len(ascii_w) != 1:
        problems.append(f"ASCII is not monospaced: advances {sorted(ascii_w)}")
    elif cjk_w != {2 * next(iter(ascii_w))}:
        problems.append(f"CJK is not 2x ASCII: ascii {ascii_w}, cjk {cjk_w}")

    print(f"{bundled_path.name}: {len(sub_cmap)} codepoints, all verified against {upstream_path.name}"
          if not problems else f"{bundled_path.name}: {len(problems)} problem(s)")
    return [f"{bundled_path.name}: {p}" for p in problems]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=pathlib.Path, help="directory with an unpacked MapleMono-CN release")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        upstream = args.upstream or download_upstream(pathlib.Path(tmp))
        problems = []
        for bundled, upstream_name in WEIGHTS.items():
            upstream_file = upstream / upstream_name
            if not upstream_file.exists():
                sys.exit(f"{upstream_file} not found — is {upstream} an unpacked {UPSTREAM_ASSET}?")
            problems += verify(BUNDLED / bundled, upstream_file)

    for problem in problems:
        print(f"FAIL {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
