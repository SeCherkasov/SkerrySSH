#!/usr/bin/env python3
"""Generate maven-metadata.xml for every artifact in the offline repository.

The generator (flatpak-gradle-generator) emits only .jar/.pom/.module files, not the
maven-metadata.xml that a Maven repo needs to resolve *version ranges* (e.g. some POMs request
org.bouncycastle:bcutil-jdk18on:[1.80,1.81)). Offline, Gradle refuses a range with "no cached
version listing". Walking the materialised repo and writing a metadata file per artifact — listing
the single version that is actually vendored — lets `gradle --offline` resolve those ranges.

Run inside the Flatpak sandbox after sources are downloaded and before Gradle. Usage:
  gen-maven-metadata.py <offline-repository-dir>
"""
import collections
import os
import sys

# Fixed timestamp: the value is irrelevant to range resolution and staying constant keeps the
# sandbox build reproducible (the environment has no reliable wall clock anyway).
LAST_UPDATED = "20240101000000"


def main() -> int:
    repo = sys.argv[1]
    versions_by_artifact: dict[str, set[str]] = collections.defaultdict(set)

    # A directory holding .pom/.jar/.module files is a version dir; its parent is the artifact dir.
    for dirpath, _dirs, files in os.walk(repo):
        if any(f.endswith((".pom", ".jar", ".module")) for f in files):
            versions_by_artifact[os.path.dirname(dirpath)].add(os.path.basename(dirpath))

    for artifact_dir, versions in versions_by_artifact.items():
        artifact_id = os.path.basename(artifact_dir)
        group_id = os.path.relpath(os.path.dirname(artifact_dir), repo).replace(os.sep, ".")
        ordered = sorted(versions)
        version_tags = "".join(f"      <version>{v}</version>\n" for v in ordered)
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            "<metadata>\n"
            f"  <groupId>{group_id}</groupId>\n"
            f"  <artifactId>{artifact_id}</artifactId>\n"
            "  <versioning>\n"
            f"    <latest>{ordered[-1]}</latest>\n"
            f"    <release>{ordered[-1]}</release>\n"
            "    <versions>\n"
            f"{version_tags}"
            "    </versions>\n"
            f"    <lastUpdated>{LAST_UPDATED}</lastUpdated>\n"
            "  </versioning>\n"
            "</metadata>\n"
        )
        with open(os.path.join(artifact_dir, "maven-metadata.xml"), "w") as f:
            f.write(xml)

    print(f"wrote maven-metadata.xml for {len(versions_by_artifact)} artifacts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
