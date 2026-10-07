"""Retained regression tests for deterministic rules and Git content identity."""
from __future__ import annotations
import os
import subprocess
import unittest
import tempfile
import unittest.mock
from harness import checks, gate, policy, state
from support import SandboxCase

def run_git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True).stdout
class TestRelevance(unittest.TestCase):
    def test_code_files_count(self):
        for path in ("shared/src/commonMain/kotlin/A.kt", "build.gradle.kts",
                     "composeApp/src/commonMain/composeResources/values/strings.xml",
                     "gradle/libs.versions.toml"):
            self.assertTrue(state.is_code(path), path)

    def test_prose_and_generated_files_do_not(self):
        for path in ("README.md", "docs/notes.md", "licenses/example.md",
                     "composeApp/build/generated/Thing.kt", "docs/img/x.png"):
            self.assertFalse(state.is_code(path), path)

    def test_the_harness_counts_as_code_it_gates(self):
        # It used to be ignored, so deleting a rule moved no digest and the stage that had already
        # run against the old rule set stayed green.
        for path in ("tools/harness/checks.py", ".agents/hooks/guard-git.py"):
            self.assertTrue(state.is_code(path), path)

    def test_a_reviewer_definition_is_code_the_gate_watches(self):
        # The agent files are the executable content of the review gate: prose everywhere else,
        # but editing one changes what a reviewer looks for.
        self.assertTrue(state.is_code(".agents/reviewers/skerry-security-reviewer.md"))
        self.assertFalse(state.is_code("docs/design/notes.md"))
        self.assertEqual(policy.areas([".agents/reviewers/skerry-reviewer.md"]), ["harness"])

    def test_cross_agent_instructions_are_code_the_gate_watches(self):
        # Codex and other agents execute these files through the same portable policy. A
        # change here can weaken the same process, so it must reopen the harness suite too.
        for path in ("AGENTS.md", "docs/development-process.md"):
            self.assertTrue(state.is_code(path), path)
            self.assertEqual(policy.areas([path]), ["harness"])
        self.assertFalse(state.is_code(".agents/ONBOARDING.md"))

    def test_the_invisible_byte_list_covers_the_bidi_and_c1_families(self):
        # ALM is a bidi control like LRM; C1 opens CSI/OSC in a UTF-8 xterm; the word-joiner block
        # and the BOM are invisible in review the same way the zero-width space is.
        for point in (0x061C, 0x2060, 0xFEFF, 0x007F, 0x009B, 0x202E, 0x200B,
                      0x2028, 0x2029, 0x000C, 0x0085):
            self.assertIsNotNone(state.CONTROL_CHARS.search(chr(point)), hex(point))
        for point in (0x0009, 0x000A, 0x0041, 0x0410, 0x4E2D, 0x00A0):
            self.assertIsNone(state.CONTROL_CHARS.search(chr(point)), hex(point))

    def test_test_sources_are_marked(self):
        self.assertTrue(state.is_test("shared/src/commonTest/kotlin/AT.kt"))
        self.assertTrue(state.is_test("composeApp/src/desktopTest/kotlin/B.kt"))
        self.assertFalse(state.is_test("shared/src/commonMain/kotlin/A.kt"))


