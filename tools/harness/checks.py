"""Deterministic project rules, checked by grep instead of by a reviewing model.

Everything here is a rule from `docs/coding-guidelines.md` or `CLAUDE.md` that can be decided
without reading the code for meaning: a missing translation, a raw `Text(`, a hex colour, a hard
dependency coordinate. Handing those to an LLM reviewer costs a minute and misses some of them;
a regex costs a second and never gets bored.

Rules apply to **lines this branch adds**, not to the whole repository — legacy is not the branch's
debt. The i18n parity rule is the exception: it is a property of the resource set as a whole, and
the set is clean today, so any drift belongs to the change that caused it.

Escape hatch: `harness-allow: <rule>` anywhere on the offending line — except `version-bump`,
whose file has no *end-of-line* comment syntax, so a trailing marker would land inside the version
string. Its hatch is a line of its own, spelled exactly `# harness-allow: version-bump`.
"""

from __future__ import annotations

import glob
import os
import re
import sys

from . import policy, state

BLOCK, WARN = "block", "warn"


class Finding:
    def __init__(self, rule: str, severity: str, path: str, line: int, message: str):
        self.rule, self.severity = rule, severity
        self.path, self.line, self.message = path, line, message

    def __str__(self) -> str:
        where = f"{self.path}:{self.line}" if self.line else self.path
        return f"[{self.severity}] {self.rule} — {where}\n    {self.message}"


# --- diff plumbing -------------------------------------------------------------------------

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def added_lines(base: str = "", cwd: str | None = None) -> list[tuple[str, int, str]]:
    """(path, line number, text) for every line this branch adds, worktree included."""
    base = base or state.merge_base(cwd=cwd)
    root = state.repo_root(cwd)
    out: list[tuple[str, int, str]] = []

    # One diff per file, rather than one diff parsed for its `+++ b/` headers. An added line whose
    # own text starts with `++ b/…` — legal inside a raw string or a fixture — renders as a header
    # and used to retarget every rule after it onto a path where none of them apply.
    code, names = state.git(["diff", "--name-only", "-z", base], cwd)
    for path in (p for p in names.split("\0") if p) if code == 0 else ():
        # --text: a committed `.gitattributes` with `*.kt -diff` makes git print "Binary files
        # differ" instead of the content, and every rule then sees an empty diff.
        code, diff = state.git(["diff", "-U0", "--no-color", "--text", base, "--", path], cwd)
        if code != 0:
            continue
        lineno, in_hunk = 0, False
        # split("\n"), not splitlines(): Python breaks lines on U+000C, U+001C-1E, U+0085 and
        # U+2028/9, which git does not, and it consumes the byte — truncating the line and hiding
        # the very character the control-chars rule is looking for.
        for raw in diff.split("\n"):
            if raw.startswith("@@"):
                match = HUNK.match(raw)
                if match:
                    lineno = int(match.group(1))
                in_hunk = True
            elif in_hunk and raw.startswith("+"):
                # Inside a hunk every `+` line is content: the file headers are all above it.
                out.append((path, lineno, raw[1:]))
                lineno += 1

    code, untracked = state.git(["ls-files", "--others", "--exclude-standard", "-z"], cwd)
    if code == 0:
        for path in (p for p in untracked.split("\0") if p):
            try:
                with open(os.path.join(root, path), encoding="utf-8") as fh:
                    out.extend((path, i, text.rstrip("\n"))
                               for i, text in enumerate(fh, start=1))
            except (OSError, UnicodeDecodeError):
                continue  # binary or unreadable: no textual rule applies
    return out


def _allowed(text: str, rule: str) -> bool:
    return f"harness-allow: {rule}" in text


def _is_comment(text: str) -> bool:
    stripped = text.strip()
    return stripped.startswith(("//", "*", "/*", "#", "<!--"))


# --- rules ---------------------------------------------------------------------------------

