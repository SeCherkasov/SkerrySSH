#!/usr/bin/env python3
"""PreToolUse guard for Bash commands in the Skerry repo.

Three rules, all from CLAUDE.md -> How we work:
  * `main` is protected: committing or pushing while HEAD is on main is blocked outright.
  * Code that changed after the last gate run cannot be committed or pushed: the build/detekt run
    and the reviewer fan-out must both post-date the last .kt/.kts edit (state written by
    gate-state.py). Set SKERRY_GATE_OVERRIDE=1 to bypass deliberately.
  * `gh pr create` asks for confirmation on top of that.

Exit 2 blocks the call and hands stderr to the model; a JSON body on stdout with exit 0
downgrades the call to an explicit confirmation.
"""

import json
import os
import re
import subprocess
import sys
import time

# `rtk` transparently prefixes shell commands in this environment, so match through it.
GIT_WRITE = re.compile(r"(?:^|[;&|]|\s)(?:rtk\s+)?git\s+(?:-\S+\s+)*(commit|push)(?![\w-])")
PR_CREATE = re.compile(r"(?:^|[;&|]|\s)(?:rtk\s+)?gh\s+pr\s+create\b")


def current_branch() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def gate_debt() -> list:
    """Which gate stages are stale relative to the last code edit."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--git-dir"], capture_output=True, text=True, timeout=5
        )
        path = os.path.join(out.stdout.strip(), "skerry-gate-state.json")
        with open(path) as fh:
            state = json.load(fh)
    except (OSError, ValueError, subprocess.SubprocessError):
        return []  # no state yet — nothing was tracked, so nothing to claim

    code = state.get("code")
    if not code:
        return []
    stale = []
    if code > state.get("build", 0):
        stale.append("build + tests + detekt")
    if code > state.get("review", 0):
        stale.append("reviewer fan-out")
    if stale:
        age = int(time.time() - code)
        stale.append(f"(last code edit {age}s ago)")
    return stale


def ask(reason: str) -> None:
    json.dump({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": reason,
        }
    }, sys.stdout)
    sys.exit(0)


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)  # never break the session on a malformed payload

    command = (payload.get("tool_input") or {}).get("command") or ""
    if not command:
        sys.exit(0)

    match = GIT_WRITE.search(command)
    if match and current_branch() == "main":
        verb = match.group(1)
        sys.stderr.write(
            f"Blocked: `git {verb}` while HEAD is on main. This repo is PR-only — "
            "create a feature branch (`git checkout -b <type>/<slug>`) and open a PR. "
            "Carry the working tree over with the branch; do not stash or reset it.\n"
        )
        sys.exit(2)

    if (match or PR_CREATE.search(command)) and os.environ.get("SKERRY_GATE_OVERRIDE") != "1":
        stale = gate_debt()
        if stale:
            sys.stderr.write(
                "Blocked: code changed after the last gate run — stale: "
                + ", ".join(stale)
                + ". Run /gate (build + tests + detekt, then the reviewer fan-out) and triage the "
                "findings first. If this is deliberate, re-run with SKERRY_GATE_OVERRIDE=1 and say "
                "why in the message to the user.\n"
            )
            sys.exit(2)

    if PR_CREATE.search(command):
        ask(
            "Opening a PR. Confirm the pre-PR gate actually ran on this branch: "
            "tests + build + Android compile green, and the review fan-out "
            "(skerry-reviewer, ecc:kotlin-reviewer, ecc:security-reviewer, "
            "ecc:silent-failure-hunter) triaged. Run /gate if it did not."
        )

    sys.exit(0)


if __name__ == "__main__":
    main()
