#!/usr/bin/env python3
"""Skerry harness v2: plan, execute and explain evidence for the current change."""
from __future__ import annotations
import argparse
import contextlib
import io
import json
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from harness import agents, capabilities, checks, environment, evidence, policy, reviews, runner, state


def debt(mode="final", cwd=None):
    owed = [s.name for s in policy.plan(mode, cwd) if not evidence.current(s, cwd)]
    if mode == "final":
        owed += runner.red_debt(cwd) + reviews.debt(cwd)
    return owed


def result(summary, data=None, owed=None, artifacts=None):
    return {"status": "owed" if owed else "ok", "summary": summary,
            "next_actions": owed or [], "artifacts": artifacts or [], "data": data or {}}


def view(mode="final"):
    task = policy.classify()
    owed = debt(mode)
    return result(f"{mode}: {task['kind']} on {task['branch']} — " +
                  (f"{len(owed)} requirement(s) owed" if owed else "verified"), task, owed,
                  [str(evidence.directory())])


def dispatch(args):
    if args.command in ("status", "verify"):
        return view(args.mode), bool(debt(args.mode))
    if args.command == "plan":
        stages = [{"name": s.name, "command": list(s.command), "current": evidence.current(s),
                   "modules": list(s.modules)} for s in policy.plan(args.mode)]
        return result(f"{args.mode} plan", {"task": policy.classify(), "stages": stages,
                                             "agents": agents.plan(focus=args.focus, ecc_root=args.ecc_root)}), 0
    if args.command == "skill-plan":
        return result("ECC guidance selected by scope and explicit task focus",
                      capabilities.plan(focus=args.focus, ecc_root=args.ecc_root)), 0
    if args.command in ("reviewers", "agent-plan"):
        data = agents.plan(focus=args.focus, ecc_root=args.ecc_root)
        data["delta"] = {name: reviews.delta(name) for name in policy.reviewers()}
        return result("explicit agent dispatch profiles", data, reviews.debt()), 0
    if args.command == "doctor":
        items = environment.doctor()
        errors = [i["name"] + ": " + i["detail"] for i in items if i["status"] == "error"]
        output = result("environment diagnostics", items, errors)
        if not errors and any(i["status"] == "warning" for i in items):
            output["status"] = "warning"
        return output, bool(errors)
    if args.command == "run":
        stages = policy.plan(args.mode)
        if args.stages:
            unknown = set(args.stages) - {s.name for s in stages}
            if unknown:
                raise ValueError("stage not in this plan: " + ", ".join(sorted(unknown)))
            stages = [s for s in stages if s.name in args.stages]
        pending_gradle = any(s.command and s.command[0] == "./gradlew" and not evidence.current(s) for s in stages)
        if pending_gradle:
            errors = [i for i in environment.doctor() if i["status"] == "error"]
            if errors:
                raise ValueError("prerequisite failed: " + "; ".join(i["name"] + ": " + i["detail"] for i in errors))
        ok = runner.run(stages)
        output = view(args.mode)
        if args.build_only:
            output["summary"] = "planned build stages " + ("passed" if ok else "failed") + "; final review/RED gate is separate"
            output["status"] = "partial" if ok and output["next_actions"] else output["status"]
        return output, 1 if not ok or (output["next_actions"] and not args.build_only) else 0
    if args.command == "checks":
        task = policy.classify()
        findings = checks.run(task=task, base=args.base or "")
        blocking = [str(f) for f in findings if f.severity == checks.BLOCK]
        return result("deterministic checks", [str(f) for f in findings], blocking), bool(blocking)
    if args.command == "task":
        current = policy.classify()
        if policy.KINDS.index(args.kind) < policy.KINDS.index(current["kind"]):
            raise ValueError("a declaration may only make the inferred kind stricter")
        evidence.update(lambda data: data.setdefault("tasks", {}).update(
            {current["branch"]: {"kind": args.kind, "ref": args.ref or ""}}))
        return view(), 0
    if args.command == "red":
        runner.red(args.file, args.tests)
        return result("RED recorded; implement the fix and keep the regression unchanged"), 0
    if args.command == "review-start":
        pending = reviews.start(args.reviewer, extra_reason=args.extra_round_reason)
        pending["dispatch"] = agents.profile(args.reviewer, focus=args.focus, ecc_root=args.ecc_root)
        pending["delta"] = reviews.delta(args.reviewer)
        pending["report_template"] = {"schema": 1, "token": pending["token"],
                                      "reviewer": args.reviewer, "summary": "", "findings": []}
        return result("review launched snapshot; give this token and dispatch profile to the reviewer", pending), 0
    if args.command == "review":
        report_path = Path(args.file)
        if report_path.stat().st_size > 200_000:
            raise ValueError("review report exceeds 200 KB")
        report = json.loads(report_path.read_text())
        if args.reviewer and report.get("reviewer") != args.reviewer:
            raise ValueError("report reviewer does not match the requested reviewer")
        reviews.record(report)
        return result("review recorded; resolve every finding", report, reviews.debt(), [args.file]), 0
    if args.command == "resolve":
        reviews.resolve(args.token, args.finding, args.outcome, args.reason)
        return result("finding resolution recorded", owed=reviews.debt()), 0
    if args.command == "install-hooks":
        environment.install_hooks()
        return result("Git pre-commit/pre-push hooks configured for this clone"), 0
    if args.command == "sync-agents":
        return result("local Codex reviewers generated from portable prompts and dispatch profiles",
                      artifacts=agents.sync_configs()), 0
    if args.command == "guard":
        environment.guard(args.action, sys.stdin.read() if args.action == "push" else "")
        return result("delivery verified"), 0
    raise ValueError("unknown command")


def parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("status", "verify", "plan", "run", "doctor", "checks", "task", "red", "reviewers",
                 "agent-plan", "skill-plan", "review-start", "review", "resolve", "install-hooks", "sync-agents", "guard"):
        command = commands.add_parser(name)
        command.add_argument("--json", action="store_true")
        if name in ("plan", "agent-plan", "skill-plan", "reviewers", "review-start"):
            command.add_argument("--focus", action="append", choices=tuple(capabilities.ROUTES), default=[],
                                 help="Task semantics paths cannot infer; repeat to add topics")
            command.add_argument("--ecc-root", help="Installed ECC plugin directory; overrides ECC_PLUGIN_ROOT")
        if name in ("status", "verify", "plan", "run"):
            command.add_argument("--mode", choices=("fast", "final"), default="final")
        if name == "run":
            command.add_argument("stages", nargs="*")
            command.add_argument("--build-only", action="store_true", help="CI: stage verdict only; never closes final review/RED debt")
        elif name == "checks":
            command.add_argument("--base")
        elif name == "task":
            command.add_argument("kind", choices=policy.KINDS)
            command.add_argument("ref", nargs="?")
        elif name == "red":
            command.add_argument("--file", required=True)
            command.add_argument("--tests", required=True)
        elif name == "review-start":
            command.add_argument("reviewer", choices=reviews.NAMES)
            command.add_argument("--extra-round-reason", default="",
                                 help="Only after the user's explicit instruction for another round")
        elif name == "review":
            command.add_argument("reviewer", nargs="?", help="Optional check against the report's reviewer")
            command.add_argument("--file", required=True)
        elif name == "resolve":
            command.add_argument("token")
            command.add_argument("finding")
            command.add_argument("outcome", choices=("fixed", "rejected"))
            command.add_argument("--reason", required=True)
        elif name == "guard":
            command.add_argument("action", choices=("commit", "push"))
    return parser


def main(argv=None):
    args = parser().parse_args(argv or ["status"])
    capture = io.StringIO()
    try:
        if not state.repo_root():
            raise ValueError("not inside a Git repository")
        with contextlib.redirect_stdout(capture) if args.json else contextlib.nullcontext():
            output, code = dispatch(args)
    except (ValueError, OSError, RuntimeError, KeyError, TypeError) as exc:
        output = {"status": "error", "summary": str(exc),
                  "next_actions": ["Run gate.py doctor; fix the reported cause before retrying."], "artifacts": []}
        code = 2
    if args.json:
        if capture.getvalue():
            output["log"] = capture.getvalue()
        print(json.dumps(output, indent=2))
    else:
        print(output["summary"])
        if args.command in ("plan", "reviewers", "agent-plan", "skill-plan", "doctor", "review-start"):
            print(json.dumps(output.get("data", {}), indent=2))
        for action in output["next_actions"]:
            print("  owed: " + action)
    return int(code)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