RAW_TEXT = re.compile(r"(?<![A-Za-z0-9_.])Text\s*\(")
RAW_ICON = re.compile(r"(?<![A-Za-z0-9_.])Icon\s*\(")
LITERAL_UI_STRING = re.compile(r"(?<![A-Za-z0-9_.])(?:Txt|Text)\s*\(\s*\"((?:[^\"\\]|\\.)*)\"")
INTERPOLATION = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")
HEX_COLOUR = re.compile(r"Color\s*\(\s*0x")
# Bare names, not import paths: `mockk<Foo>()` is the form that slips past a path-shaped pattern.
TEST_LIB = re.compile(r"\b(kotest|mockk)\b")
RAW_COORD = re.compile(
    r"\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|debugImplementation|"
    r"androidTestImplementation|ksp|kapt)\s*\(\s*\"[^\"]+:[^\"]+:[^\"]*\"")
SECRET_WRITE = re.compile(r"\.writeText\s*\(")
SUSPICIOUS_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(?:Exception|Throwable)\s*\)")
# One definition, in `state`: this rule keeps the byte out of the source, and `save_review_report`
# strips it out of a stored report before the operator cats it. Two lists would drift apart.
CONTROL_RANGES = state.CONTROL_RANGES
CONTROL_CHARS = state.CONTROL_CHARS
TODO_MARK = re.compile(r"(?://|/\*|\*)\s*(TODO|FIXME|XXX)\b")

UI_MAIN = re.compile(r"^composeApp/src/\w+Main/")
PRIMITIVE_FILES = ("DesignPrimitives.kt", "/design/Sym.kt")
THEME_PATHS = ("/theme/", "/design/", "/qr/", "Qr.kt")
SECRET_PATHS = ("/vault/", "/guard/", "/team/", "/share/", "/sync/")
SHORTCUT_FILE = "composeApp/src/commonMain/kotlin/app/skerry/ui/desktop/DesktopShortcuts.kt"
KEYBOARD_SETTINGS = "composeApp/src/commonMain/kotlin/app/skerry/ui/settings/KeyboardSection.kt"
FILE_SIZE_LIMIT = 500
VERSION_FILE = "gradle.properties"
VERSION_RULE = "version-bump"
VERSION_NAME = re.compile(r"^\s*skerry\.versionName\s*=\s*(\S+)\s*$")
VERSION_CODE = re.compile(r"^\s*skerry\.versionCode\s*=\s*(\d+)\s*$")


