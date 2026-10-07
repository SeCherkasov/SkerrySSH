"""Pure change planning, independent of plugins and execution state."""
from __future__ import annotations

import sys
import os
from dataclasses import dataclass
from . import state

PY = sys.executable
MODULES = ("sync-wire", "server", "shared", "composeApp", "androidApp")
UPSTREAM = {"sync-wire": (), "server": ("sync-wire",), "shared": ("sync-wire",),
            "composeApp": ("shared", "sync-wire"),
            "androidApp": ("composeApp", "shared", "sync-wire")}
# desktopTest in shared boots the real server; this dependency belongs to test/build evidence,
# not Android/production compilation. A server edit schedules this integration suite only.
TEST_UPSTREAM = {"shared": ("server",)}
TEST_TASKS = {"server": "test", "shared": "desktopTest", "composeApp": "desktopTest",
              "androidApp": "testDebugUnitTest"}
KINDS = ("docs", "refactor", "feature", "bug")
BRANCH_KINDS = {"fix": "bug", "bug": "bug", "hotfix": "bug", "feat": "feature",
                "feature": "feature", "refactor": "refactor", "chore": "refactor",
                "perf": "refactor", "test": "refactor", "docs": "docs"}
SECURITY_SEGMENTS = ("/ssh/", "/sftp/", "/telnet/", "/serial/", "/mosh/", "/rdp/", "/vnc/",
                     "/terminal/", "/graphics/", "/audio/", "/tunnel/", "/container/", "/vault/",
                     "/guard/", "/team/", "/share/", "/sync/", "/trust/", "crypto", "Crypto",
                     "server/", "sync-wire/")


def harness_input(path):
    return (path.startswith(("tools/harness/", ".agents/hooks/", ".agents/reviewers/", ".codex/", ".github/"))
            or path in state.AGENT_FILES)


def product_input(path):
    return (not harness_input(path) and not path.startswith(state.IGNORED_PREFIXES)
            and "/build/" not in path and not path.endswith(".md")
            and path not in ("LICENSE", "COPYING", "NOTICE"))


def module_of(path):
    head = path.split("/", 1)[0]
    return head if head in MODULES else ""


def areas(paths):
    found = set()
    for path in paths:
        if harness_input(path):
            found.add("harness")
        module = module_of(path)
        if module:
            found.add({"composeApp": "ui", "androidApp": "android", "sync-wire": "server"}.get(module, module))
        if "/androidMain/" in path:
            found.add("android")
        if "/desktopMain/" in path:
            found.add("desktop")
        if any(segment in path for segment in SECURITY_SEGMENTS):
            found.add("security")
        if "/terminal/" in path or "/graphics/" in path:
            found.add("terminal")
        if "composeResources/" in path or "/res/values" in path:
            found.add("i18n")
        if path.endswith((".gradle.kts", ".versions.toml")) or path.startswith("gradle/"):
            found.add("build")
    return sorted(found)


def classify(cwd=None):
    from . import evidence
    branch = state.current_branch(cwd)
    paths = state.changed_paths(cwd=cwd)
    if os.environ.get("CI") == "true" and state.merge_base(cwd=cwd) == state.git(["rev-parse", "HEAD"], cwd)[1].strip():
        paths = sorted(state.worktree_entries(cwd))  # main CI verifies the full tree, not an empty self-diff
    code = [p for p in paths if harness_input(p) or product_input(p)]
    named = BRANCH_KINDS.get(branch.split("/", 1)[0], "feature")
    kind = (named if named != "docs" else "feature") if code else "docs"
    declared = evidence.load(cwd).get("tasks", {}).get(branch, {}).get("kind")
    if declared in KINDS and KINDS.index(declared) >= KINDS.index(kind):
        kind = declared
    return {"kind": kind, "branch": branch, "base": state.merge_base(cwd=cwd), "paths": paths,
            "code_paths": code, "areas": areas(code)}


def affected_modules(task):
    modules = {module_of(p) for p in task["code_paths"] if not harness_input(p)}
    if "" in modules:
        return MODULES
    modules |= {m for m in MODULES if any(up in modules for up in UPSTREAM[m])}
    return tuple(m for m in MODULES if m in modules)


@dataclass(frozen=True)
class Stage:
    name: str
    command: tuple[str, ...] = ()
    modules: tuple[str, ...] = ()
    test_task: str = ""

    def inputs(self, cwd=None):
        entries = state.worktree_entries(cwd)
        if self.name == "checks":
            return {p: cid for p, cid in entries.items() if harness_input(p) or product_input(p)}
        if self.name == "selftest":
            return {p: cid for p, cid in entries.items() if harness_input(p)}
        return {p: cid for p, cid in entries.items() if product_input(p)
                and (not module_of(p) or module_of(p) in self.modules)}


def plan(mode="final", cwd=None):
    task = classify(cwd)
    if task["kind"] == "docs":
        return []
    modules = affected_modules(task)
    stages = [Stage("checks")]
    if "harness" in task["areas"]:
        stages.append(Stage("selftest", (PY, "tools/harness/selftest.py")))
    flags = ("--max-workers=2", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process")
    if os.environ.get("CI") == "true":
        flags += ("-PskerryCi=1",)
    test_modules = set(modules)
    for module, dependencies in TEST_UPSTREAM.items():
        if any(up in modules for up in dependencies):
            test_modules.add(module)
    for module in (m for m in MODULES if m in test_modules):
        scope = (module,) + UPSTREAM[module] + TEST_UPSTREAM.get(module, ())
        test_task = TEST_TASKS.get(module)
        if module == "sync-wire" and any(p.startswith("sync-wire/src/test/") for p in state.worktree_entries(cwd)):
            test_task = "test"
        if test_task:
            server_only = ("-PserverOnly",) if module in ("server", "sync-wire") else ()
            command = ("./gradlew", f":{module}:{test_task}", "--rerun") + flags + server_only
            stages.append(Stage(f"tests:{module}", command, scope, test_task))
    if mode == "final":
        for module in modules:
            scope = (module,) + UPSTREAM[module]
            server_only = ("-PserverOnly",) if module in ("server", "sync-wire") else ()
            stages += [Stage(f"build:{module}", ("./gradlew", f":{module}:build") + flags + server_only,
                             scope + TEST_UPSTREAM.get(module, ())),
                       Stage(f"detekt:{module}", ("./gradlew", f":{module}:detekt") + flags + server_only, scope)]
    return stages


def reviewer_paths(name, cwd=None):
    changed = set(classify(cwd)["code_paths"])
    entries = state.worktree_entries(cwd)
    def relevant(path):
        if name == "skerry-kotlin-reviewer":
            return path.endswith((".kt", ".kts"))
        if name == "skerry-security-reviewer":
            return any(segment in path for segment in SECURITY_SEGMENTS)
        return True
    selected = {p: entries.get(p, "deleted") for p in changed if relevant(p)}
    for path in (f".agents/reviewers/{name}.md", f".codex/agents/{name}.toml"):
        if selected and path in entries:
            selected[path] = entries[path]
    return selected


def reviewers(cwd=None):
    if classify(cwd)["kind"] == "docs":
        return []
    return [name for name in ("skerry-reviewer", "skerry-kotlin-reviewer", "skerry-security-reviewer")
            if reviewer_paths(name, cwd)]
