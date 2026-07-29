#!/usr/bin/env python3
"""PostToolUse recorder for the pre-PR gate.

Tracks three timestamps in .git/skerry-gate-state.json:
  code   — last edit to a .kt/.kts file
  build  — last green `gradlew` run that included the test and detekt tasks
  review — last run of a reviewer subagent

guard-git.py refuses a commit or a PR when `code` is newer than `build` or `review`, i.e. when the
code changed after it was last gated. Documentation-only changes never set `code`, so they commit
freely.
"""

import json
import os
import re
import subprocess
import sys
import time

REVIEWERS = re.compile(
    r"skerry-reviewer|kotlin-reviewer|security-reviewer|silent-failure-hunter|pr-test-analyzer"
)
GATE_TASKS = re.compile(r"detektAll|allTests")
CODE_FILE = re.compile(r"\.kts?$")


def state_path() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--git-dir"], capture_output=True, text=True, timeout=5
        )
        git_dir = out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""
    return os.path.join(git_dir, "skerry-gate-state.json") if git_dir else ""


def mark(key: str) -> None:
    path = state_path()
    if not path:
        return
    try:
        with open(path) as fh:
            state = json.load(fh)
    except (OSError, ValueError):
        state = {}
    state[key] = time.time()
    try:
        with open(path, "w") as fh:
            json.dump(state, fh)
    except OSError:
        pass  # a hook must never break the session


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)

    tool = payload.get("tool_name") or ""
    tool_input = payload.get("tool_input") or {}
    response = payload.get("tool_response")
    response_text = response if isinstance(response, str) else json.dumps(response or "")

    if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        if CODE_FILE.search(tool_input.get("file_path") or ""):
            mark("code")
    elif tool == "Bash":
        command = tool_input.get("command") or ""
        # A green gate run: the gradle tasks that matter, and no failure in the output.
        if GATE_TASKS.search(command) and "BUILD SUCCESSFUL" in response_text:
            mark("build")
    elif tool in ("Agent", "Task"):
        subagent = tool_input.get("subagent_type") or ""
        if REVIEWERS.search(subagent):
            mark("review")

    sys.exit(0)


if __name__ == "__main__":
    main()