def _line_rules(added: list[tuple[str, int, str]]) -> list[Finding]:
    found: list[Finding] = []
    for path, line, text in added:
        if not state.is_code(path):
            continue
        # TODO markers live in comments, so this one is judged before comments are skipped.
        if path.endswith(".kt") and TODO_MARK.search(text) and not _allowed(text, "todo"):
            found.append(Finding("todo", WARN, path, line,
                                 "TODO/FIXME added — either do it or drop it before the PR."))
        # Trojan source belongs in a comment by construction: the payload has to sit where the
        # reviewer reads prose and the compiler reads nothing. So this one is judged before
        # comments are skipped, like the TODO rule above it.
        if CONTROL_CHARS.search(text) and not _allowed(text, "control-chars"):
            # In a test these bytes are the fixture — the input a sanitiser is supposed to strip.
            # In shipping code they are a spoof waiting to happen, and unreadable in review.
            found.append(Finding("control-chars", WARN if state.is_test(path) else BLOCK,
                                 path, line,
                                 "raw control or bidi byte — write it as an escape "
                                 "(`\\u202E`) so review can see it."))
        if _is_comment(text):
            continue
        kt = path.endswith(".kt")
        ui = kt and UI_MAIN.match(path) and not any(m in path for m in PRIMITIVE_FILES)

        if ui and RAW_TEXT.search(text) and not _allowed(text, "design-primitives"):
            found.append(Finding("design-primitives", BLOCK, path, line,
                                 "raw `Text(` — the UI uses `Txt`, see coding-guidelines §1."))
        if ui and RAW_ICON.search(text) and not _allowed(text, "design-primitives"):
            found.append(Finding("design-primitives", BLOCK, path, line,
                                 "raw `Icon(` — the UI uses `Sym`."))
        if ui and not _allowed(text, "i18n-hardcoded"):
            match = LITERAL_UI_STRING.search(text)
            # `Txt("#$tag")` and `Txt("${index + 1}")` carry no prose: the letters belong to the
            # expression, not to the user. Replaying this rule over 25 merged PRs, that was every
            # false positive it had.
            prose = INTERPOLATION.sub("", match.group(1)) if match else ""
            if re.search(r"[A-Za-z]{2,}", prose):
                found.append(Finding("i18n-hardcoded", BLOCK, path, line,
                                     f"user-visible literal \"{prose[:40]}\" — move it to "
                                     "composeResources and ship en + ru + zh. A brand name or a "
                                     "protocol token takes `// harness-allow: i18n-hardcoded`."))
        if (ui and HEX_COLOUR.search(text) and not any(t in path for t in THEME_PATHS)
                and not _allowed(text, "design-hex")):
            found.append(Finding("design-hex", BLOCK, path, line,
                                 "hex colour outside the theme — use a `D.*` design token."))
        if (kt or path.endswith(".kts")) and TEST_LIB.search(text) \
                and not _allowed(text, "test-framework"):
            found.append(Finding("test-framework", BLOCK, path, line,
                                 "this repo tests with `kotlin.test` on JUnit 5 — no Kotest, no "
                                 "MockK, fakes are hand-written."))
        if path.endswith(".gradle.kts") and RAW_COORD.search(text) \
                and not _allowed(text, "raw-dependency"):
            found.append(Finding("raw-dependency", BLOCK, path, line,
                                 "raw dependency coordinate — declare it in libs.versions.toml."))
        if (kt and SECRET_WRITE.search(text) and any(p in path for p in SECRET_PATHS)
                and not _allowed(text, "secret-write")):
            found.append(Finding("secret-write", BLOCK, path, line,
                                 "`writeText` on a secret-bearing path — use `atomicWriteUtf8` "
                                 "(atomic + 0600)."))
    return found


def _cancellation_rule(added: list[tuple[str, int, str]], cwd: str | None) -> list[Finding]:
    """A `catch (e: Exception)` added to a file that suspends and never names CancellationException.

    Coarse on purpose: a warning, because the correct rethrow can live in a helper. The project's
    most expensive bug class deserves a nudge even when the check cannot be certain.
    """
    root = state.repo_root(cwd)
    found, checked = [], {}
    for path, line, text in added:
        if not path.endswith(".kt") or not SUSPICIOUS_CATCH.search(text):
            continue
        if _allowed(text, "cancellation") or _is_comment(text):
            continue
        if path not in checked:
            try:
                with open(os.path.join(root, path), encoding="utf-8") as fh:
                    body = fh.read()
            except OSError:
                body = ""
            checked[path] = "suspend " in body and "CancellationException" not in body
        if checked[path]:
            found.append(Finding("cancellation", WARN, path, line,
                                 "`catch (Exception)` in a suspending file that never mentions "
                                 "CancellationException — cancellation is being swallowed."))
    return found


