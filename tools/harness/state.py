"""Gate state — what has been verified, and against which snapshot of the tree.

The old harness tracked timestamps: `code` (last Edit of a .kt file) versus `build` and `review`.
That is only as honest as the tool that made the edit. A `sed -i`, a `git apply`, a patch written
by a subagent or an editor outside the session left no `code` mark at all, and the guard happily
let the commit through.

This version records a **content digest** of every file that can change what the build produces.
A stage counts as green only for the digest it ran against, so:

  * an edit through any tool, or no tool at all, invalidates the gate;
  * `git commit` does not (the content is the same, only HEAD moved);
  * reverting an edit restores the earlier green state, which is correct;
  * switching branches invalidates it, because the content differs.
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess

# Suffixes that can change what the build produces or how it behaves.
CODE_SUFFIXES = (
    ".kt", ".kts", ".java", ".xml", ".toml", ".properties", ".pro", ".json", ".sql", ".gradle",
)
# Trees that never enter a Gradle build: prose, the harness itself, CI definitions, assets.
IGNORED_PREFIXES = (
    "docs/", "licenses/", ".claude/", "tools/", ".github/", ".idea/", "build/",
)
IGNORED_SUFFIXES = (".md", ".png", ".jpg", ".jpeg", ".svg", ".webp", ".ico", ".ttf", ".otf")
# Path fragments that mark a source file as a test — used to tell "the fix" from "the test" when
# proving the RED phase of a bug fix.
TEST_MARKERS = ("/commonTest/", "/desktopTest/", "/androidTest/", "/jvmTest/", "/test/", "Test.kt")

STATE_DIR = "skerry-gate"
STATE_FILE = "state.json"
REVIEWS_DIR = "reviews"


def git(args: list[str], cwd: str | None = None) -> tuple[int, str]:
    """Run git and return (exit code, stdout). Never raises: callers degrade instead."""
    try:
        out = subprocess.run(
            ["git"] + args, capture_output=True, text=True, timeout=30, cwd=cwd,
        )
        return out.returncode, out.stdout
    except (OSError, subprocess.SubprocessError):
        return 1, ""


def repo_root(cwd: str | None = None) -> str:
    code, out = git(["rev-parse", "--show-toplevel"], cwd)
    return out.strip() if code == 0 else ""


def git_dir(cwd: str | None = None) -> str:
    code, out = git(["rev-parse", "--absolute-git-dir"], cwd)
    return out.strip() if code == 0 else ""


def state_path(cwd: str | None = None) -> str:
    gd = git_dir(cwd)
    return os.path.join(gd, STATE_DIR, STATE_FILE) if gd else ""


def load(cwd: str | None = None) -> dict:
    path = state_path(cwd)
    if not path:
        return {}
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save(state: dict, cwd: str | None = None) -> None:
    path = state_path(cwd)
    if not path:
        return
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(state, fh, indent=2, sort_keys=True)
        os.replace(tmp, path)
    except OSError:
        pass  # the harness must never break the session it guards


def log_path(stage: str, cwd: str | None = None) -> str:
    gd = git_dir(cwd)
    return os.path.join(gd, STATE_DIR, f"{stage}.log") if gd else ""


def review_report_path(reviewer: str, cwd: str | None = None) -> str:
    gd = git_dir(cwd)
    safe = "".join(ch for ch in reviewer if ch.isalnum() or ch in "-_")
    return os.path.join(gd, STATE_DIR, REVIEWS_DIR, f"{safe}.md") if gd and safe else ""


def save_review_report(reviewer: str, text: str, cwd: str | None = None) -> str:
    """Keep a reviewer's findings on disk.

    Findings used to live only in the session context, so a compaction silently dropped the list
    of what still had to be fixed. On disk they survive it, and the next round can be scoped to
    what is actually left instead of running the whole fan-out again to rediscover it.
    """
    path = review_report_path(reviewer, cwd)
    if not path or not text.strip():
        return ""
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        header = f"# {reviewer}\n\nbranch: {current_branch(cwd)}\nat: {_now():.0f}\n\n"
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(header + text.strip() + "\n")
        return path
    except OSError:
        return ""  # the harness must never break the session it guards


def is_code(path: str) -> bool:
    """Whether a repo-relative path takes part in a Gradle build."""
    if path.startswith(IGNORED_PREFIXES) or path.endswith(IGNORED_SUFFIXES):
        return False
    if "/build/" in path:
        return False
    return path.endswith(CODE_SUFFIXES)


def is_test(path: str) -> bool:
    return any(marker in path for marker in TEST_MARKERS)


class _Absent:
    """Sentinel for a path that is tracked but no longer on disk. Not a content id."""


ABSENT = _Absent()


def _hash_file(root: str, path: str) -> str | _Absent:
    """Git's own blob id for a file on disk, or [ABSENT] if it is no longer there.

    It has to be git's, not just any hash: entries from the index arrive as blob ids, and a file
    that keeps its content while moving from untracked to tracked must keep its content id too.
    Hashing it differently made `git commit` look like an edit and reopened a gate that was green.
    """
    try:
        with open(os.path.join(root, path), "rb") as fh:
            data = fh.read()
        header = f"blob {len(data)}\0".encode()
        return hashlib.sha1(header + data).hexdigest()
    except OSError:
        return ABSENT


def worktree_entries(cwd: str | None = None) -> dict[str, str]:
    """path -> content id for every tracked or untracked file, worktree state, index bypassed."""
    root = repo_root(cwd)
    if not root:
        return {}
    entries: dict[str, str] = {}

    code, out = git(["ls-files", "-s", "-z"], cwd)
    if code == 0:
        for record in out.split("\0"):
            if not record or "\t" not in record:
                continue
            meta, path = record.split("\t", 1)
            parts = meta.split()
            if len(parts) >= 2:
                entries[path] = parts[1]  # blob id as recorded in the index

    # The index can lag behind the worktree; those files are hashed from disk instead.
    for args in (["diff", "--name-only", "-z"],
                 ["ls-files", "--others", "--exclude-standard", "-z"]):
        code, out = git(args, cwd)
        if code != 0:
            continue
        for path in out.split("\0"):
            if not path:
                continue
            content_id = _hash_file(root, path)
            # A file deleted in the worktree is dropped rather than recorded as missing: after the
            # commit the path leaves the index entirely, so recording it either way would make the
            # commit look like an edit — the same hole a non-git hash used to open, one step later.
            # Deleting still moves the digest, because the path stops being in the set at all.
            if content_id is ABSENT:
                entries.pop(path, None)
            else:
                entries[path] = content_id
    return entries


def scoped_entries(keep=None, scope: str = "all", cwd: str | None = None) -> dict[str, str]:
    """The build-relevant files a predicate keeps, as path -> content id.

    `keep` narrows the set to what one consumer actually looks at, so a reviewer of the vault is
    not invalidated by a Compose layout edit. None means every build-relevant file.
    """
    selected = {}
    for path, content_id in worktree_entries(cwd).items():
        if not is_code(path):
            continue
        if scope == "src" and is_test(path):
            continue
        if keep is not None and not keep(path):
            continue
        selected[path] = content_id
    return selected


def digest_of(entries: dict[str, str]) -> str:
    """The digest of an already-selected set — the one place the hash is defined."""
    if not entries:
        return "empty"
    joined = "\n".join(f"{path}\0{content_id}" for path, content_id in sorted(entries.items()))
    return hashlib.sha256(joined.encode()).hexdigest()[:16]


def tree_digest(scope: str = "all", cwd: str | None = None) -> str:
    """Digest of the build-relevant content of the worktree.

    scope="src" excludes test sources, so a bug fix can be told apart from the test that proves it.
    """
    return digest_of(scoped_entries(None, scope, cwd))


def current_branch(cwd: str | None = None) -> str:
    code, out = git(["rev-parse", "--abbrev-ref", "HEAD"], cwd)
    return out.strip() if code == 0 else ""


def merge_base(ref: str = "main", cwd: str | None = None) -> str:
    code, out = git(["merge-base", ref, "HEAD"], cwd)
    if code == 0 and out.strip():
        return out.strip()
    code, out = git(["rev-parse", "HEAD"], cwd)
    return out.strip() if code == 0 else ""


def changed_paths(base: str = "", cwd: str | None = None) -> list[str]:
    """Every path this branch touches: committed since `base`, plus the dirty worktree."""
    base = base or merge_base(cwd=cwd)
    paths: set[str] = set()
    if base:
        code, out = git(["diff", "--name-only", "-z", base], cwd)
        if code == 0:
            paths.update(p for p in out.split("\0") if p)
    code, out = git(["ls-files", "--others", "--exclude-standard", "-z"], cwd)
    if code == 0:
        paths.update(p for p in out.split("\0") if p)
    return sorted(paths)


def record_stage(stage: str, ok: bool, scope: str = "all", extra: dict | None = None,
                 cwd: str | None = None) -> dict:
    """Pin a stage result to the tree it ran against."""
    state = load(cwd)
    stages = state.setdefault("stages", {})
    entry = {
        "ok": ok,
        "digest": tree_digest(scope, cwd),
        "branch": current_branch(cwd),
        "at": _now(),
    }
    if extra:
        entry.update(extra)
    stages[stage] = entry
    save(state, cwd)
    return entry


def stage_is_current(state: dict, stage: str, digest: str) -> bool:
    entry = (state.get("stages") or {}).get(stage) or {}
    return bool(entry.get("ok")) and entry.get("digest") == digest


def _now() -> float:
    import time
    return time.time()
