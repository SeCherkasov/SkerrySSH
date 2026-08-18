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

GATE_TASKS = re.compile(r"gradlew\b.*\b(allTests|detektAll|\btest\b|build|compileDebugKotlin)")
RUNNER = re.compile(r"harness/gate\.py")


def note(text: str) -> None:
    json.dump({
        "hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": text}
    }, sys.stdout)
    sys.exit(0)


def report_text(response) -> str:
    """The reviewer's findings as text, whatever shape the harness handed them back in."""
    if isinstance(response, str):
        return response
    if isinstance(response, dict):
        for key in ("content", "text", "output", "result"):
            if key in response:
                return report_text(response[key])
        return ""
    if isinstance(response, list):
        return "\n".join(part for part in (report_text(item) for item in response) if part)
    return ""


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)
    if state is None:
        sys.exit(0)

    tool = payload.get("tool_name") or ""
    tool_input = payload.get("tool_input") or {}

    if tool in ("Agent", "Task"):
        subagent = (tool_input.get("subagent_type") or "").split(":")[-1]
        known = set(policy.BASE_REVIEWERS) | {
            name for names in policy.AREA_REVIEWERS.values() for name in names
        }
        if subagent in known:
            st = state.load()
            entries = policy.reviewer_entries(subagent)
            st.setdefault("reviews", {})[subagent] = {
                # Scoped to what this reviewer reads, so an unrelated fix does not owe it again.
                "digest": state.digest_of(entries),
                "files": entries,
                "branch": state.current_branch(),
                "at": __import__("time").time(),
            }
            state.save(st)
            state.save_review_report(subagent, report_text(payload.get("tool_response")))
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
    main()