class TestDigest(SandboxCase):
    def test_edit_outside_the_session_invalidates(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        # The hole this replaces: a change made by sed, git apply or an outside editor left the
        # timestamp-based recorder none the wiser.
        subprocess.run(["sed", "-i", "s/1/2/", "shared/src/commonMain/kotlin/A.kt"],
                       cwd=self.cwd, check=True)
        self.assertNotEqual(before, state.tree_digest("all", self.cwd))

    def test_commit_does_not_invalidate(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        self.box.commit("work")
        self.assertEqual(before, state.tree_digest("all", self.cwd),
                         "committing changes HEAD, not content — a gated tree stays gated")

    def test_committing_a_deletion_does_not_invalidate(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.write("shared/src/commonMain/kotlin/B.kt", "val b = 1\n")
        self.box.commit("two files")
        os.remove(os.path.join(self.cwd, "shared/src/commonMain/kotlin/B.kt"))
        before = state.tree_digest("all", self.cwd)
        self.box.commit("drop one")
        self.assertEqual(before, state.tree_digest("all", self.cwd),
                         "a deleted file is gone either way — the commit only records that")

    def test_committing_a_rename_does_not_invalidate(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.commit("one file")
        os.remove(os.path.join(self.cwd, "shared/src/commonMain/kotlin/A.kt"))
        self.box.write("shared/src/commonMain/kotlin/Moved.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        self.box.commit("move it")
        self.assertEqual(before, state.tree_digest("all", self.cwd),
                         "the hole this closes: a gate green before the commit reopened after it")

    def test_deleting_a_file_still_invalidates(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.write("shared/src/commonMain/kotlin/B.kt", "val b = 1\n")
        self.box.commit("two files")
        before = state.tree_digest("all", self.cwd)
        os.remove(os.path.join(self.cwd, "shared/src/commonMain/kotlin/B.kt"))
        self.assertNotEqual(before, state.tree_digest("all", self.cwd),
                            "deleting code is an edit like any other")

    def test_revert_restores_the_digest(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        original = state.tree_digest("all", self.cwd)
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 999\n")
        self.assertNotEqual(original, state.tree_digest("all", self.cwd))
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(original, state.tree_digest("all", self.cwd))

    def test_prose_does_not_move_the_digest(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("all", self.cwd)
        self.box.write("docs/notes.md", "prose\n")
        self.assertEqual(before, state.tree_digest("all", self.cwd))

    def test_src_scope_ignores_tests(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        before = state.tree_digest("src", self.cwd)
        self.box.write("shared/src/commonTest/kotlin/ATest.kt", "// test\n")
        self.assertEqual(before, state.tree_digest("src", self.cwd))
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 2\n")
        self.assertNotEqual(before, state.tree_digest("src", self.cwd))


class TestChecks(SandboxCase):
    """Every blocking rule, proved both ways: it fires, and clean code does not trip it."""

    def _findings(self, rule: str) -> list:
        task = {"kind": "refactor", "areas": [], "paths": [], "code_paths": []}
        return [f for f in checks.run(self.cwd, task) if f.rule == rule]

    def setUp(self) -> None:
        super().setUp()
        self.box.branch("refactor/checks")

    def test_raw_text_and_icon_are_blocked(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Text(\"x\") }\nfun b() { Icon(y) }\n")
        rules = {f.rule for f in self._findings("design-primitives")}
        self.assertEqual(rules, {"design-primitives"})
        self.assertEqual(len(self._findings("design-primitives")), 2)

    def test_txt_and_sym_are_fine(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Txt(stringResource(Res.string.k)) }\nfun b() { Sym(Icons.X) }\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_similar_names_are_not_false_positives(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val a = BasicText(x)\nval b = TextField(y)\nval c = IconButton(z)\n"
                       "val d = TextStyle(w)\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_root_uppercase_on_a_ui_label_is_blocked(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "fun a() { Txt(label.uppercase()) }\n")
        self.assertEqual(len(self._findings("label-case")), 1)

    def test_label_uppercase_and_tokens_are_fine(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Txt(labelUppercase(label)) }\n"
                       "val t = keyType.uppercase() // harness-allow: label-case\n"
                       "val u = text.uppercase(locale)\n")
        self.box.write("composeApp/src/commonMain/kotlin/app/skerry/ui/design/LabelCase.kt",
                       "fun x(t: String) = t.uppercase()\n")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val h = hex.uppercase()\n")
        self.assertEqual(self._findings("label-case"), [])

    def test_hardcoded_ui_string_is_blocked(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "fun a() { Txt(\"Connect\") }\n")
        self.assertEqual(len(self._findings("i18n-hardcoded")), 1)

    def test_punctuation_literal_is_not_a_string_to_translate(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "fun a() { Txt(\" · \") }\n")
        self.assertEqual(self._findings("i18n-hardcoded"), [])

    def test_interpolation_alone_is_not_prose(self):
        # Every false positive this rule produced across 25 merged PRs was of this shape.
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Txt(\"${state.index + 1}\") }\n"
                       "fun b() { Txt(\"#$tag\") }\n"
                       "fun c() { Txt(\"$count/$total\") }\n")
        self.assertEqual(self._findings("i18n-hardcoded"), [])

    def test_prose_around_interpolation_still_counts(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Txt(\"$count hosts online\") }\n")
        self.assertEqual(len(self._findings("i18n-hardcoded")), 1)

    def test_hex_colour_outside_the_theme_is_blocked(self):
        self.box.write("composeApp/src/commonMain/kotlin/ui/S.kt", "val c = Color(0xFF102030)\n")
        self.assertEqual(len(self._findings("design-hex")), 1)

    def test_hex_colour_inside_the_theme_is_allowed(self):
        self.box.write("composeApp/src/commonMain/kotlin/ui/theme/C.kt", "val c = Color(0xFF102030)\n")
        self.assertEqual(self._findings("design-hex"), [])

    def test_kotest_and_mockk_are_blocked(self):
        self.box.write("shared/src/commonTest/kotlin/T.kt",
                       "import io.kotest.matchers.shouldBe\nval m = mockk<Foo>()\n")
        self.assertEqual(len(self._findings("test-framework")), 2)

    def test_raw_dependency_coordinate_is_blocked(self):
        self.box.write("shared/build.gradle.kts",
                       "dependencies {\n  implementation(\"io.ktor:ktor-client:3.0.0\")\n}\n")
        self.assertEqual(len(self._findings("raw-dependency")), 1)

    def test_version_catalog_reference_is_fine(self):
        self.box.write("shared/build.gradle.kts",
                       "dependencies {\n  implementation(libs.ktor.client)\n}\n")
        self.assertEqual(self._findings("raw-dependency"), [])

    def test_plain_write_on_a_secret_path_is_blocked(self):
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt", "fun s() { file.writeText(pw) }\n")
        self.assertEqual(len(self._findings("secret-write")), 1)

    def test_atomic_write_is_fine(self):
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       "fun s() { atomicWriteUtf8(file, pw) }\n")
        self.assertEqual(self._findings("secret-write"), [])

    def test_invisible_characters_are_blocked_in_shipping_code(self):
        bidi = chr(0x202E)
        self.box.write("shared/src/commonMain/kotlin/A.kt", f"val a = \"x{bidi}y\"\n")
        found = self._findings("control-chars")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_invisible_characters_in_a_test_fixture_only_warn(self):
        # A sanitiser's test has to contain the byte it strips; that is the fixture, not a spoof.
        bidi = chr(0x202E)
        self.box.write("shared/src/commonTest/kotlin/ATest.kt", f"val a = \"ssh{bidi}d\"\n")
        found = self._findings("control-chars")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.WARN)

    def test_invisible_characters_are_blocked_in_a_comment_too(self):
        # Trojan source lives in comments by design: the payload has to sit where the reviewer
        # reads prose and the compiler reads nothing.
        bidi = chr(0x202E)
        self.box.write("shared/src/commonMain/kotlin/A.kt", f"// drop{bidi} the table\n")
        found = self._findings("control-chars")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_a_line_that_looks_like_a_diff_header_does_not_retarget_the_rules(self):
        # `git diff -U0` renders an added line `++ b/x` as `+++ b/x`, which the parser read as a
        # new file header: every rule after it was applied to the attacker's path instead.
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       'val sample = """\n'
                       "++ b/docs/notes.md\n"
                       '"""\n'
                       "fun leak() { file.writeText(secret) }\n")
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1,
                         "content cannot decide which path the rules are applied to")

    def test_the_line_that_does_the_spoofing_is_judged_like_any_other(self):
        # Skipping a `+++` line inside a hunk would let the same trick hide one line — its own.
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       'val sample = """\n'
                       '++ b/notes.md" + file.writeText(secret)\n'
                       '"""\n')
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1)

    def test_a_separator_python_invents_does_not_truncate_a_line(self):
        # `splitlines()` breaks on U+000C, U+001C-1E, U+0085, U+2028/9 — none of which git or
        # Kotlin treat as line ends — and eats the byte, so the rest of the line was never judged
        # and the invisible byte itself became invisible to the rule that looks for it.
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       "fun b(f: Path, s: String) { f\u000c.writeText(s) }\n")
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1)
        self.assertEqual(len(self._findings("control-chars")), 1)

    def test_a_gitattribute_cannot_hide_a_file_from_the_rules(self):
        # `*.kt -diff` makes git print "Binary files differ" instead of the content.
        self.box.write(".gitattributes", "*.kt -diff\n")
        self.box.write("shared/src/commonMain/kotlin/vault/V.kt",
                       "fun b(f: Path, s: String) { f.writeText(s) }\n")
        self.box.commit("vault")
        self.assertEqual(len(self._findings("secret-write")), 1)

    def test_a_name_that_reads_as_a_pathspec_is_still_diffed(self):
        # git parses `:(icase)x.kt` as pathspec magic, not as a file name, so the per-file diff
        # came back empty and every line-based rule was applied to nothing.
        self.box.write(":(icase)Payload.kt", f"// drop{chr(0x202E)} the table\n")
        self.box.commit("payload")
        self.assertEqual(len(self._findings("control-chars")), 1)

    def test_a_file_name_that_is_not_utf8_does_not_disarm_the_gate(self):
        # git hands the name back as raw bytes; a strict decode raised out of `gate_debt`, and the
        # commit guard catches everything and allows — the whole gate off for one bad file name.
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        broken = os.path.join(self.cwd.encode(), b"shared/src/commonMain/kotlin/\xff.kt")
        with open(broken, "wb") as fh:
            fh.write(b"val b = 2\n")
        self.addCleanup(os.remove, broken)
        debt = gate.debt(cwd=self.cwd)
        self.assertTrue(debt, "a file the harness cannot name is not a gate it can skip")
        self.assertNotEqual(state.tree_digest("all", self.cwd), "empty")

    def test_escaped_control_characters_are_fine(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = \"x\\u001Fy\"\n")
        self.assertEqual(self._findings("control-chars"), [])

    def test_allow_comment_silences_a_rule(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "fun a() { Text(\"x\") } // harness-allow: design-primitives\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_comments_are_not_code(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "// Text(\"x\") is what this used to be\n")
        self.assertEqual(self._findings("design-primitives"), [])

    def test_shortcut_without_a_settings_row_is_blocked(self):
        self.box.write(checks.SHORTCUT_FILE, "val s = Key.F1\n")
        self.assertEqual(len(self._findings("shortcut-settings")), 1)

    def test_shortcut_with_a_settings_row_is_fine(self):
        self.box.write(checks.SHORTCUT_FILE, "val s = Key.F1\n")
        self.box.write(checks.KEYBOARD_SETTINGS, "val row = KeyboardBinding(label, \"F1\")\n")
        self.assertEqual(self._findings("shortcut-settings"), [])

    def test_a_version_name_without_a_code_is_blocked(self):
        self.box.write("gradle.properties", "skerry.versionName=0.4.1\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_a_version_name_with_a_code_is_fine(self):
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_version_code_alone_is_fine(self):
        # Seeding the code without touching the name is the safe direction — the rule is about a
        # release that moves the name and leaves the code behind, not the reverse.
        self.box.write("gradle.properties", "skerry.versionCode=116\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_version_code_that_does_not_grow_is_blocked(self):
        self._seed_version("0.4.0", 200)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)
        # The message proves which branch fired: a code equal to the base is absent from the diff
        # entirely, so counting findings cannot tell the comparison from the missing-code rule.
        self.assertIn("is not above 200", found[0].message)

    def test_a_version_code_that_grows_is_fine(self):
        self._seed_version("0.4.0", 116)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_code_a_sibling_branch_already_took_is_blocked(self):
        # main moved to 117 after this branch forked, so 117 is still an addition against the fork
        # point — only a comparison against main's tip can see that it is already spent.
        self._seed_version("0.4.1", 117, merge_back=False)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)
        self.assertIn("is not above 117", found[0].message)

    def test_a_baseline_without_a_code_is_reported_not_ignored(self):
        # The file exists but carries no code line: a real previous value may have been there and
        # the rule stopped comparing, so it says so instead of passing in silence.
        self._seed_version("0.4.0", None)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        # BLOCK, not WARN: a warning is print-only here — `checks.main` sets its exit code from the
        # blocking findings alone, so "could not compare" would commit exactly like "compared fine".
        self.assertEqual(found[0].severity, checks.BLOCK)

    def test_an_allow_comment_silences_the_version_rule(self):
        self._seed_version("0.4.0", 200)
        self.box.write("gradle.properties",
                       "# harness-allow: version-bump\n"
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_prose_about_the_hatch_does_not_invoke_it(self):
        # The file's own comment block documents this rule; a reworded paragraph must not disarm it.
        self._seed_version("0.4.0", 200)
        self.box.write("gradle.properties",
                       "# the version-bump check takes a harness-allow: version-bump line\n"
                       "skerry.versionName=0.4.1\nskerry.versionCode=116\n")
        self.assertEqual(len(self._findings("version-bump")), 1)

    def test_a_stale_baseline_cannot_lower_the_bar(self):
        # main regressed below the branch point — a rewritten history, or a ref never pulled. The
        # highest code any baseline knows is the one to beat, so the lower ref buys nothing.
        self._seed_version("0.4.0", 200)
        run_git(self.box.path, "checkout", "-q", "main")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.0\nskerry.versionCode=100\n")
        self.box.commit("main regresses")
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=150\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertIn("is not above 200", found[0].message)

    def test_a_tree_with_no_baseline_at_all_is_reported(self):
        # Outside a repository both git calls fail, exactly as they do on an unborn HEAD — where the
        # old code read `git show :gradle.properties`, which is the *index*, and compared the value
        # being committed with itself.
        previous, problem = checks._previous_version_code(tempfile.gettempdir(), "")
        self.assertIsNone(previous)
        self.assertTrue(problem)

    def test_an_unrelated_ref_cannot_raise_the_bar(self):
        # A leftover origin/main from before a history rewrite: it resolves, and it carries a high
        # code that belongs to a lineage this branch is not on. Taking the maximum unfiltered would
        # let it block every legitimate bump until the number climbed past an abandoned one.
        self._seed_version("0.4.0", 116)
        run_git(self.box.path, "checkout", "-q", "--orphan", "abandoned")
        self.box.write("gradle.properties",
                       "skerry.versionName=9.0.0\nskerry.versionCode=9000\n")
        self.box.commit("abandoned lineage")
        head = run_git(self.box.path, "rev-parse", "HEAD").strip()
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        run_git(self.box.path, "update-ref", "refs/remotes/origin/main", head)
        # The ref has to actually exist, or the test passes on a baseline that was never consulted.
        self.assertEqual(
            run_git(self.box.path, "rev-parse", "--verify", "--quiet", "origin/main^{commit}").strip(),
            head)
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        self.assertEqual(self._findings("version-bump"), [])

    def test_a_baseline_whose_code_line_does_not_parse_is_blocked(self):
        # The regex is the only reader of that line. A reformat at the baseline — a trailing
        # comment, a quoted value — is not "no previous code", it is a comparison that cannot be
        # made, and this rule is not allowed to fall silent on one.
        run_git(self.box.path, "checkout", "-q", "main")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.0\nskerry.versionCode=116 # bumped for 0.4.0\n")
        self.box.commit("seed an unreadable code")
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        run_git(self.box.path, "merge", "-q", "main")
        self.box.write("gradle.properties",
                       "skerry.versionName=0.4.1\nskerry.versionCode=117\n")
        found = self._findings("version-bump")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].severity, checks.BLOCK)
        # Named once, not once per candidate ref: without dedup on the commit the same tree renders
        # as "at main, <sha>" and reads as two independent baselines failing.
        self.assertIn("gradle.properties at main carries no skerry.versionCode", found[0].message)

    def _seed_version(self, name: str, code: int | None, merge_back: bool = True) -> None:
        """Put a version on main, and by default on this branch's point as well.

        `merge_back=False` leaves main ahead: that is the sibling-branch case, where the code is
        already spent on main but still reads as an addition against the fork point.
        """
        body = f"skerry.versionName={name}\n" + (f"skerry.versionCode={code}\n" if code else "")
        run_git(self.box.path, "checkout", "-q", "main")
        self.box.write("gradle.properties", body)
        self.box.commit(f"seed {name}")
        run_git(self.box.path, "checkout", "-q", "refactor/checks")
        if merge_back:
            run_git(self.box.path, "merge", "-q", "main")

    def test_a_feature_without_tests_is_blocked(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        task = {"kind": "feature", "areas": ["shared"], "paths": [], "code_paths": []}
        found = [f for f in checks.run(self.cwd, task) if f.rule == "tests-present"]
        self.assertEqual(len(found), 1)

    def test_a_feature_with_tests_is_fine(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.box.write("shared/src/commonTest/kotlin/ATest.kt", "// covers it\n")
        task = {"kind": "feature", "areas": ["shared"], "paths": [], "code_paths": []}
        self.assertEqual([f for f in checks.run(self.cwd, task) if f.rule == "tests-present"], [])

    def test_a_harness_feature_owes_a_test_too(self):
        # The rule that gates every other change is the one place an untested rule costs most.
        self.box.branch("feat/gate-tweak")
        self.box.write("tools/harness/policy.py", "# a new rule\n")
        task = {"kind": "feature", "areas": ["harness"], "paths": [], "code_paths": []}
        found = [f for f in checks.run(self.cwd, task) if f.rule == "tests-present"]
        self.assertEqual(len(found), 1)

    def test_a_harness_feature_with_its_suite_touched_is_fine(self):
        self.box.branch("feat/gate-tweak")
        self.box.write("tools/harness/policy.py", "# a new rule\n")
        self.box.write("tools/harness/selftest.py", "# and the case that proves it\n")
        task = {"kind": "feature", "areas": ["harness"], "paths": [], "code_paths": []}
        self.assertEqual([f for f in checks.run(self.cwd, task) if f.rule == "tests-present"], [])

    def test_an_agent_policy_feature_owes_a_harness_test(self):
        task = {"kind": "feature", "areas": ["harness"], "paths": [], "code_paths": []}
        for path in state.AGENT_FILES:
            with self.subTest(path=path):
                self.box.write(path, "# changed policy\n")
                found = [f for f in checks.run(self.cwd, task) if f.rule == "tests-present"]
                self.assertEqual(len(found), 1)
                os.remove(os.path.join(self.cwd, path))

    def test_an_agent_policy_feature_with_a_harness_test_is_fine(self):
        self.box.write("AGENTS.md", "# changed policy\n")
        self.box.write("tools/harness/selftest.py", "# covers the policy\n")
        task = {"kind": "feature", "areas": ["harness"], "paths": [], "code_paths": []}
        self.assertEqual([f for f in checks.run(self.cwd, task) if f.rule == "tests-present"], [])

    def test_a_refactor_may_leave_tests_alone(self):
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(self._findings("tests-present"), [])

    def test_committed_lines_still_count_as_added(self):
        self.box.write("composeApp/src/commonMain/kotlin/S.kt", "fun a() { Text(\"x\") }\n")
        self.box.commit("wip")
        self.assertEqual(len(self._findings("design-primitives")), 1,
                         "the range is main...HEAD plus the worktree, not the worktree alone")

    def test_untouched_legacy_is_not_the_branch_debt(self):
        self.box.write("composeApp/src/commonMain/kotlin/Legacy.kt", "fun a() { Text(\"old\") }\n")
        self.box.commit("legacy on main")
        run_git(self.cwd, "checkout", "-q", "main")
        self.box.branch("refactor/other")
        self.box.write("shared/src/commonMain/kotlin/A.kt", "val a = 1\n")
        self.assertEqual(self._findings("design-primitives"), [],
                         "rules apply to what this branch adds, not to what it inherited")

    def test_i18n_gap_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        self.box.write(f"{base}/values/strings.xml",
                       '<resources><string name="a">A</string></resources>')
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><string name="a">А</string></resources>')
        self.box.write(f"{base}/values-zh/strings.xml", "<resources></resources>")
        self.box.write(f"{base}/values-tr/strings.xml",
                       '<resources><string name="a">A</string></resources>')
        self.box.write(f"{base}/values-de/strings.xml",
                       '<resources><string name="a">A</string></resources>')
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1)
        self.assertIn("values-zh", found[0].message)

    def test_i18n_gap_in_german_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-zh", "values-tr"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1)
        self.assertIn("values-de", found[0].message)

    def test_i18n_complete_is_clean(self):
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-zh", "values-tr", "values-de"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        self.assertEqual(self._findings("i18n-parity"), [])

    def test_i18n_gap_in_plurals_and_arrays_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        def both(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d files</item>' for c in categories)
            return (f'<resources><plurals name="p">{items}</plurals>'
                    '<string-array name="a"><item>Jan</item></string-array></resources>')
        self.box.write(f"{base}/values/strings.xml", both("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", both("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", "<resources></resources>")
        self.box.write(f"{base}/values-tr/strings.xml", both("one", "other"))
        self.box.write(f"{base}/values-de/strings.xml", both("one", "other"))
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 2, "a plural and an array ship in every language like a string")
        self.assertTrue(all("values-zh" in f.message for f in found))

    def test_a_plural_does_not_define_a_string_of_the_same_name(self):
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return f'<resources><plurals name="count">{items}</plurals></resources>'
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", body("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        self.box.write(f"{base}/values-tr/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-de/strings.xml", body("one", "other"))
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val t = stringResource(Res.string.count)\n")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "the namespaces are separate — a plural is not a string")
        self.assertIn("Res.string.count", found[0].message)

    def test_undefined_string_key_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-zh", "values-tr", "values-de"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val t = stringResource(Res.string.missing_key)\n")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1)
        self.assertIn("missing_key", found[0].message)


    def test_a_string_and_a_plural_of_one_name_are_two_keys(self):
        base = "composeApp/src/commonMain/composeResources"
        self.box.write(f"{base}/values/strings.xml",
                       '<resources><string name="a">A</string></resources>')
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><plurals name="a">'
                       '<item quantity="one">А</item><item quantity="few">А</item>'
                       '<item quantity="many">А</item><item quantity="other">А</item>'
                       '</plurals></resources>')
        for locale in ("values-zh", "values-tr", "values-de"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        messages = [f.message for f in self._findings("i18n-parity")]
        self.assertEqual(len(messages), 2, "a plural does not translate a string of the same name")
        self.assertTrue(any(m.startswith("`a` has no values-ru") for m in messages), messages)
        self.assertTrue(any(m.startswith("`plurals a` exists only in values-ru") for m in messages), messages)

    def test_a_plural_missing_a_category_is_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        full = ('<item quantity="one">A</item><item quantity="few">A</item>'
                '<item quantity="many">A</item><item quantity="other">A</item>')
        self.box.write(f"{base}/values/strings.xml",
                       f'<resources><plurals name="n">{full}</plurals></resources>')
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><plurals name="n"><item quantity="other">A</item></plurals></resources>')
        self.box.write(f"{base}/values-zh/strings.xml",
                       '<resources><plurals name="n"><item quantity="other">A</item></plurals></resources>')
        self.box.write(f"{base}/values-tr/strings.xml",
                       '<resources><plurals name="n"><item quantity="one">A</item>'
                       '<item quantity="other">A</item></plurals></resources>')
        self.box.write(f"{base}/values-de/strings.xml",
                       '<resources><plurals name="n"><item quantity="one">A</item>'
                       '<item quantity="other">A</item></plurals></resources>')
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "Chinese needs `other` alone; Russian needs one/few/many too")
        self.assertIn("one, few, many", found[0].message)

    def test_a_turkish_plural_needs_one_as_well_as_other(self):
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return f'<resources><plurals name="n">{items}</plurals></resources>'
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", body("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        self.box.write(f"{base}/values-tr/strings.xml", body("other"))
        self.box.write(f"{base}/values-de/strings.xml", body("one", "other"))
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "CLDR gives Turkish `one` — Chinese's lone `other` is not enough")
        self.assertIn("values-tr", found[0].message)

    def test_a_german_plural_needs_one_as_well_as_other(self):
        # German shares English's one/other split; the positive pin keeps a future edit from
        # dropping values-de out of the CLDR table while every other selftest stays green.
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return f'<resources><plurals name="n">{items}</plurals></resources>'
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", body("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        self.box.write(f"{base}/values-tr/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-de/strings.xml", body("other"))
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "German splits one/other like English — a lone `other` is not a translation")
        self.assertIn("values-de", found[0].message)

    def test_undefined_plural_and_array_keys_are_blocked(self):
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return (f'<resources><plurals name="here">{items}</plurals>'
                    '<string-array name="also"><item>Jan</item></string-array></resources>')
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml", body("one", "few", "many", "other"))
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        self.box.write(f"{base}/values-tr/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-de/strings.xml", body("one", "other"))
        self.box.write("composeApp/src/commonMain/kotlin/S.kt",
                       "val a = pluralStringResource(Res.plurals.here, 1)\n"
                       "val b = stringArrayResource(Res.array.also)\n"
                       "val c = pluralStringResource(Res.plurals.gone, 1)\n"
                       "val d = stringArrayResource(Res.array.vanished)\n")
        messages = [f.message for f in self._findings("i18n-parity")]
        self.assertEqual(len(messages), 2, "the two defined accessors resolve, the two stale ones do not")
        self.assertIn("`Res.plurals.gone` is used but defined nowhere.", messages)
        self.assertIn("`Res.array.vanished` is used but defined nowhere.", messages)

    def test_an_attribute_before_the_name_does_not_exempt_a_string(self):
        base = "composeApp/src/commonMain/composeResources"
        self.box.write(f"{base}/values/strings.xml",
                       '<resources><string translatable="false" name="a">A</string></resources>')
        for locale in ("values-ru", "values-zh", "values-tr", "values-de"):
            self.box.write(f"{base}/{locale}/strings.xml", "<resources></resources>")
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 4, "a key the pattern misses is silently exempt from parity")

    def test_an_unreadable_resource_file_says_so(self):
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-tr"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        os.makedirs(os.path.join(self.cwd, base, "values-zh/strings.xml"))
        found = self._findings("i18n-parity")
        warnings = [f for f in found if f.severity == checks.WARN]
        self.assertEqual(len(warnings), 1, "an incomplete sweep has to say it was incomplete")
        # Every locale is walked twice — once for names, once for plural categories. An uncached
        # read counted the same broken file once per walk and sent the reader hunting a second one.
        self.assertIn("1 resource file(s) could not be read", warnings[0].message)

    def test_a_resource_file_that_is_not_utf8_says_so(self):
        # The likeliest way a locale file becomes unreadable is an editor saving it in cp1251, and
        # that raises UnicodeDecodeError, not OSError — the warning built for it has to fire.
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-tr"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        path = os.path.join(self.cwd, base, "values-zh", "strings.xml")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write('<resources><string name="a">Файл</string></resources>'.encode("cp1251"))
        warnings = [f for f in self._findings("i18n-parity") if f.severity == checks.WARN]
        self.assertEqual(len(warnings), 1, "a file the sweep cannot decode is not a clean sweep")

    def test_a_source_file_that_cannot_be_read_says_so(self):
        # An unreadable source hides a `Res.` usage, not a translation — a different warning from
        # the locale one, and the two counters must not be folded together.
        base = "composeApp/src/commonMain/composeResources"
        for locale in ("values", "values-ru", "values-zh", "values-tr"):
            self.box.write(f"{base}/{locale}/strings.xml",
                           '<resources><string name="a">A</string></resources>')
        path = os.path.join(self.cwd, "composeApp/src/commonMain/kotlin/S.kt")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write("val a = Res.string.a // файл\n".encode("cp1251"))
        warnings = [f for f in self._findings("i18n-parity") if f.severity == checks.WARN]
        self.assertEqual(len(warnings), 1)
        self.assertIn("source file(s) could not be read", warnings[0].message)

    def test_a_translation_that_drops_a_placeholder_is_blocked(self):
        # Turkish reorders the sentence around the host name ("“%1$s” silinsin mi?"); a
        # translation that loses the placeholder passes every key check and draws no host at all.
        base = "composeApp/src/commonMain/composeResources"
        self.box.write(f"{base}/values/strings.xml",
                       '<resources><string name="a">Delete “%1$s”?</string>'
                       '<plurals name="p"><item quantity="one">%1$d file</item>'
                       '<item quantity="other">%1$d files</item></plurals></resources>')
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><string name="a">Удалить «%1$s»?</string>'
                       '<plurals name="p"><item quantity="one">%1$d файл</item>'
                       '<item quantity="few">%1$d файла</item><item quantity="many">%1$d файлов</item>'
                       '<item quantity="other">%1$d файла</item></plurals></resources>')
        self.box.write(f"{base}/values-zh/strings.xml",
                       '<resources><string name="a">删除“%1$s”？</string>'
                       '<plurals name="p"><item quantity="other">%1$d 个文件</item></plurals></resources>')
        self.box.write(f"{base}/values-tr/strings.xml",
                       '<resources><string name="a">Silinsin mi?</string>'
                       '<plurals name="p"><item quantity="one">%1$d dosya</item>'
                       '<item quantity="other">dosyalar</item></plurals></resources>')
        self.box.write(f"{base}/values-de/strings.xml",
                       '<resources><string name="a">„%1$s“ löschen?</string>'
                       '<plurals name="p"><item quantity="one">%1$d Datei</item>'
                       '<item quantity="other">%1$d Dateien</item></plurals></resources>')
        messages = [f.message for f in self._findings("i18n-parity")]
        self.assertEqual(len(messages), 2, messages)
        self.assertTrue(all("values-tr" in m and "%1$" in m for m in messages), messages)

    def test_an_empty_plural_form_is_not_a_form(self):
        base = "composeApp/src/commonMain/composeResources"
        def body(*categories: str) -> str:
            items = "".join(f'<item quantity="{c}">%1$d</item>' for c in categories)
            return f'<resources><plurals name="n">{items}</plurals></resources>'
        self.box.write(f"{base}/values/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-ru/strings.xml",
                       '<resources><plurals name="n">'
                       '<item quantity="one">%1$d файл</item><item quantity="few"></item>'
                       '<item quantity="many">%1$d файлов</item>'
                       '<item quantity="other">%1$d файла</item></plurals></resources>')
        self.box.write(f"{base}/values-zh/strings.xml", body("other"))
        self.box.write(f"{base}/values-tr/strings.xml", body("one", "other"))
        self.box.write(f"{base}/values-de/strings.xml", body("one", "other"))
        found = self._findings("i18n-parity")
        self.assertEqual(len(found), 1, "an item with no text draws a blank where the number goes")
        self.assertIn("few", found[0].message)


