"""Explain prerequisites and install tool-independent Git adapters without overwriting hooks."""
import os
import re
import shutil
import subprocess
from pathlib import Path
from . import agents, evidence, policy, state


def doctor(cwd=None):
    root = state.repo_root(cwd)
    checks = []
    def item(name, ok, detail, required=False):
        checks.append({"name": name, "status": "ok" if ok else "error" if required else "warning",
                       "detail": detail})
    item("git", bool(root), root, True)
    item("python", __import__("sys").version_info >= (3, 11), "Python 3.11+ required", True)
    stages = policy.plan("final", cwd)
    gradle = [s for s in stages if s.command and s.command[0] == "./gradlew"]
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home, "bin/java")) if java_home else shutil.which("java")
    try:
        proc = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=10)
        version = (proc.stderr + proc.stdout).splitlines()[0]
    except (OSError, TypeError, IndexError, subprocess.SubprocessError):
        version = "java unavailable"
    item("jdk", bool(re.search(r'version "21[.\"]', version)), version, bool(gradle))
    android_required = any("-PserverOnly" not in s.command for s in gradle)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = Path(root, "local.properties")
    if not sdk and local.exists():
        match = re.search(r"^sdk.dir=(.+)$", local.read_text(), re.M)
        sdk = match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\") if match else ""
    item("android-sdk", bool(sdk and Path(sdk).is_dir()), sdk or "set ANDROID_HOME or sdk.dir", android_required)
    display = bool(os.environ.get("DISPLAY")) or os.name == "nt" or __import__("sys").platform == "darwin"
    ui_tests = any(s.name == "tests:composeApp" for s in stages)
    item("display", display, "Use xvfb-run for Compose tests without DISPLAY", ui_tests)
    config = state.git(["config", "--get", "core.hooksPath"], cwd)[1].strip()
    item("git-hooks", config == "tools/harness/hooks", config or "run gate.py install-hooks")
    item("agent-profiles", bool(agents.configuration(cwd)), "explicit model/effort profiles loaded", True)
    item("legacy-state", not Path(state.git_dir(cwd), "skerry-gate/state.json").exists(),
         "old receipts are preserved for history, never accepted as v2 evidence")
    return checks


def install_hooks(cwd=None):
    existing = state.git(["config", "--get", "core.hooksPath"], cwd)[1].strip()
    if existing and existing != "tools/harness/hooks":
        raise ValueError(f"existing core.hooksPath={existing}; preserve and integrate those hooks explicitly")
    traditional = Path(state.git_dir(cwd), "hooks")
    if not existing and any((traditional / name).exists() for name in ("pre-commit", "pre-push")):
        raise ValueError("existing Git hooks found; preserve and integrate them explicitly")
    code, _ = state.git(["config", "core.hooksPath", "tools/harness/hooks"], cwd)
    if code:
        raise ValueError("cannot configure Git hooks")


def guard(kind, pushed="", cwd=None):
    if state.current_branch(cwd).split("/", 1)[0] not in policy.BRANCH_KINDS:
        raise ValueError("delivery requires a typed working branch; main and detached HEAD are protected")
    if kind == "push":
        head = state.git(["rev-parse", "HEAD"], cwd)[1].strip()
        for line in pushed.splitlines():
            local_ref, local_sha, remote_ref, _ = line.split()
            if local_sha == "0" * 40:
                continue  # branch deletion carries no code
            if remote_ref == "refs/heads/main" or local_sha != head:
                raise ValueError("push only this verified HEAD to a non-main ref; verify other refs separately")
        if state.checked_git(["status", "--porcelain"], cwd).strip():
            raise ValueError("push requires a clean worktree matching HEAD")
    elif state.checked_git(["diff", "--name-only", "-z"], cwd):
        raise ValueError("stage the complete change before commit: verified worktree must match index")
    from . import gate
    owed = gate.debt("final", cwd)
    if owed:
        raise ValueError("delivery blocked: " + "; ".join(owed))
