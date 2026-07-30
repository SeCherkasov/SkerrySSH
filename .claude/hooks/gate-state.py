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
# `> file`, `2> file`, `>> file`, `&> file` — where a gate run puts gradle's output.
REDIRECT = re.compile(r"(?:\d?>>?|&>)\s*\"?([^\s\"|;&]+)")
# A log older than this belongs to an earlier run: the command was rerun and this time wrote nothing.
LOG_MAX_AGE_S = 30 * 60
LOG_TAIL_BYTES = 64 * 1024


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


def log_tail(path: str) -> str:
    """The end of a log the command redirected into, or empty if there is no usable one."""
    path = os.path.expanduser(path)
    try:
        if time.time() - os.path.getmtime(path) > LOG_MAX_AGE_S:
            return ""
        with open(path, errors="replace") as fh:
            fh.seek(0, os.SEEK_END)
            fh.seek(max(0, fh.tell() - LOG_TAIL_BYTES))
            return fh.read()
    except OSError:
        return ""


def gradle_was_green(command: str, response_text: str) -> bool:
    """
    Whether a gate run passed. The verdict is not always in what the tool returned: `/gate` sends
    gradle's output to a file (a pipe would mask the exit code), and then the tool answers with
    nothing but an exit code — which used to read as "not green" and blocked the commit that a
    fully green gate had just earned.
    """
    outputs = [response_text] + [log_tail(target) for target in REDIRECT.findall(command)]
    # A failure anywhere in the run outweighs a success beside it: one command can hold several
    # gradle invocations, and the gate is what all of them together say.
    if any("BUILD FAILED" in text for text in outputs):
        return False
    return any("BUILD SUCCESSFUL" in text for text in outputs)


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
        # A green gate run: the gradle tasks that matter, and no failure in what it wrote.
        if GATE_TASKS.search(command) and gradle_was_green(command, response_text):
            mark("build")
    elif tool in ("Agent", "Task"):
        subagent = tool_input.get("subagent_type") or ""
        if REVIEWERS.search(subagent):
            mark("review")

    sys.exit(0)


if __name__ == "__main__":
    main()
