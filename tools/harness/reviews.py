"""Review receipts bind structured findings to an explicit pre-review snapshot."""
from __future__ import annotations
import re
import time
import uuid
from pathlib import Path
from . import evidence, policy, state

NAMES = ("skerry-reviewer", "skerry-kotlin-reviewer", "skerry-security-reviewer")
MAX_ROUNDS = 2


def scope(name, cwd=None):
    return {"branch": state.current_branch(cwd), "base": state.merge_base(cwd=cwd),
            "files": policy.reviewer_paths(name, cwd)}


def _history(data, cwd=None):
    return [r for r in data.get("reviews", {}).values()
            if r["snapshot"]["branch"] == state.current_branch(cwd)]


def start(name, cwd=None, extra_reason=""):
    if name not in policy.reviewers(cwd):
        raise ValueError(f"reviewer has no scope in this change: {name}")
    snapshot = scope(name, cwd)
    token = uuid.uuid4().hex
    pending = {"token": token, "reviewer": name, "snapshot": snapshot,
               "at": time.time(), "extra_reason": extra_reason}
    def mutate(data):
        rounds = [r for r in _history(data, cwd) if r["reviewer"] == name]
        if len(rounds) >= MAX_ROUNDS and not extra_reason.strip():
            raise RuntimeError(f"{name}: two rounds exhausted; gate stays owed. A third round needs "
                               "the user's instruction and --extra-round-reason.")
        data.setdefault("pending", {})[token] = pending
    evidence.update(mutate, cwd)
    return pending


def validate(report, cwd=None):
    if not isinstance(report, dict) or report.get("schema") != 1:
        raise ValueError("review report must use schema 1")
    if report.get("reviewer") not in NAMES or not isinstance(report.get("token"), str):
        raise ValueError("reviewer and launch token are required")
    if not isinstance(report.get("summary"), str) or not report["summary"].strip():
        raise ValueError("a review summary is required")
    findings = report.get("findings")
    if not isinstance(findings, list) or len(findings) > 100:
        raise ValueError("findings must be an array of at most 100 items")
    ids = set()
    for item in findings:
        if not isinstance(item, dict) or not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", str(item.get("id", ""))):
            raise ValueError("every finding needs a stable id")
        if item["id"] in ids:
            raise ValueError("duplicate finding id")
        ids.add(item["id"])
        if type(item.get("priority")) is not int or item["priority"] not in range(4):
            raise ValueError("finding priority must be 0..3")
        path = item.get("path")
        if not isinstance(path, str) or Path(path).is_absolute() or ".." in Path(path).parts:
            raise ValueError("finding path must be repository-relative")
        if type(item.get("line")) is not int or item["line"] < 1:
            raise ValueError("finding line must be positive")
        for field in ("title", "body"):
            if not isinstance(item.get(field), str) or not item[field].strip():
                raise ValueError(f"finding requires {field}")


def record(report, cwd=None):
    validate(report, cwd)
    token = report["token"]
    def mutate(data):
        pending = data.get("pending", {}).get(token)
        if not pending or pending["reviewer"] != report["reviewer"]:
            raise ValueError("unknown or consumed review launch token")
        if pending["snapshot"] != scope(report["reviewer"], cwd):
            raise ValueError("review scope changed after launch; start a new review on the delta")
        rounds = [r for r in _history(data, cwd) if r["reviewer"] == report["reviewer"]]
        if len(rounds) >= MAX_ROUNDS and not pending["extra_reason"].strip():
            raise ValueError("review round limit exceeded")
        entry = {**report, "snapshot": pending["snapshot"], "at": time.time(),
                 "extra_reason": pending["extra_reason"],
                 "findings": [{**item, "state": "open"} for item in report["findings"]]}
        data.setdefault("reviews", {})[token] = entry
        del data["pending"][token]
    evidence.update(mutate, cwd)


def resolve(token, finding_id, outcome, reason, cwd=None):
    if outcome not in ("fixed", "rejected") or not reason.strip():
        raise ValueError("resolution requires fixed/rejected and a concrete reason")
    def mutate(data):
        report = data.get("reviews", {}).get(token)
        if not report or report["snapshot"]["branch"] != state.current_branch(cwd):
            raise ValueError("review does not belong to this branch")
        for item in report["findings"]:
            if item["id"] == finding_id:
                item.update(state=outcome, reason=reason)
                return
        raise ValueError("unknown finding")
    evidence.update(mutate, cwd)


def debt(cwd=None):
    data = evidence.load(cwd)
    history = _history(data, cwd)
    owed = []
    for name in policy.reviewers(cwd):
        latest = sorted((r for r in history if r["reviewer"] == name), key=lambda r: r["at"])
        if not latest or latest[-1]["snapshot"] != scope(name, cwd):
            exhausted = len(latest) >= MAX_ROUNDS
            owed.append(f"review:{name}" + (" — two rounds exhausted; user decision required" if exhausted else ""))
    for report in history:
        for item in report["findings"]:
            if item["state"] == "open":
                owed.append(f"finding:{report['token']}:{item['id']} — {item['title']}")
    return owed


def delta(name, cwd=None):
    previous = [r for r in _history(evidence.load(cwd), cwd) if r["reviewer"] == name]
    before = max(previous, key=lambda r: r["at"])["snapshot"]["files"] if previous else {}
    now = scope(name, cwd)["files"]
    return sorted(p for p in set(before) | set(now) if before.get(p) != now.get(p))
