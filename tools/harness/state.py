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
import re
import subprocess

# Suffixes that can change what the build produces or how it behaves.
CODE_SUFFIXES = (
    ".kt", ".kts", ".java", ".xml", ".toml", ".properties", ".pro", ".json", ".sql", ".gradle",
    # The harness is code the gate depends on: editing a rule used to move no digest at all, so a
    # rule could be deleted after its stage had already been recorded green.
    ".py",
)
# Trees that never enter a build: prose, CI definitions, assets.
IGNORED_PREFIXES = (
    "docs/", "licenses/", ".github/", ".idea/", "build/",
)
# Instructions the gate executes: the reviewers it launches, and the slash commands
# that drive the fan-out and the kind declaration. Prose everywhere else stays prose.
AGENT_PREFIX = (".claude/agents/", ".claude/commands/")
IGNORED_SUFFIXES = (".md", ".png", ".jpg", ".jpeg", ".svg", ".webp", ".ico", ".ttf", ".otf")
# Path fragments that mark a source file as a test — used to tell "the fix" from "the test" when
# proving the RED phase of a bug fix.
TEST_MARKERS = ("/commonTest/", "/desktopTest/", "/androidTest/", "/jvmTest/", "/test/", "Test.kt",
                # The harness's own suite, for the same reason: a RED phase must not be closable
                # by weakening the test that proved the bug.
                "selftest.py")

# Written out from code points on purpose: the subject is invisible characters, and a literal one
# here would be unreviewable. C0 minus tab/LF/CR, then the zero-width and bidi overrides.
CONTROL_RANGES = (
    (0x00, 0x08), (0x0B, 0x0C), (0x0E, 0x1F), (0x7F, 0x9F),  # C0 minus tab/LF/CR, DEL, C1
    (0x061C, 0x061C),                                        # Arabic letter mark, a bidi control
    (0x200B, 0x200F), (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x2069), (0xFEFF, 0xFEFF),
    (0x2028, 0x2029),  # line and paragraph separators: invisible, and Python splits lines on them
)
CONTROL_CHARS = re.compile(
    "[" + "".join(f"\\U{lo:08X}-\\U{hi:08X}" for lo, hi in CONTROL_RANGES) + "]")
# Roughly a long review round. A runaway report is a broken reviewer, not findings to keep.
REPORT_CHARS = 200_000

STATE_DIR = "skerry-gate"
STATE_FILE = "state.json"
REVIEWS_DIR = "reviews"


def git(args: list[str], cwd: str | None = None) -> tuple[int, str]:
    """Run git and return (exit code, stdout). Never raises: callers degrade instead."""
    try:
        env = dict(os.environ)
        # A file named `:(attr:x)Payload.kt` is a pathspec that matches nothing, so its content is
        # never diffed and no rule ever sees it.
        env["GIT_LITERAL_PATHSPECS"] = "1"
        out = subprocess.run(
            ["git"] + args, capture_output=True, text=True, errors="replace", timeout=30, cwd=cwd,
            env=env,
        )
        return out.returncode, out.stdout
    except (OSError, subprocess.SubprocessError, ValueError):
        # errors="replace" is the point: git hands back path names as raw bytes, and one file whose
        # name is not UTF-8 used to raise out of every digest — into the commit guard, which
        # catches everything and allows. A name the harness cannot spell is not a gate it skips.
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


def save(state: dict, cwd: str | None = None) -> bool:
    """Persist the gate state. False when it did not land — a caller that reports success to the
    user has to know; the harness itself still never raises, so nothing it guards breaks."""
    path = state_path(cwd)
    if not path:
        return False
    try:
        # The file carries the branch, every path in the change and the reviewers' verdicts.
        # `os.open`'s mode only applies to a file it creates, so the modes are set again after the
        # rename: a directory or a leftover `.tmp` from an older version keeps its own otherwise.
        directory = os.path.dirname(path)
        os.makedirs(directory, mode=0o700, exist_ok=True)
        if os.stat(directory).st_mode & 0o077:
            # Only when it is actually loose: a directory made read-only on purpose stays that
            # way, and a save into it fails the way the caller expects.
            os.chmod(directory, 0o700)
        tmp = path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(state, fh, indent=2, sort_keys=True)
        os.replace(tmp, path)
        os.chmod(path, 0o600)
        return True
    except OSError:
        return False  # the harness must never break the session it guards


def log_path(stage: str, cwd: str | None = None) -> str:
    gd = git_dir(cwd)
    return os.path.join(gd, STATE_DIR, f"{stage}.log") if gd else ""


def review_report_path(reviewer: str, cwd: str | None = None) -> str:
    gd = git_dir(cwd)
    safe = "".join(ch for ch in reviewer if ch.isalnum() or ch in "-_")
    return os.path.join(gd, STATE_DIR, REVIEWS_DIR, f"{safe}.md") if gd and safe else ""


def save_review_report(reviewer: str, text: str, cwd: str | None = None) -> str:
    """Keep a reviewer's findings on disk, one round after another.

    Findings used to live only in the session context, so a compaction silently dropped the list
    of what still had to be fixed. On disk they survive it, and the next round can be scoped to
    what is actually left instead of running the whole fan-out again to rediscover it. Rounds are
    appended rather than replaced: a round-1 item the next pass does not repeat is still owed.

    The text is a reviewer's quotation of what it read — terminal fixtures, file names off an SFTP
    listing, an AI transcript. Escape sequences in it would run in the operator's terminal on a
    `cat`, so they are stripped, and the length is capped: nothing that arrives here is trusted
    beyond being someone's prose.
    """
    path = review_report_path(reviewer, cwd)
    body = CONTROL_CHARS.sub("", text.strip())[:REPORT_CHARS]
    if not path or not body:
        return ""
    try:
        directory = os.path.dirname(path)
        os.makedirs(directory, mode=0o700, exist_ok=True)
        header = f"\n# {reviewer}\n\nbranch: {current_branch(cwd)}\nat: {_now():.0f}\n\n"
        # 0600 through os.open: the file is a verbatim copy of whatever path the operator named,
        # and `.git` is never cleaned. A default-mode open leaves it world-readable for good.
        # Both mode arguments below are ignored when the target already exists, so a directory or
        # a report written before this rule stayed world-readable. Tighten first, so the round
        # about to be appended is never briefly world-readable.
        os.chmod(directory, 0o700)
        if os.path.exists(path):
            os.chmod(path, 0o600)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as fh:
            fh.write(header + body + "\n")
        return path
    except OSError:
        return ""  # the harness must never break the session it guards


def is_code(path: str) -> bool:
    """Whether a repo-relative path takes part in a Gradle build, or in the gate that guards it."""
    # Prose everywhere else, but an agent definition is what a reviewer executes: editing one
    # changes what the review gate looks for, and it used to move no digest at all.
    if path.startswith(AGENT_PREFIX):
        return True
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
