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
import hashlib
import re
import time

from . import state

KINDS = ("bug", "feature", "refactor", "docs")
# How much each kind owes, so a declaration can be compared against what the branch already proves.
STRICTNESS = {"docs": 0, "refactor": 1, "feature": 2, "bug": 3}

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
    # The harness gates every other change, and Gradle cannot see a line of it: its own suite is
    # the only thing that can, so a change here owes that suite the way Kotlin owes `tests`.
    # Everything that decides what a change owes, or who reads it: the runner, the hook that
    # records reviews, the settings that register the hook, and the reviewers' own definitions.
    ("harness", lambda p: p.startswith(("tools/harness/", ".claude/hooks/", ".claude/agents/",
                                       ".claude/commands/"))
        or p == ".claude/settings.json"),
)

# Stage -> the command the runner uses. "checks" is internal (tools/harness/checks.py).
STAGE_COMMANDS = {
    "selftest": ["python3", "tools/harness/selftest.py"],
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

# Untrusted input reaches the client through more than the areas named above: every wire protocol
# parses bytes a server chose. The security reviewer's scope is the vault and the sync boundary
# plus those parsers, which is wider than the "security" area used for build stages.
PROTOCOL_SEGMENTS = (
    "/ssh/", "/sftp/", "/telnet/", "/serial/", "/mosh/", "/rdp/", "/vnc/", "/terminal/",
    "/graphics/", "/audio/", "/tunnel/", "/container/",
)


def _area_matcher(*names: str):
    rules = [predicate for area, predicate in AREA_RULES if area in names]
    return lambda path: any(rule(path) for rule in rules)


def _kotlin(path: str) -> bool:
    return path.endswith((".kt", ".kts"))


def _security_scope(path: str) -> bool:
    return (_area_matcher("security")(path)
            or any(seg in path for seg in PROTOCOL_SEGMENTS))


# What each reviewer actually reads. A reviewer is owed again only when the files inside its own
# scope have moved — fixing a Compose layout does not un-review the vault. None means everything,
# and is the honest answer for the two passes that are about the change as a whole.
REVIEWER_SCOPES = {
    "skerry-reviewer": None,               # parity, i18n, the abstraction catalogue: the whole diff
    "skerry-kotlin-reviewer": _kotlin,
    "skerry-security-reviewer": _security_scope,
    "silent-failure-hunter": _kotlin,
    "pr-test-analyzer": None,              # coverage is a property of the change, not of a file
    "a11y-architect": _area_matcher("ui", "android"),
    "java-reviewer": _area_matcher("server"),
    "performance-optimizer": _area_matcher("terminal"),
}


def reviewer_entries(reviewer: str, cwd: str | None = None) -> dict[str, str]:
    """The files this reviewer's verdict depends on, as path -> content id.

    A reviewer reads the diff, not the repository, so the set is the change itself narrowed to the
    reviewer's own scope. Recording the whole tree instead would put thousands of untouched files
    into the state file and make every reviewer depend on every other reviewer's fix.
    """
    keep = REVIEWER_SCOPES.get(reviewer, None)
    changed = set(state.changed_paths(cwd=cwd))
    if keep is None:
        return state.scoped_entries(lambda path: path in changed, "all", cwd)
    return state.scoped_entries(lambda path: path in changed and keep(path), "all", cwd)


def reviewer_digest(reviewer: str, cwd: str | None = None) -> str:
    return state.digest_of(reviewer_entries(reviewer, cwd))


def reviewer_delta(st: dict, reviewer: str, cwd: str | None = None) -> list[str]:
    """Which files inside a reviewer's scope moved since it last ran.

    Empty when the reviewer never ran here: there is no delta to review, only the whole diff.
    """
    entry = (st.get("reviews") or {}).get(reviewer) or {}
    seen = entry.get("files")
    # A pass made on another branch is not a delta to re-read here — it is no pass at all.
    if not isinstance(seen, dict) or entry.get("branch") != state.current_branch(cwd):
        return []
    now = reviewer_entries(reviewer, cwd)
    changed = {path for path, cid in now.items() if seen.get(path) != cid}
    changed |= {path for path in seen if path not in now}
    return sorted(changed)


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


def _floor(branch: str, has_code: bool) -> str:
    """The least strict kind the diff itself can justify, whatever the branch is named.

    Both the branch and the declaration are floored by this one function. They used to be floored
    by two pieces of code that agreed on `wip/` and disagreed on `docs/`: a branch called `docs/…`
    with Kotlin in it became a feature, while declaring `docs` on that same branch was compared
    against `docs` and accepted itself — the whole gate off, and quieter than the override.
    """
    if not has_code:
        return "docs"
    named = kind_from_branch(branch)
    # The strictest kind that does not demand a reproduction test.
    return named if named and named != "docs" else "feature"


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
    # than the diff itself proves. Refusing "docs" over a Kotlin diff was not enough: on a `fix/`
    # branch, declaring `refactor` dropped the RED phase — a silent bypass, where the override at
    # least has to be typed out loud.
    if declared.get("kind") in KINDS and declared.get("branch") == branch:
        # The same default the branch itself would fall back to: the strictest kind that does
        # not demand a reproduction test.
        from_branch = _floor(branch, bool(code_paths))
        if STRICTNESS[declared["kind"]] >= STRICTNESS[from_branch]:
            return {
                "kind": declared["kind"], "source": "declared", "ref": declared.get("ref", ""),
                "areas": task_areas, "paths": paths, "code_paths": code_paths, "branch": branch,
            }

    # No code in the change means no build can be affected, whatever the branch is called.
    if not code_paths:
        kind, source = "docs", "no code in diff"
    else:
        named = kind_from_branch(branch)
        kind = _floor(branch, True)
        source = f"branch `{branch}`" if named else "default"
    return {
        "kind": kind, "source": source, "ref": "", "areas": task_areas,
        "paths": paths, "code_paths": code_paths, "branch": branch,
    }


def required_stages(task: dict) -> list[str]:
    if task["kind"] == "docs":
        return []
    areas = set(task["areas"])
    stages = ["checks"]
    if "harness" in areas:
        stages.append("selftest")
    # Gradle cannot see a line of Python. Charging a hook fix ten minutes of `test allTests` and
    # `build` teaches the operator to reach for SKERRY_GATE_OVERRIDE, which costs more than it saves.
    if areas != {"harness"}:
        stages += ["tests", "build", "detekt"]
        if {"ui", "android"} & areas:
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


# What a backgrounded fan-out returns the moment it is launched. The agent has not read a line
# yet, so recording it would make "reviewed" mean "started" — the one thing the review gate exists
# to rule out. Length is part of the test: a real report that happens to quote `agentId` or the
# phrase "in the background" is a report, not an acknowledgement.
LAUNCH_STUB = re.compile(r"async agent launched|agentid:|is working in the background", re.I)
STUB_CHARS = 400
# A positive floor as well as the denylist: acceptance must not be what happens by default. A
# reviewer that read this diff has more to say than a word, whatever shape the reply arrives in.
MIN_REPORT_CHARS = 200
# Deep enough for any real tool response, shallow enough that the hook cannot blow its stack.
FLATTEN_DEPTH = 50


def review_findings(response) -> str:
    """A reviewer's findings out of whatever shape the harness handed the response back in.

    Empty when the response carries none — an async launch acknowledgement, a one-word "ok", or a
    reviewer that said nothing at all. Either way there is nothing to pin a tree to.
    """
    text = _flatten(response).strip()
    if len(text) < MIN_REPORT_CHARS:
        return ""
    if len(text) < STUB_CHARS and LAUNCH_STUB.search(text):
        return ""
    return text


def _flatten(response, depth: int = 0) -> str:
    """The text inside whatever shape the harness handed a response back in.

    Bounded: this runs inside a PostToolUse hook, where a RecursionError would surface as a
    traceback on every Agent call. A response nested past the bound carries no findings anyone
    could act on anyway.
    """
    if depth > FLATTEN_DEPTH:
        return ""
    if isinstance(response, str):
        return response
    if isinstance(response, dict):
        for key in ("content", "text", "output", "result"):
            if key in response:
                return _flatten(response[key], depth + 1)
        return ""
    if isinstance(response, list):
        return "\n".join(part for part in (_flatten(item, depth + 1) for item in response) if part)
    return ""


def _already_recorded(reviewer: str, cwd: str | None = None) -> bool:
    """Whether this reviewer's pass for *this* tree is already recorded.

    Both halves matter. Without the branch, a pass follows the worktree onto the next piece of
    work; without the digest, a reviewer whose second round reads the same as its first ("checked
    and clean" over changed code) can never be recorded, because the text is on disk already.
    """
    entry = (state.load(cwd).get("reviews") or {}).get(reviewer) or {}
    return (entry.get("branch") == state.current_branch(cwd)
            and entry.get("digest") == reviewer_digest(reviewer, cwd))


def _wears_another_name(reviewer: str, findings: str) -> bool:
    """A report whose header names a different reviewer records nobody. Always refused: no state
    of the gate makes one reviewer's pass count as another's."""
    first = findings.lstrip().splitlines()[0].strip()
    return first.startswith("# ") and first[2:].strip() in known_reviewers() - {reviewer}


def _stored_rounds(reviewer: str, cwd: str | None = None) -> list[tuple[str, str]]:
    """Each round on disk as (branch it was recorded on, its text)."""
    rounds: list[tuple[str, str]] = []
    for block in stored_report(reviewer, cwd).split(f"\n# {reviewer}\n"):
        if not block.strip():
            continue
        match = re.search(r"^branch: (.*)$", block, re.M)
        rounds.append((match.group(1).strip() if match else "", block))
    return rounds


def _is_replay(reviewer: str, findings: str, cwd: str | None = None) -> bool:
    """Whether this text is a round already on disk, replayed rather than reviewed.

    `gate.py reviewers` prints the path of the previous findings, so re-feeding that file is the
    easiest keystroke there is. A round made on another branch is always a replay; one made on
    this branch is a replay only while the pass it belongs to is still recorded — otherwise the
    retry after a failed state write, and a second round that reads the same as the first, would
    both be refused with no way through.
    """
    body = findings.strip()
    for round_branch, text in _stored_rounds(reviewer, cwd):
        # Both directions: a round re-fed on its own, and the whole findings file re-fed at once —
        # `gate.py reviewers` prints that path, so it is the likelier keystroke of the two.
        if body in text or text.strip() in body:
            if round_branch != state.current_branch(cwd):
                return True
            return _already_recorded(reviewer, cwd)
    return False


def note_review_launch(reviewer: str, cwd: str | None = None) -> None:
    """Remember what the reviewer's scope looked like when it started reading.

    A backgrounded reviewer reports minutes later, and the tree can move underneath it in that
    window. Build stages already discard a run whose tree changed while it ran; without this
    snapshot the review gate had no way to notice the same thing.
    """
    entries = reviewer_entries(reviewer, cwd)
    st = state.load(cwd)
    st.setdefault("pending_reviews", {})[reviewer] = {
        "digest": state.digest_of(entries),
        "files": entries,
        "branch": state.current_branch(cwd),
        "at": time.time(),
    }
    state.save(st, cwd)


def moved_under_the_reviewer(reviewer: str, findings: str, cwd: str | None = None) -> bool:
    """Whether this report was written against a tree that has since moved — and is still the
    report that was refused for it.

    The snapshot is taken when a pass is launched, and a reviewer re-run inline produces no launch,
    so a first refusal used to wedge that reviewer for good: nothing could ever refresh the
    snapshot again. Pinning the refusal to the text it refused keeps the property that matters —
    retrying the same stale report changes nothing — while a report the reviewer actually wrote
    afterwards gets through.
    """
    if not review_drift(reviewer, cwd):
        return False
    st = state.load(cwd)
    pending = (st.get("pending_reviews") or {}).get(reviewer)
    if pending is None:
        return False
    digest = hashlib.sha256(findings.encode("utf-8")).hexdigest()[:16]
    if pending.get("refused") in (None, digest):
        pending["refused"] = digest
        state.save(st, cwd)
        return True
    # A different report after the refusal: the reviewer ran again, and the snapshot is spent.
    # Re-aimed at the tree as it is now rather than dropped — dropping it left every later move
    # for that reviewer unmeasured, so a stale report after this one met no drift check at all.
    pending["files"] = reviewer_entries(reviewer, cwd)
    pending.pop("refused", None)
    state.save(st, cwd)
    return False


def review_drift(reviewer: str, cwd: str | None = None) -> list[str]:
    """Files in the reviewer's scope that moved while it was reading. Empty when it never ran
    backgrounded — there is no snapshot to compare against, and nothing to claim."""
    pending = (state.load(cwd).get("pending_reviews") or {}).get(reviewer)
    # A snapshot taken on another branch cannot judge this one: every file differs across a switch,
    # so it would refuse the first honest report here as a tree that "moved".
    if not pending or pending.get("branch") != state.current_branch(cwd):
        return []
    before, now = pending.get("files") or {}, reviewer_entries(reviewer, cwd)
    return sorted({path for path in set(before) | set(now) if before.get(path) != now.get(path)})


def record_review(reviewer: str, text: str, cwd: str | None = None) -> str:
    """Pin a reviewer to the tree its findings were written against.

    Returns the reason it was *not* recorded, empty when it was. A bool told the caller only that
    something went wrong, and the hook reacted to all four reasons the same way — including by
    re-snapshotting the drift evidence it had just refused on.
    """
    findings = review_findings(text)
    if not findings:
        return "no findings"
    if moved_under_the_reviewer(reviewer, findings, cwd):
        return "moved"
    if _wears_another_name(reviewer, findings):
        return "recycled"
    if _is_replay(reviewer, findings, cwd):
        return "recycled"
    # The report goes first. The other order left the entry behind when the write failed, and the
    # structural check in `missing_reviewers` then read the *previous* round's file and called the
    # reviewer done — against code it never saw.
    if not state.save_review_report(reviewer, findings, cwd):
        return "not saved"
    entries = reviewer_entries(reviewer, cwd)
    st = state.load(cwd)
    st.setdefault("reviews", {})[reviewer] = {
        # Scoped to what this reviewer reads, so an unrelated fix does not owe it again.
        "digest": state.digest_of(entries),
        "files": entries,
        "branch": state.current_branch(cwd),
        "rounds": review_rounds(st, reviewer, cwd) + 1,
        "at": time.time(),
    }
    (st.get("pending_reviews") or {}).pop(reviewer, None)
    return "" if state.save(st, cwd) else "not saved"


def known_reviewers() -> set[str]:
    return set(BASE_REVIEWERS) | {name for names in AREA_REVIEWERS.values() for name in names}


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
            missing = missing_reviewers(st, task, cwd)
            if missing:
                debt.append("review — missing: " + ", ".join(missing))
            continue
        if not state.stage_is_current(st, stage, digest):
            entry = (st.get("stages") or {}).get(stage) or {}
            why = "never run" if not entry else (
                "failed" if not entry.get("ok") else "ran against different code")
            debt.append(f"{stage} — {why}")
    return task, debt


# Two passes per branch, and the third is the operator's decision. A reviewer is owed again when
# the files it read move — but fixing what it found is exactly what moves them, and its next round
# is then about the fix. Inside a reviewer's own scope that loop does not converge; it ran eleven
# times on one branch before this cap existed. What the cap lets through is printed, not hidden.
REVIEW_ROUNDS = 2


def review_rounds(st: dict, reviewer: str, cwd: str | None = None) -> int:
    entry = (st.get("reviews") or {}).get(reviewer) or {}
    return entry.get("rounds", 0) if entry.get("branch") == state.current_branch(cwd) else 0


def unreviewed(st: dict, task: dict, cwd: str | None = None) -> list[tuple[str, list[str]]]:
    """Reviewers the gate has stopped demanding while their scope has moved since they last read.

    The gate stops asking; it does not claim the code was read. Saying nothing here would be the
    same defect as recording a reviewer on its launch.
    """
    out = []
    for name in required_reviewers(task, cwd):
        if review_rounds(st, name, cwd) < REVIEW_ROUNDS:
            continue
        delta = reviewer_delta(st, name, cwd)
        if delta:
            out.append((name, delta))
    return out


def missing_reviewers(st: dict, task: dict, cwd: str | None = None) -> list[str]:
    """Reviewers whose own scope has moved since they last ran, or who left no findings behind.

    Previously this compared one whole-tree digest, so a one-line Compose fix owed the vault, the
    server and the terminal reviewer all over again — the fan-out ran ten to twenty times per
    branch and each pass re-read the entire diff. Scoping the comparison is what stops that.

    The report has to exist too. Entries written from an intent rather than a result left no file
    on disk, and comparing digests alone counted them as reviewed — the check has to be structural,
    not a matter of which code path wrote the entry.
    """
    seen = (st.get("reviews") or {})
    branch = state.current_branch(cwd)
    missing = []
    for name in required_reviewers(task, cwd):
        entry = seen.get(name) or {}
        if not reviewer_entries(name, cwd):
            continue  # nothing of this reviewer's in the change: there is nothing for it to read
        if review_rounds(st, name, cwd) >= REVIEW_ROUNDS:
            continue
        if (entry.get("digest") != reviewer_digest(name, cwd) or entry.get("branch") != branch
                or not stored_report(name, cwd)):
            missing.append(name)
    return missing


def stored_report(reviewer: str, cwd: str | None = None) -> str:
    """The findings on disk for this reviewer, or empty when there are none."""
    path = state.review_report_path(reviewer, cwd)
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except (OSError, UnicodeDecodeError):
        return ""


def skipped_reviewers(task: dict, cwd: str | None = None) -> list[str]:
    """Relevant but not installed here — reported so a thinner review is never silent."""
    return [name for name in relevant_reviewers(task) if not is_installed(name, cwd)]
