#!/usr/bin/env python3
"""PreToolUse guard on Bash: nothing leaves this worktree ungated.

Two rules from CLAUDE.md -> How we work:

  * `main` is protected — committing or pushing while HEAD is on main is blocked outright.
  * A change may only be committed, pushed or turned into a PR once it has met the requirements
    for what it *is*. A docs change owes nothing. A feature owes checks, tests, build, detekt,
    the Android compile when it touches UI, and the reviewer fan-out. A bug fix owes all of that
    plus a test recorded failing before the fix existed.

The requirements come from tools/harness/policy.py, the same module `gate.py status` reports from,
so what the guard demands and what the runner reports can never disagree.

`SKERRY_GATE_OVERRIDE=1` skips the gate requirement — deliberately, and out loud to the user. It
does not unprotect main.
"""

import json
import os
import re
import sys

HOOK_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HOOK_DIR))
sys.path.insert(0, os.path.join(REPO_ROOT, "tools"))

try:
    from harness import policy, state
except ImportError:  # a worktree without the harness must still be usable
    policy = state = None

# `rtk` transparently prefixes shell commands in this environment, so match through it.
GIT_WRITE = re.compile(r"(?:^|[;&|]|\s)(?:rtk\s+)?git\s+(?:-\S+\s+)*(commit|push)(?![\w-])")
PR_CREATE = re.compile(r"(?:^|[;&|]|\s)(?:rtk\s+)?gh\s+pr\s+create\b")
# A hook reads the environment of the process that spawned it, not the one the command runs in,
# so the documented escape hatch is recognised in the command text as well.
OVERRIDE = re.compile(r"\bSKERRY_GATE_OVERRIDE=1\b")


def block(message: str) -> None:
    sys.stderr.write(message + "\n")
    sys.exit(2)


def ask(reason: str) -> None:
    json.dump({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": reason,
        }
    }, sys.stdout)
    sys.exit(0)


def describe(task: dict, debt: list) -> str:
    areas = ", ".join(task["areas"]) or "none"
    lines = [
        f"Blocked: this is a **{task['kind']}** change ({task['source']}), areas: {areas}.",
        "It still owes:",
    ]
    lines += [f"  - {item}" for item in debt]
    lines += [
        "",
        "Close it with `tools/harness/gate.py run` (build stages) and the reviewer fan-out from",
        "`/gate`; `tools/harness/gate.py status` shows what is left. If the kind is wrong, declare",
        "it: `tools/harness/gate.py task <bug|feature|refactor|docs> [ref]`.",
        "Deliberate bypass: prefix the command with SKERRY_GATE_OVERRIDE=1 and say why to the user.",
    ]
    return "\n".join(lines)


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)  # never break the session on a malformed payload

    command = (payload.get("tool_input") or {}).get("command") or ""
    if not command:
        sys.exit(0)

    write = GIT_WRITE.search(command)
    pr = PR_CREATE.search(command)
    if not write and not pr:
        sys.exit(0)  # the expensive part below runs only for the three commands that matter

    if state is None:
        sys.exit(0)

    if write and state.current_branch() == "main":
        block(
            f"Blocked: `git {write.group(1)}` while HEAD is on main. This repo is PR-only — create "
            "a feature branch (`git checkout -b <kind>/<slug>`; the prefix also tells the harness "
            "what kind of change this is) and open a PR. Carry the working tree over with the "
            "branch; do not stash or reset it."
        )

    if os.environ.get("SKERRY_GATE_OVERRIDE") == "1" or OVERRIDE.search(command):
        sys.exit(0)

    try:
        task, debt = policy.gate_debt()
    except Exception as exc:  # a broken harness must not wedge the repo
        sys.stderr.write(f"harness: gate check failed ({type(exc).__name__}: {exc}); allowing.\n")
        sys.exit(0)

    if debt:
        block(describe(task, debt))

    if pr:
        ask(
            f"Opening a PR for a {task['kind']} change. The gate is green for the current tree: "
            f"{', '.join(policy.required_stages(task)) or 'nothing required'}. Confirm the reviewer "
            "findings were triaged (fixed or rejected with a reason to the user), and that the PR "
            "description says what was not verified live."
        )

    sys.exit(0)


if __name__ == "__main__":
    main()