def _i18n_parity(cwd: str | None) -> list[Finding]:
    root = state.repo_root(cwd)
    base = os.path.join(root, "composeApp/src/commonMain/composeResources")
    if not os.path.isdir(base):
        return []

    unreadable: dict[str, list[str]] = {"locale": [], "source": []}
    cached: dict[str, str] = {}

    def read(file: str) -> str:
        """A locale file's text, read once per run, or empty.

        A file the check cannot open is recorded rather than passed over: an unreadable locale
        looks exactly like an untranslated one in the findings below. The cache is what keeps that
        count honest — the name scan and the plural scan both walk every locale, and an uncached
        read reported one broken file as two.
        """
        if file not in cached:
            try:
                with open(file, encoding="utf-8") as fh:
                    cached[file] = fh.read()
            except (OSError, UnicodeDecodeError):
                # A locale file saved in cp1251 is the likeliest way this fails, and that raises
                # ValueError, not OSError — uncaught it took the whole `checks` stage down.
                unreadable["locale"].append(os.path.relpath(file, root))
                cached[file] = ""
        return cached[file]

    def read_source(file: str) -> str:
        """A Kotlin source, read once each — an unreadable one hides a usage, not a translation."""
        try:
            with open(file, encoding="utf-8") as fh:
                return fh.read()
        except (OSError, UnicodeDecodeError):
            unreadable["source"].append(os.path.relpath(file, root))
            return ""

    def keys(locale: str) -> dict[tuple[str, str], str]:
        """Every translatable name, by kind — a plural and an array need a translation as much as
        a string does, and each kind lives in its own namespace.

        The attribute is matched loosely: `<string translatable="false" name="x">` is the form
        Android tooling emits, and a name the pattern misses is silently exempt from parity.
        """
        out = {}
        for file in sorted(glob.glob(os.path.join(base, locale, "*.xml"))):
            body = read(file)
            for kind, key in re.findall(r'<(string|string-array|plurals)\s+[^>]*?name="([^"]+)"', body):
                out[(kind, key)] = os.path.relpath(file, root)
        return out

    def label(entry: tuple[str, str]) -> str:
        kind, key = entry
        return key if kind == "string" else f"{kind} {key}"

    en, found = keys("values"), []
    for locale in ("values-ru", "values-zh"):
        other = keys(locale)
        for key in sorted(set(en) - set(other)):
            found.append(Finding("i18n-parity", BLOCK, en[key], 0,
                                 f"`{label(key)}` has no {locale} translation — strings ship en + ru + zh."))
        for key in sorted(set(other) - set(en)):
            found.append(Finding("i18n-parity", BLOCK, other[key], 0,
                                 f"`{label(key)}` exists only in {locale} — a stale or misspelt key."))

    # The plural categories each language actually needs (CLDR). A name that exists in a locale but
    # carries only `other` is not a translation: the reader falls back to `other` silently, so
    # Russian renders "1 файлов" with every gate green.
    required = {"values": ("one", "other"),
                "values-ru": ("one", "few", "many", "other"),
                "values-zh": ("other",)}
    for locale, categories in required.items():
        for file in sorted(glob.glob(os.path.join(base, locale, "*.xml"))):
            rel = os.path.relpath(file, root)
            for key, block in re.findall(r'<plurals\s+[^>]*?name="([^"]+)"(.*?)</plurals>', read(file), re.S):
                # An empty item is not a form: it draws a blank where the number belongs, and the
                # reader is as silent about it as it is about a category that was never declared.
                written = {category for category, text
                           in re.findall(r'<item quantity="([a-z]+)"[^>]*>(.*?)</item>', block, re.S)
                           if text.strip()}
                missing = [c for c in categories if c not in written]
                if missing:
                    found.append(Finding("i18n-parity", BLOCK, rel, 0,
                                         f"`plurals {key}` has no {', '.join(missing)} form — "
                                         f"{locale} needs {'/'.join(categories)}; a missing or "
                                         "empty item falls back to `other` and reads wrong."))

    # `Res.array` is the accessor of a `<string-array>`; the other two are named after their tag.
    accessor = {"string": "string", "plurals": "plurals", "string-array": "array"}
    tag_of = {value: key for key, value in accessor.items()}
    used: set[tuple[str, str]] = set()
    for file in glob.glob(os.path.join(root, "composeApp/src/**/*.kt"), recursive=True):
        for kind, key in re.findall(r"Res\.(string|plurals|array)\.([A-Za-z0-9_]+)", read_source(file)):
            used.add((tag_of[kind], key))
    for kind, key in sorted(used - set(en)):
        found.append(Finding("i18n-parity", BLOCK, "composeApp", 0,
                             f"`Res.{accessor[kind]}.{key}` is used but defined nowhere."))

    # Which way the sweep is wrong depends on what could not be read, so the two are counted apart.
    if unreadable["locale"]:
        found.append(Finding("i18n-parity", WARN, unreadable["locale"][0], 0,
                             f"{len(unreadable['locale'])} resource file(s) could not be read — a "
                             "translation reported missing above may be that read failure."))
    if unreadable["source"]:
        found.append(Finding("i18n-parity", WARN, unreadable["source"][0], 0,
                             f"{len(unreadable['source'])} source file(s) could not be read — a "
                             "stale `Res.` reference in them goes unreported."))
    return found


