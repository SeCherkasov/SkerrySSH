#!/usr/bin/env python3
"""PostToolUse recorder for the one gate stage the runner cannot execute: the review fan-out.

Build stages are run by `tools/harness/gate.py`, which reads their exit codes directly. Reviewers
are subagents, so their completion is only visible here — this hook pins each finished reviewer to
a digest of the tree it reviewed. Editing code afterwards invalidates the review, exactly as it
invalidates a build.

It also answers Gradle runs made outside the runner, which are *not* recorded: without that note
a green `./gradlew allTests` looks like it closed the gate, and the block at commit time comes as
a surprise several minutes later.
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
except ImportError:
    policy = state = None

REFUSALS = {
    "moved": "the code moved while it was reading, so it reviewed a tree that no longer exists",
    "recycled": "that is the findings file already on disk, not a new pass",
    "not saved": "the record could not be written",
}
GATE_TASKS = re.compile(r"gradlew\b.*\b(allTests|detektAll|\btest\b|build|compileDebugKotlin)")
RUNNER = re.compile(r"harness/gate\.py")


def note(text: str) -> None:
    json.dump({
        "hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": text}
    }, sys.stdout)
    sys.exit(0)


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)
    if not isinstance(payload, dict):
        sys.exit(0)
    if state is None:
        sys.exit(0)

    tool = payload.get("tool_name") or ""
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        tool_input = {}

    if tool in ("Agent", "Task"):
        subagent = (tool_input.get("subagent_type") or "").split(":")[-1]
        if subagent in policy.known_reviewers():
            reason = policy.record_review(subagent, payload.get("tool_response"))
            if reason == "no findings":
                # A backgrounded reviewer answers with a launch acknowledgement: it has not read a
                # line yet. Recording that would close the review gate on the fan-out being
                # started, which is the one thing the gate exists to rule out. Snapshot the scope
                # it is about to read instead, so an edit made while it reads is visible when the
                # report finally arrives.
                policy.note_review_launch(subagent)
                note(f"{subagent} was launched, not recorded — this reply carries no findings. "
                     f"When its report arrives, record it with `tools/harness/gate.py review "
                     f"{subagent} --file <report>`.")
            elif reason:
                # Anything else is a report that was refused, and the snapshot is the evidence it
                # was refused on: re-taking it here would let the retry launder the refusal.
                note(f"{subagent}: {REFUSALS.get(reason, reason)} — not recorded.")
        sys.exit(0)

    if tool == "Bash":
        command = tool_input.get("command") or ""
        if GATE_TASKS.search(command) and not RUNNER.search(command):
            note("This Gradle run was not recorded as a gate stage — only "
                 "`tools/harness/gate.py run` marks stages, because only it can pin an exit code "
                 "to the tree it ran against. Fine for an iteration; re-run through the runner "
                 "when closing the gate.")

    sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001 - a hook that raises breaks every Agent call in the session
        sys.exit(0)
