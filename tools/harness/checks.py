"""Deterministic project rules, checked by grep instead of by a reviewing model.

Everything here is a rule from `docs/coding-guidelines.md` or `CLAUDE.md` that can be decided
without reading the code for meaning: a missing translation, a raw `Text(`, a hex colour, a hard
dependency coordinate. Handing those to an LLM reviewer costs a minute and misses some of them;
a regex costs a second and never gets bored.

Rules apply to **lines this branch adds**, not to the whole repository — legacy is not the branch's
debt. The i18n parity rule is the exception: it is a property of the resource set as a whole, and
the set is clean today, so any drift belongs to the change that caused it.

Escape hatch: `harness-allow: <rule>` anywhere on the offending line.
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

    code, diff = state.git(["diff", "-U0", "--no-color", base], cwd)
    if code == 0:
        path, lineno = "", 0
        for raw in diff.splitlines():
            if raw.startswith("+++ b/"):
                path, lineno = raw[6:], 0
            elif raw.startswith("@@"):
                match = HUNK.match(raw)
                if match:
                    lineno = int(match.group(1))
            elif raw.startswith("+") and path and not raw.startswith("+++"):
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
# Built from code points rather than written out: the pattern's own subject matter is invisible
# characters, and a literal one here would be unreviewable — and would trip this very rule.
CONTROL_RANGES = (
    (0x00, 0x08), (0x0B, 0x0C), (0x0E, 0x1F),   # C0 minus tab, LF, CR
    (0x200B, 0x200F), (0x202A, 0x202E), (0x2066, 0x2069),  # zero-width and bidi overrides
)
CONTROL_CHARS = re.compile(
    "[" + "".join(f"\\U{lo:08X}-\\U{hi:08X}" for lo, hi in CONTROL_RANGES) + "]")
TODO_MARK = re.compile(r"(?://|/\*|\*)\s*(TODO|FIXME|XXX)\b")

UI_MAIN = re.compile(r"^composeApp/src/\w+Main/")
PRIMITIVE_FILES = ("DesignPrimitives.kt", "/design/Sym.kt")
THEME_PATHS = ("/theme/", "/design/", "/qr/", "Qr.kt")
SECRET_PATHS = ("/vault/", "/guard/", "/team/", "/share/", "/sync/")
SHORTCUT_FILE = "composeApp/src/commonMain/kotlin/app/skerry/ui/desktop/DesktopShortcuts.kt"
KEYBOARD_SETTINGS = "composeApp/src/commonMain/kotlin/app/skerry/ui/settings/KeyboardSection.kt"
FILE_SIZE_LIMIT = 500


def _line_rules(added: list[tuple[str, int, str]]) -> list[Finding]:
    found: list[Finding] = []
    for path, line, text in added:
        if not state.is_code(path):
            continue
        # TODO markers live in comments, so this one is judged before comments are skipped.
        if path.endswith(".kt") and TODO_MARK.search(text) and not _allowed(text, "todo"):
            found.append(Finding("todo", WARN, path, line,
                                 "TODO/FIXME added — either do it or drop it before the PR."))
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
        if CONTROL_CHARS.search(text) and not _allowed(text, "control-chars"):
            # In a test these bytes are the fixture — the input a sanitiser is supposed to strip.
            # In shipping code they are a spoof waiting to happen, and unreadable in review.
            found.append(Finding("control-chars", WARN if state.is_test(path) else BLOCK,
                                 path, line,
                                 "raw control or bidi byte in a literal — write it as an escape "
                                 "(`\\u202E`), it is invisible in review otherwise."))
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

    def keys(locale: str) -> dict[str, str]:
        out = {}
        for file in sorted(glob.glob(os.path.join(base, locale, "*.xml"))):
            try:
                body = open(file, encoding="utf-8").read()
            except OSError:
                continue
            for key in re.findall(r'<string name="([^"]+)"', body):
                out[key] = os.path.relpath(file, root)
        return out

    en, found = keys("values"), []
    for locale in ("values-ru", "values-zh"):
        other = keys(locale)
        for key in sorted(set(en) - set(other)):
            found.append(Finding("i18n-parity", BLOCK, en[key], 0,
                                 f"`{key}` has no {locale} translation — strings ship en + ru + zh."))
        for key in sorted(set(other) - set(en)):
            found.append(Finding("i18n-parity", BLOCK, other[key], 0,
                                 f"`{key}` exists only in {locale} — a stale or misspelt key."))

    used: set[str] = set()
    for file in glob.glob(os.path.join(root, "composeApp/src/**/*.kt"), recursive=True):
        try:
            used |= set(re.findall(r"Res\.string\.([A-Za-z0-9_]+)", open(file, encoding="utf-8").read()))
        except OSError:
            continue
    for key in sorted(used - set(en)):
        found.append(Finding("i18n-parity", BLOCK, "composeApp", 0,
                             f"`Res.string.{key}` is used but defined nowhere."))
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


def _tests_present(added: list[tuple[str, int, str]], task: dict) -> list[Finding]:
    if task["kind"] not in ("bug", "feature"):
        return []
    src = {p for p, _, _ in added if state.is_code(p) and not state.is_test(p) and p.endswith(".kt")}
    tests = {p for p, _, _ in added if state.is_test(p)}
    if src and not tests:
        return [Finding("tests-present", BLOCK, sorted(src)[0], 0,
                        f"a {task['kind']} changed Kotlin sources without touching a single test "
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