def _shortcut_rule(added: list[tuple[str, int, str]]) -> list[Finding]:
    touched = {path for path, _, _ in added}
    if KEYBOARD_SETTINGS in touched:
        return []
    for path, line, text in added:
        if path == SHORTCUT_FILE and "Key." in text and not _allowed(text, "shortcut-settings"):
            return [Finding("shortcut-settings", BLOCK, path, line,
                            "a key binding changed without a row in Settings → Keyboard "
                            f"({KEYBOARD_SETTINGS}) — they ship in the same commit.")]
    return []


def _version_bump(added: list[tuple[str, int, str]], cwd: str | None, base: str) -> list[Finding]:
    """A release bump must move both halves of the version, and move the code upwards.

    The release workflow used to stamp the APK with `github.run_number`, monotonic for free. It now
    reads `skerry.versionCode` as it is written here, so forgetting the bump no longer fails
    anywhere in this repository — it fails at the store upload, days later, on an artifact that is
    already tagged and published.

    The hatch is a comment line in the file, not the offending line: a `.properties` value runs to
    the end of its line, so a trailing `harness-allow` would end up inside the version string.
    """
    mine = [(line, text) for path, line, text in added if path == VERSION_FILE]
    # A line that is nothing but the marker, not merely a line containing it: the file's own comment
    # block documents this rule, and a reworded paragraph re-adds every line of it — on the release
    # bump, which is the one commit the rule exists for.
    if any(text.strip() == f"# harness-allow: {VERSION_RULE}" for _, text in mine):
        return []
    named = coded = None
    for line, text in mine:
        if VERSION_NAME.match(text):
            named = line
        match = VERSION_CODE.match(text)
        if match:
            coded = (line, int(match.group(1)))
    if named is None:
        return []
    if coded is None:
        # git emits changed lines only, so a code left exactly where it was is absent from the diff
        # — "not written" and "not moved" are the same mistake and get the same sentence.
        return [Finding(VERSION_RULE, BLOCK, VERSION_FILE, named,
                        "skerry.versionName moved without skerry.versionCode — Android refuses an "
                        "update whose code did not grow, and the stores reject the upload.")]
    line, code = coded
    previous, problem = _previous_version_code(cwd, base)
    if problem:
        return [Finding(VERSION_RULE, BLOCK, VERSION_FILE, line, problem)]
    if previous is not None and code <= previous:
        return [Finding(VERSION_RULE, BLOCK, VERSION_FILE, line,
                        f"skerry.versionCode {code} is not above {previous} — an Android version "
                        "code only ever grows.")]
    return []


