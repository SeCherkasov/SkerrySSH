"""What the gate demands, given what the change actually is.

One gate for every change was the previous version's real weakness: a README fix paid for a full
Gradle run and a five-agent fan-out, while a bug fix could land with no test reproducing the bug —
the single rule that matters most for bugs. Here the requirements are a function of two things:

  * the **kind** of change (bug / feature / refactor / docs), which decides what tests must prove;
  * the **areas** it touches, which decide which build stages and which reviewers are relevant.
"""

from __future__ import annotations

import glob
import os

from . import state

KINDS = ("bug", "feature", "refactor", "docs")

# Branch prefixes are the cheapest honest signal: they are chosen before the work starts.
BRANCH_KINDS = {
    "fix": "bug", "bug": "bug", "bugfix": "bug", "hotfix": "bug", "issue": "bug",
    "feat": "feature", "feature": "feature",
    "refactor": "refactor", "chore": "refactor", "perf": "refactor", "test": "refactor",
    "docs": "docs", "doc": "docs",
}

# Area -> predicate over a repo-relative path.
AREA_RULES = (
    ("ui", lambda p: p.startswith("composeApp/")),
    ("android", lambda p: p.startswith("androidApp/") or "/androidMain/" in p),
    ("desktop", lambda p: "/desktopMain/" in p),
    ("shared", lambda p: p.startswith("shared/")),
    ("server", lambda p: p.startswith(("server/", "sync-wire/"))),
    ("i18n", lambda p: "composeResources/" in p or "/res/values" in p),
    ("security", lambda p: any(seg in p for seg in ("/vault/", "/guard/", "/team/", "/share/",
                                                    "/sync/", "crypto", "Crypto"))),
    ("terminal", lambda p: "/terminal/" in p or "/graphics/" in p),
    ("build", lambda p: p.endswith((".gradle.kts", ".versions.toml")) or p.startswith("gradle/")),
)

# Stage -> the command the runner uses. "checks" is internal (tools/harness/checks.py).
STAGE_COMMANDS = {
    "tests": ["./gradlew", "test", "allTests"],
    "build": ["./gradlew", "build"],
    "detekt": ["./gradlew", "detektAll"],
    "android": ["./gradlew", ":androidApp:compileDebugKotlin"],
}

# The reviewers this repository owns. The generic Kotlin and security agents were replaced by
# project-local ones: the generic Kotlin reviewer spends its pass on domain/data modules,
# ViewModels, Room and NavController — none of which exist here — and a generic security reviewer
# looks for web vulnerabilities in an SSH client. Everything a repo-local file cannot say better
# than the plugin stays with the plugin.
BASE_REVIEWERS = (
    "skerry-reviewer",
    "skerry-kotlin-reviewer",
    "skerry-security-reviewer",
    "silent-failure-hunter",
    "pr-test-analyzer",
)
AREA_REVIEWERS = {
    "ui": ("a11y-architect",),
    "server": ("java-reviewer",),
    "terminal": ("performance-optimizer",),
}

PLUGIN_GLOBS = (
    "~/.claude/plugins/cache/*/*/*/agents/{name}.md",
    "~/.claude/plugins/marketplaces/*/agents/{name}.md",
)


def agent_id(reviewer: str) -> str:
    return reviewer if reviewer.startswith("skerry-") else f"ecc:{reviewer}"


def is_installed(reviewer: str, cwd: str | None = None) -> bool:
    """Whether this reviewer can actually be launched here.

    Repo-local agents ship with the clone and are always available. Plugin agents are not declared
    anywhere in the repository — they live in the operator's plugin cache — so a contributor
    without the plugin would face a gate that cannot be closed by any action they can take.
    Those requirements degrade instead: the gate reports them as skipped rather than owed.
    """
    if reviewer.startswith("skerry-"):
        root = state.repo_root(cwd)
        return bool(root) and os.path.exists(os.path.join(root, ".claude", "agents",
                                                          f"{reviewer}.md"))
    return any(glob.glob(os.path.expanduser(pattern.format(name=reviewer)))
               for pattern in PLUGIN_GLOBS)


def kind_from_branch(branch: str) -> str:
    """`fix/reconcile-debt` -> bug. Empty when the branch name says nothing."""
    head = branch.split("/", 1)[0].lower()
    return BRANCH_KINDS.get(head, "")


def areas(paths: list[str]) -> list[str]:
    found = set()
    for path in paths:
        for name, matches in AREA_RULES:
            if matches(path):
                found.add(name)
    return sorted(found)


