#!/usr/bin/env python3
"""Merge the per-module flatpak-gradle-generator outputs into one sources list.

The three desktop modules (sync-wire, shared, composeApp) each emit a sources array; they share
many dependencies, so entries are de-duplicated by their download URL. The result is the
`flatpak-sources.json` the manifest references.
"""
import json
import sys


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: merge-sources.py OUT.json IN1.json [IN2.json ...]", file=sys.stderr)
        return 2

    out_path, in_paths = sys.argv[1], sys.argv[2:]
    seen: set[str] = set()
    merged: list[dict] = []
    for path in in_paths:
        with open(path) as f:
            for entry in json.load(f):
                key = entry.get("url") or json.dumps(entry, sort_keys=True)
                if key in seen:
                    continue
                seen.add(key)
                merged.append(entry)

    # Stable order keeps the committed file diff-friendly across regenerations.
    merged.sort(key=lambda e: (e.get("dest", ""), e.get("dest-filename", "")))
    with open(out_path, "w") as f:
        json.dump(merged, f, indent=2)
        f.write("\n")

    print(f"merged {len(merged)} unique sources from {len(in_paths)} modules -> {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