def _baseline_refs(cwd: str | None, base: str) -> list[tuple[str, str]]:
    """Every commit whose version code this branch has to clear, as (name, commit).

    Not the branch point alone: two branches forked from the same commit can reach for the same
    number, and the second one has to be told. Not one ref either — a local `main` that has not been
    pulled since the last release answers with a code that is already spent, and a baseline that can
    only be *too low* turns "no comparison" into a confident wrong one.

    A name is kept once `rev-parse --verify` peels it to a commit — `git show :gradle.properties` is
    not a failure but the syntax for reading the *index*, which hands back the very value being
    committed — and only if that commit shares history with HEAD. This repository has had its
    history rewritten once; an abandoned ref left behind by that can carry a high code belonging to
    no lineage we are on, and taking the maximum would make it block every branch forever.

    Dedup is on the commit, not the name: in the ordinary case all three candidates are the same
    commit, and a finding that names it twice reads as two baselines failing instead of one.
    """
    found: list[tuple[str, str]] = []
    for ref in ("origin/main", "main", base or state.merge_base(cwd=cwd)):
        if not ref:
            continue
        code, out = state.git(["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], cwd)
        commit = out.strip()
        if code != 0 or any(commit == known for _, known in found):
            continue
        if state.git(["merge-base", commit, "HEAD"], cwd)[0] == 0:
            found.append((ref, commit))
    return found


def _previous_version_code(cwd: str | None, base: str) -> tuple[int | None, str | None]:
    """(the code to beat, why it could not be read).

    The highest code across every baseline, so a stale ref can lower the bar for nobody.

    A file that does not exist there is the one silence kept: that is what the first commit of a
    fresh clone looks like, and it is not a version that went backwards. Everything else is
    reported — a monotonicity rule that quietly stops comparing is worse than one that says it
    could not.
    """
    refs = _baseline_refs(cwd, base)
    if not refs:
        return None, ("neither main nor the branch point resolves, so the previous "
                      "skerry.versionCode could not be read — it was not compared.")
    codes, seen = [], []
    for ref, commit in refs:
        code, text = state.git(["show", f"{commit}:{VERSION_FILE}"], cwd)
        if code != 0:
            continue
        seen.append(ref)
        for line in text.split("\n"):
            match = VERSION_CODE.match(line)
            if match:
                codes.append(int(match.group(1)))
                break
    if codes:
        return max(codes), None
    if seen:
        return None, (f"{VERSION_FILE} at {', '.join(seen)} carries no skerry.versionCode — the "
                      "value this branch has to beat could not be read, so it was not compared.")
    return None, None


def _tests_present(added: list[tuple[str, int, str]], task: dict) -> list[Finding]:
    if task["kind"] not in ("bug", "feature"):
        return []
    # `.py` under the harness counts as source for the same reason `.kt` does — it is behaviour
    # someone has to be able to break. Its test is `selftest.py`, which `state.is_test` knows.
    src = {p for p, _, _ in added if state.is_code(p) and not state.is_test(p)
           and (p.endswith(".kt") or p.startswith(("tools/harness/", ".claude/hooks/")))}
    tests = {p for p, _, _ in added if state.is_test(p)}
    if src and not tests:
        return [Finding("tests-present", BLOCK, sorted(src)[0], 0,
                        f"a {task['kind']} changed sources without touching a single test "
                        "— step 1 of the loop is the failing test.")]
    return []


def _file_size(cwd: str | None, added: list[tuple[str, int, str]]) -> list[Finding]:
    root = state.repo_root(cwd)
    found = []
    for path in sorted({p for p, _, _ in added if p.endswith(".kt")}):
        try:
            with open(os.path.join(root, path), encoding="utf-8") as fh:
                count = sum(1 for _ in fh)
        except OSError:
            continue
        if count > FILE_SIZE_LIMIT:
            found.append(Finding("file-size", WARN, path, 0,
                                 f"{count} lines after this change — guidelines §2 splits a file "
                                 f"approaching {FILE_SIZE_LIMIT}."))
    return found


def run(cwd: str | None = None, task: dict | None = None, base: str = "") -> list[Finding]:
    task = task or policy.classify(cwd)
    added = added_lines(base=base, cwd=cwd)
    findings = _line_rules(added)
    findings += _cancellation_rule(added, cwd)
    findings += _shortcut_rule(added)
    findings += _version_bump(added, cwd, base)
    findings += _tests_present(added, task)
    findings += _file_size(cwd, added)
    findings += _i18n_parity(cwd)
    return findings


def main(argv: list[str]) -> int:
    findings = run()
    blocking = [f for f in findings if f.severity == BLOCK]
    warnings = [f for f in findings if f.severity == WARN]
    for finding in blocking + warnings:
        print(finding)
    if not findings:
        print("checks: clean")
    else:
        print(f"\nchecks: {len(blocking)} blocking, {len(warnings)} warnings")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