def classify(cwd: str | None = None) -> dict:
    """The task at hand: kind, where it came from, and which areas it touches.

    An explicit `/task` declaration wins, but only on the branch it was made for — carrying a
    "docs" declaration onto the next branch is exactly the hole a declaration-only design has.
    """
    branch = state.current_branch(cwd)
    paths = state.changed_paths(cwd=cwd)
    code_paths = [p for p in paths if state.is_code(p)]
    task_areas = areas(paths)

    declared = (state.load(cwd).get("task") or {})
    # A declaration can make the gate stricter (this "chore" is really a bug fix) but never looser
    # than the diff itself proves: calling a change with Kotlin in it "docs" would disarm the gate
    # with one command, which is the whole thing this harness exists to prevent.
    if declared.get("kind") in KINDS and declared.get("branch") == branch:
        if not (declared["kind"] == "docs" and code_paths):
            return {
                "kind": declared["kind"], "source": "declared", "ref": declared.get("ref", ""),
                "areas": task_areas, "paths": paths, "code_paths": code_paths, "branch": branch,
            }

    # No code in the change means no build can be affected, whatever the branch is called.
    if not code_paths:
        kind, source = "docs", "no code in diff"
    else:
        kind = kind_from_branch(branch)
        source = f"branch `{branch}`" if kind else "default"
        if not kind:
            kind = "feature"  # the strictest kind that does not demand a reproduction test
    return {
        "kind": kind, "source": source, "ref": "", "areas": task_areas,
        "paths": paths, "code_paths": code_paths, "branch": branch,
    }


def required_stages(task: dict) -> list[str]:
    if task["kind"] == "docs":
        return []
    stages = ["checks", "tests", "build", "detekt"]
    if {"ui", "android"} & set(task["areas"]):
        stages.append("android")
    if task["kind"] == "bug":
        stages.append("red")
    stages.append("review")
    return stages


def relevant_reviewers(task: dict) -> list[str]:
    """Everyone this change should be seen by, installed or not."""
    if task["kind"] == "docs":
        return []
    reviewers = list(BASE_REVIEWERS)
    for area in task["areas"]:
        for extra in AREA_REVIEWERS.get(area, ()):
            if extra not in reviewers:
                reviewers.append(extra)
    return reviewers


def required_reviewers(task: dict, cwd: str | None = None) -> list[str]:
    """Those the gate can actually demand — the rest are reported as skipped, not owed."""
    return [name for name in relevant_reviewers(task) if is_installed(name, cwd)]


def red_is_proven(st: dict, src_digest: str, branch: str) -> tuple[bool, str]:
    """Whether some test was recorded failing *before* the current source state existed.

    A recorded RED whose source digest still matches the tree means nothing was fixed since — the
    test is still red, or the record belongs to an earlier state. Records are scoped to the branch
    they were made on, so a proof does not follow the worktree onto the next piece of work.
    """
    records = [r for r in (st.get("red") or []) if r.get("branch") == branch]
    if not records:
        return False, ("no test was recorded failing before the fix — run "
                       "`tools/harness/gate.py red --tests '<pattern>' --file <test file>`")
    for record in records:
        if record.get("src_digest") and record.get("src_digest") != src_digest:
            return True, f"{record.get('task', '?')} --tests {record.get('pattern', '?')}"
    return False, "the sources have not changed since the test was recorded failing — no fix yet"


def gate_debt(cwd: str | None = None) -> tuple[dict, list[str]]:
    """The task, and every requirement it has not met yet, in the order they should be run."""
    task = classify(cwd)
    stages = required_stages(task)
    if not stages:
        return task, []

    st = state.load(cwd)
    digest = state.tree_digest("all", cwd)
    debt: list[str] = []

    for stage in stages:
        if stage == "red":
            proven, why = red_is_proven(st, state.tree_digest("src", cwd), task["branch"])
            if not proven:
                debt.append(f"red — {why}")
            continue
        if stage == "review":
            missing = missing_reviewers(st, digest, task, cwd)
            if missing:
                debt.append("review — missing: " + ", ".join(missing))
            continue
        if not state.stage_is_current(st, stage, digest):
            entry = (st.get("stages") or {}).get(stage) or {}
            why = "never run" if not entry else (
                "failed" if not entry.get("ok") else "ran against different code")
            debt.append(f"{stage} — {why}")
    return task, debt


def missing_reviewers(st: dict, digest: str, task: dict, cwd: str | None = None) -> list[str]:
    seen = (st.get("reviews") or {})
    return [name for name in required_reviewers(task, cwd)
            if (seen.get(name) or {}).get("digest") != digest]


def skipped_reviewers(task: dict, cwd: str | None = None) -> list[str]:
    """Relevant but not installed here — reported so a thinner review is never silent."""
    return [name for name in relevant_reviewers(task) if not is_installed(name, cwd)]
