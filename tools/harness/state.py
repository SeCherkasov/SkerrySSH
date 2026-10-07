"""Git content identity shared by the planner and deterministic rules."""
from __future__ import annotations
import hashlib
import os
import re
import subprocess

CODE_SUFFIXES = (".kt", ".kts", ".java", ".xml", ".toml", ".properties", ".pro", ".json", ".sql", ".gradle", ".py", ".sh", ".yml", ".yaml")
IGNORED_PREFIXES = ("docs/", "licenses/", ".idea/", "build/")
AGENT_FILES = ("AGENTS.md", "docs/development-process.md", "docs/coding-guidelines.md")
AGENT_PREFIX = (".agents/reviewers/", ".codex/agents/", ".github/workflows/", "tools/harness/hooks/")
IGNORED_SUFFIXES = (".md", ".png", ".jpg", ".jpeg", ".svg", ".webp", ".ico", ".ttf", ".otf")
TEST_MARKERS = ("/commonTest/", "/desktopTest/", "/androidTest/", "/jvmSharedTest/", "/jvmTest/", "/test/", "Test.kt", "selftest.py", "tools/harness/tests/")
CONTROL_RANGES = ((0x00, 0x08), (0x0B, 0x0C), (0x0E, 0x1F), (0x7F, 0x9F), (0x061C, 0x061C), (0x200B, 0x200F), (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x2069), (0xFEFF, 0xFEFF), (0x2028, 0x2029))
CONTROL_CHARS = re.compile("[" + "".join(f"\\U{lo:08X}-\\U{hi:08X}" for lo, hi in CONTROL_RANGES) + "]")

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


def checked_git(args, cwd=None):
    code, out = git(args, cwd)
    if code:
        raise ValueError("Git could not read repository state: git " + " ".join(args))
    return out


def git_dir(cwd: str | None = None) -> str:
    code, out = git(["rev-parse", "--absolute-git-dir"], cwd)
    return out.strip() if code == 0 else ""


def is_code(path: str) -> bool:
    """Whether a repo-relative path takes part in a Gradle build, or in the gate that guards it."""
    # Prose everywhere else, but an agent definition is what a reviewer executes: editing one
    # changes what the review gate looks for, and it used to move no digest at all.
    if path in AGENT_FILES or path.startswith(AGENT_PREFIX):
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
        full = os.path.join(root, path)
        if os.path.islink(full):
            data = os.fsencode(os.readlink(full))
        else:
            with open(full, "rb") as fh:
                data = fh.read()
        header = f"blob {len(data)}\0".encode()
        return hashlib.sha1(header + data).hexdigest()
    except OSError as exc:
        if os.path.lexists(os.path.join(root, path)):
            raise ValueError(f"cannot read build input {path}: {exc}") from exc
        return ABSENT


def worktree_entries(cwd: str | None = None) -> dict[str, str]:
    """path -> content id for every tracked or untracked file, worktree state, index bypassed."""
    root = repo_root(cwd)
    if not root:
        return {}
    entries: dict[str, str] = {}

    out = checked_git(["ls-files", "-s", "-z"], cwd)
    if out:
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
        out = checked_git(args, cwd)
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
    for path in list(entries):
        full = os.path.join(root, path)
        try:
            mode = "symlink" if os.path.islink(full) else "executable" if os.stat(full).st_mode & 0o111 else "file"
            entries[path] += ":" + mode
        except OSError as exc:
            raise ValueError(f"cannot fingerprint input {path}: {exc}") from exc
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
    for candidate in (ref, "origin/main"):
        code, out = git(["merge-base", candidate, "HEAD"], cwd)
        if code == 0 and out.strip():
            return out.strip()
    raise ValueError("cannot resolve a main merge-base; fetch main with history before verification")


def changed_paths(base: str = "", cwd: str | None = None) -> list[str]:
    """Every path this branch touches: committed since `base`, plus the dirty worktree."""
    base = base or merge_base(cwd=cwd)
    paths: set[str] = set()
    if base:
        out = checked_git(["diff", "--name-only", "-z", base], cwd)
        paths.update(p for p in out.split("\0") if p)
    out = checked_git(["ls-files", "--others", "--exclude-standard", "-z"], cwd)
    paths.update(p for p in out.split("\0") if p)
    return sorted(paths)
