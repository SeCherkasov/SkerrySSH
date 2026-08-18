#!/usr/bin/env python3
"""The gate runner — the only thing that can mark a stage green.

The previous recorder inferred a green build from the *text* of a Bash command and the tool's
reply. Two costs came out of that: a log redirected into a shell variable was unreadable, so real
green runs went unrecorded (about ten of them), and `./gradlew build` was not recognised at all
because the pattern only knew `allTests` and `detektAll`.

Here the runner executes the stage itself, reads its exit code, and pins the result to a digest of
the tree it ran against. Nothing is inferred. A run whose tree changed underneath it is discarded
rather than recorded against the wrong code.

    tools/harness/gate.py status              what this change is, and what it still owes
    tools/harness/gate.py run [stage ...]     run what is owed (or the named stages)
    tools/harness/gate.py red --tests P       prove a test fails before the fix exists
    tools/harness/gate.py task bug [ref]      override the auto-detected kind
    tools/harness/gate.py checks              the deterministic rules alone
    tools/harness/gate.py reviewers           which reviewers this change needs
    tools/harness/gate.py review NAME         record a reviewer's findings once its report arrives
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time

if __package__ in (None, ""):  # invoked as a script, not as a module
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from harness import checks, policy, state
else:
    from . import checks, policy, state

# Test source root -> the Gradle task that runs it. `jvm("desktop")` names the JVM target
# "desktop", so the KMP modules test through :<module>:desktopTest.
TEST_TASKS = (
    ("shared/src/commonTest", ":shared:desktopTest"),
    ("shared/src/desktopTest", ":shared:desktopTest"),
    ("shared/src/jvmSharedTest", ":shared:desktopTest"),
    ("composeApp/src/commonTest", ":composeApp:desktopTest"),
    ("composeApp/src/desktopTest", ":composeApp:desktopTest"),
    ("server/src/test", ":server:test"),
    ("androidApp/src/test", ":androidApp:testDebugUnitTest"),
)


# The harness's own suite is not a Gradle task, and a `fix/` branch that touches only the gate
# still owes a RED record.
HARNESS_SUITE = "selftest"
# A test name or a glob of one. Anything else — an option, a shell fragment — is handed to the
# suite as an argument, and its own usage error would exit non-zero and read as a failing test.
TEST_PATTERN = re.compile(r"^[A-Za-z0-9_.*]+$")
# What a run that actually reached a test looks like: unittest prints both lines, in this order.
HARNESS_RED_EVIDENCE = re.compile(r"^Ran [1-9]\d* tests?\b.*^FAILED \(", re.S | re.M)
# Gradle prints this only from a test task that ran and had failures. A compile error, a
# missing dependency or a bad task name fails the build without it.
GRADLE_RED_EVIDENCE = re.compile(r"There were failing tests|tests? completed, \d+ failed")


def _env() -> dict:
    env = dict(os.environ)
    env.setdefault("ANDROID_HOME", os.path.expanduser("~/Android/Sdk"))
    return env


def _run_logged(args: list[str], stage: str, root: str) -> tuple[int, str]:
    """Run a stage command, tee its output to .git/skerry-gate/<stage>.log, return (code, log)."""
    log = state.log_path(stage) or os.path.join(root, f".gradle-{stage}.log")
    os.makedirs(os.path.dirname(log), exist_ok=True)
    # Whatever a failing test printed is nobody else's business on a shared machine, and the logs
    # sit beside a 0600 state file and 0600 findings.
    os.chmod(os.path.dirname(log), 0o700)
    with open(log, "w", encoding="utf-8") as fh:
        try:
            proc = subprocess.run(args, cwd=root, env=_env(), stdout=fh,
                                  stderr=subprocess.STDOUT, timeout=3600)
            code = proc.returncode
        except (OSError, subprocess.SubprocessError) as exc:
            fh.write(f"\nharness: {type(exc).__name__}: {exc}\n")
            code = 127
    return code, log


def tail(path: str, lines: int = 40) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return "".join(fh.readlines()[-lines:])
    except OSError:
        return ""


def run_stage(stage: str, root: str) -> bool:
    before = state.tree_digest("all")
    started = time.time()

    if stage == "checks":
        findings = checks.run()
        blocking = [f for f in findings if f.severity == checks.BLOCK]
        for finding in findings:
            print(finding)
        ok = not blocking
        detail = f"{len(blocking)} blocking, {len(findings) - len(blocking)} warnings"
    else:
        command = list(policy.STAGE_COMMANDS[stage])
        # A test task that ran with --tests leaves the aggregate task looking up-to-date, so the
        # next full run reports success in half a second without executing anything. Only --rerun
        # breaks that; cleanAllTests does not.
        if stage == "tests" and state.load().get("test_cache_dirty"):
            command.append("--rerun")
        print(f"harness: {' '.join(command)}")
        code, log = _run_logged(command, stage, root)
        ok = code == 0
        detail = f"exit {code}, log {log}"
        if not ok:
            print(tail(log))

    after = state.tree_digest("all")
    elapsed = int(time.time() - started)
    if after != before:
        print(f"harness: {stage} discarded — the tree changed while it ran ({elapsed}s). "
              "Re-run it against a settled worktree.")
        return False

    state.record_stage(stage, ok, extra={"detail": detail, "seconds": elapsed})
    if stage == "tests" and ok:
        st = state.load()
        st["test_cache_dirty"] = False
        state.save(st)
    print(f"harness: {stage} {'ok' if ok else 'FAILED'} ({elapsed}s) — {detail}")
    return ok


def cmd_run(args: argparse.Namespace) -> int:
    root = state.repo_root()
    task, debt = policy.gate_debt()
    if args.stages:
        stages = args.stages
    else:
        stages = [item.split(" — ")[0] for item in debt]
        stages = [s for s in stages if s in policy.STAGE_COMMANDS or s == "checks"]
        if not stages and not debt:
            print(f"harness: nothing owed — {task['kind']} on {task['branch']} is fully gated.")
            return 0
    print(f"harness: {task['kind']} ({task['source']}), areas: "
          f"{', '.join(task['areas']) or 'none'}")

    failed = []
    for stage in stages:
        if not run_stage(stage, root):
            failed.append(stage)
            if not args.keep_going:
                break
    if not args.keep_daemons:
        _stop_daemons(root)

    _, debt = policy.gate_debt()
    print()
    print(_status_text(task, debt, policy.unreviewed(state.load(), task)))
    return 1 if failed else 0


def _stop_daemons(root: str) -> None:
    """Gradle and Kotlin daemons together will hang this machine; the gate is the end of a run."""
    subprocess.run(["./gradlew", "--stop"], cwd=root, env=_env(),
                   capture_output=True, timeout=120, check=False)
    subprocess.run(["pkill", "-f", "KotlinCompileDaemon"], capture_output=True, check=False)


def proves_a_failing_test(task_name: str, body: str) -> bool:
    """Whether the run actually reached a test and that test failed.

    A non-zero exit is not a failing test: an import error, a syntax error, a usage error in the
    runner or a compile error in the module all exit non-zero, and each of those would record a
    RED phase no test ever ran. Fixing the typo then moves the sources and the gate reports the
    bug as reproduced.
    """
    pattern = HARNESS_RED_EVIDENCE if task_name == HARNESS_SUITE else GRADLE_RED_EVIDENCE
    return bool(pattern.search(body))


def cmd_red(args: argparse.Namespace) -> int:
    root = state.repo_root()
    task_name = args.task or _task_for_file(args.file or "")
    if not task_name:
        print("harness: which task runs this test? Pass --task (e.g. :shared:desktopTest, or "
              f"{HARNESS_SUITE} for the harness's own suite) or --file with the test's path.")
        return 2

    if task_name == HARNESS_SUITE:
        # Gradle cannot run a Python test. Without this branch a bug fix to the gate itself could
        # not record the failure it fixes — the one change where that matters most.
        if not TEST_PATTERN.match(args.tests):
            print("harness: --tests takes a test name or a glob of one, not an option — "
                  f"`{args.tests}` would be handed to the suite as an argument.")
            return 2
        command = [sys.executable, "tools/harness/selftest.py", "-k", args.tests.strip("*")]
        empty = "Ran 0 tests"
    else:
        command = ["./gradlew", task_name, "--tests", args.tests, "--rerun"]
        empty = "No tests found for given includes"
        st = state.load()
        st["test_cache_dirty"] = True  # a filtered run leaves the aggregate task looking up to date
        state.save(st)

    print(f"harness: {' '.join(command)}")
    code, log = _run_logged(command, "red", root)
    body = tail(log, 200)

    if empty in body:
        print(f"harness: the pattern matched no test — nothing was proven.\n{tail(log, 15)}")
        return 2
    if code == 0:
        print("harness: the test PASSED. A test that is green before the fix proves nothing about "
              "the bug — make it reproduce the failure first.")
        return 1
    if not proves_a_failing_test(task_name, body):
        print(f"harness: the run did not report a failing test — nothing was proven.\n"
              f"{tail(log, 15)}")
        return 2

    record = {
        "task": task_name, "pattern": args.tests, "at": time.time(),
        "branch": state.current_branch(), "src_digest": state.tree_digest("src"),
    }
    st = state.load()
    st.setdefault("red", []).append(record)
    state.save(st)
    print(f"harness: RED recorded — {task_name} --tests {args.tests} failed against the current "
          "sources. Now make it pass; the gate will check the sources actually changed.")
    return 0


def _task_for_file(path: str) -> str:
    normalised = path.replace(os.sep, "/")
    if normalised.startswith(("tools/harness/", ".claude/hooks/")):
        return HARNESS_SUITE
    for prefix, task_name in TEST_TASKS:
        if prefix in normalised:
            return task_name
    return ""


def cmd_task(args: argparse.Namespace) -> int:
    if args.kind not in policy.KINDS:
        print(f"harness: kind must be one of {', '.join(policy.KINDS)}")
        return 2
    st = state.load()
    st["task"] = {"kind": args.kind, "ref": args.ref or "",
                  "branch": state.current_branch(), "at": time.time()}
    state.save(st)
    return cmd_status(args)


# Why a report was turned away, in the words the operator needs to act on it.
REVIEW_REFUSALS = {
    "no findings": "that text carries no findings — it reads as a launch acknowledgement",
    "recycled": "that is the report already on disk, not a new pass",
    "not saved": "the record could not be written",
}


def cmd_review(args: argparse.Namespace) -> int:
    """Record a reviewer's findings once its report is actually in hand.

    The PostToolUse hook can only record a fan-out that hands its findings back inline. A
    backgrounded reviewer returns a launch acknowledgement instead and reports minutes later — so
    without this command the gate would close on the launch, and "reviewed" would mean "started".
    """
    reviewer = args.agent.split(":")[-1]
    if reviewer not in policy.known_reviewers():
        print(f"harness: {args.agent} is not one of this repository's reviewers")
        return 2
    try:
        if args.file in (None, "-"):
            text = sys.stdin.read()
        else:
            with open(args.file, encoding="utf-8") as fh:
                text = fh.read()
    except (OSError, UnicodeDecodeError) as exc:
        print(f"harness: cannot read the report — {exc}")
        return 2
    stored = state.review_report_path(reviewer)
    if args.file not in (None, "-") and stored and os.path.exists(stored) \
            and os.path.realpath(args.file) == os.path.realpath(stored):
        # `gate.py reviewers` prints this path right under MISSING. Once the reviewer's scope moves
        # it is owed again and its own stored round is no longer a replay — nothing in the text can
        # tell that file apart from a report the reviewer just wrote, but where it came from can.
        print("harness: that is the file the previous round was written to, not a new pass — "
              f"re-run {policy.agent_id(reviewer)} and record what it says.")
        return 2
    drift = policy.review_drift(reviewer)
    # The verdict itself comes from `record_review`, the same one the hook gets: the CLI used to
    # refuse on drift before the report was ever looked at, so the retry a refusal advertises was
    # refused too and the reviewer could not be recorded at all without a fresh launch.
    reason = policy.record_review(reviewer, text)
    if reason == "moved":
        print(f"harness: {reviewer} read a tree that has since moved — not recorded. Re-run it on:")
        for path in drift:
            print(f"  {path}")
        return 2
    if reason:
        print(f"harness: nothing recorded — {REVIEW_REFUSALS.get(reason, reason)}")
        return 2
    print(f"harness: {reviewer} recorded against the current tree")
    return 0


def cmd_checks(_: argparse.Namespace) -> int:
    return checks.main([])


def cmd_reviewers(_: argparse.Namespace) -> int:
    task = policy.classify()
    st = state.load()
    missing = policy.missing_reviewers(st, task)
    base = state.merge_base()
    print(f"range: {base[:12]}...HEAD (worktree included)")
    for reviewer in policy.required_reviewers(task):
        mark = "MISSING" if reviewer in missing else "ok"
        print(f"  {mark:>11}  {policy.agent_id(reviewer)}")
        if reviewer not in missing:
            continue
        delta = policy.reviewer_delta(st, reviewer)
        if delta:
            # A reviewer that has already seen this branch only needs what moved since. Handing it
            # the whole diff again is what made a second round cost as much as the first.
            print(f"{'':>13}re-run on the delta only — {len(delta)} file(s) changed since its "
                  "last pass:")
            for path in delta[:12]:
                print(f"{'':>15}{path}")
            if len(delta) > 12:
                print(f"{'':>15}... and {len(delta) - 12} more")
            report = state.review_report_path(reviewer)
            if report and os.path.exists(report):
                print(f"{'':>13}its previous findings: {os.path.relpath(report)}")
    for reviewer in policy.skipped_reviewers(task):
        print(f"  {'not installed':>11}  {policy.agent_id(reviewer)}")
    skipped = policy.skipped_reviewers(task)
    if skipped:
        print(f"\n{len(skipped)} reviewer(s) unavailable here — the gate does not demand them, so "
              "say in the hand-off that this angle went unreviewed.")
    return 0


def _status_text(task: dict, debt: list[str], missed: list[tuple[str, list[str]]] | None = None
                 ) -> str:
    lines = [
        f"task:    {task['kind']}  ({task['source']})",
        f"branch:  {task['branch']}",
        f"areas:   {', '.join(task['areas']) or 'none'}",
        f"files:   {len(task['code_paths'])} code, {len(task['paths'])} total",
    ]
    if not debt:
        lines.append("gate:    clear — commit and PR are unblocked.")
    else:
        lines.append("gate:    owed —")
        lines += [f"           - {item}" for item in debt]
        lines.append("run:     tools/harness/gate.py run")
    for reviewer, delta in missed or []:
        lines.append(f"unreviewed: {policy.agent_id(reviewer)} — {len(delta)} file(s) moved after "
                     f"its {policy.REVIEW_ROUNDS} passes; the gate stopped asking, nobody read "
                     "this:")
        lines += [f"             {path}" for path in delta[:12]]
        if len(delta) > 12:
            lines.append(f"             ... and {len(delta) - 12} more")
    return "\n".join(lines)


def cmd_status(_: argparse.Namespace) -> int:
    task, debt = policy.gate_debt()
    print(_status_text(task, debt, policy.unreviewed(state.load(), task)))
    return 1 if debt else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="gate.py", description=__doc__)
    sub = parser.add_subparsers(dest="command")

    run = sub.add_parser("run", help="run the stages this change owes")
    run.add_argument("stages", nargs="*", help="stage names; default is everything still owed")
    run.add_argument("--keep-going", action="store_true", help="do not stop at the first failure")
    run.add_argument("--keep-daemons", action="store_true", help="leave Gradle/Kotlin daemons up")
    run.set_defaults(func=cmd_run)

    red = sub.add_parser("red", help="record a test failing before the fix")
    red.add_argument("--tests", required=True, help="Gradle --tests pattern")
    red.add_argument("--task", help="Gradle test task, e.g. :shared:desktopTest")
    red.add_argument("--file", help="path to the test file; the task is derived from it")
    red.set_defaults(func=cmd_red)

    task = sub.add_parser("task", help="declare the kind of change explicitly")
    task.add_argument("kind", help=" | ".join(policy.KINDS))
    task.add_argument("ref", nargs="?", help="issue or PR reference")
    task.set_defaults(func=cmd_task)

    review = sub.add_parser("review", help="record a reviewer's findings once its report arrives")
    review.add_argument("agent", help="reviewer name, with or without its plugin prefix")
    review.add_argument("--file", help="path to the report; omitted or '-' reads stdin")
    review.set_defaults(func=cmd_review)

    sub.add_parser("checks", help="deterministic project rules").set_defaults(func=cmd_checks)
    sub.add_parser("reviewers", help="reviewers this change needs").set_defaults(func=cmd_reviewers)
    sub.add_parser("status", help="what this change is and what it owes").set_defaults(func=cmd_status)

    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        args = parser.parse_args(["status"])
    if not state.repo_root():
        print("harness: not inside a git repository")
        return 2
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
