package app.skerry.ui.snippet

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.Snippet
import app.skerry.ui.teams.ShareItem
import app.skerry.ui.teams.SharePickerDialog
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.ui.design.MAX_NOTE_CHARS
import app.skerry.ui.design.boundedVisibleText
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.allText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededSnippets
import app.skerry.ui.mobile.MobileSnippetCard
import app.skerry.ui.terminal.SnippetPalette
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.runbook.RunbookEntry
import app.skerry.ui.runbook.RunbookRunCard
import app.skerry.ui.runbook.RunbookRunner
import app.skerry.ui.runbook.RunbookStartDialog
import app.skerry.ui.runbook.RunbookTarget
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.command_clipped_partial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.compose.ui.test.onNodeWithContentDescription
import app.skerry.ui.generated.resources.lib_snippets_field_notes
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.app.DesktopView
import app.skerry.ui.desktop.clickIconWhenEnabled
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.runbook.RunbookDraft
import app.skerry.ui.generated.resources.runbook_toolbar_tip
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isRoot
import app.skerry.ui.generated.resources.runbook_run_title
import app.skerry.ui.generated.resources.runbook_run

/**
 * A snippet and a runbook are Teams share types: their label, command and description reach this
 * client exactly as their author typed them. What runs is always stripped of the reordering
 * characters, so a surface that draws them raw shows one line and executes another — the Trojan
 * Source shape, on the screens that carry a Run button. Every surface that quotes that text is
 * pinned here, because each of them writes its own `Txt` and nothing else keeps them in step.
 */
@OptIn(ExperimentalTestApi::class)
class UntrustedSnippetTextTest {

    // Escapes, not the raw glyphs: an invisible character in source is unreviewable. RLO reverses
    // the tail of the line, so what is drawn reads as a comment and what runs is the command.
    private val label = "Rollout\u202Etuollor"
    private val command = "echo ok \u202E# rm -rf /"
    private val note = "safe to re-run\u202Enur-er ot efasnu"
    private val drawnNote = sanitizeServerText(note, MAX_NOTE_CHARS, allowNewlines = true)

    // Every peer-authored field of the record, not just the two the row is named after.
    private val snippet = Snippet(
        id = "s1",
        label = label,
        command = command,
        tags = listOf("pro\u202Ed"),
        shortcut = "Ctrl+\u202EK",
        notes = note,
    )

    @Test
    fun `the library row draws neither the label nor the command raw`() {
        runForm({ SnippetListRow(SnippetEntry(snippet), selected = false) {} }) {
            assertNothingReordered()
            // Positive too: the row spells the character out rather than dropping it, so the line
            // drawn is the line that runs — a filter that deleted it would pass the check above.
            onNodeWithText(boundedVisibleText(command), useUnmergedTree = true).assertExists()
            onNodeWithText(untrustedLabel(label), useUnmergedTree = true).assertExists()
            assertNoteDrawn()
        }
    }

    @Test
    fun `the run panel draws neither the label nor the command raw`() {
        runForm({
            SnippetRunPanel(
                entry = SnippetEntry(snippet),
                targets = emptyList(),
                activeTargetId = null,
                mono = FontFamily.Monospace,
                onRun = { _, _ -> true },
                onCopy = {}, onEdit = {}, onDelete = {},
            )
        }) {
            assertNothingReordered()
            onNodeWithText(boundedVisibleText(command), useUnmergedTree = true).assertExists()
            assertNoteDrawn()
        }
    }

    @Test
    fun `the terminal palette draws neither the label nor the command raw`() {
        val manager = seededSnippets().apply {
            save(SnippetDraft(label = label, command = command, shortcut = "Ctrl+\u202EK", notes = snippet.notes))
        }
        runForm({ SnippetPalette(manager) {} }) {
            assertNothingReordered()
            onNodeWithText(boundedVisibleText(command), useUnmergedTree = true).assertExists()
            assertNoteDrawn()
        }
    }

    @Test
    fun `the phone's snippet card draws neither the label nor the command raw`() {
        runForm({ MobileSnippetCard(snippet) {} }) {
            assertNothingReordered()
            onNodeWithText(boundedVisibleText(command), useUnmergedTree = true).assertExists()
            assertNoteDrawn()
        }
    }

    @Test
    fun `the run confirmation draws the snippet label filtered`() {
        val manager = seededSnippets()
        manager.save(SnippetDraft(label = label, command = "echo \${{host}}"))
        manager.run(manager.snippets.first().id) { _, _ -> }
        runForm({ SnippetRunDialog(manager) }) {
            assertNothingReordered()
            onNodeWithText(untrustedLabel(label), useUnmergedTree = true).assertExists()
        }
    }

    /**
     * Same rule as the runbook's, on the dialog that sends a snippet: assemble keeps the control
     * bytes as the author's own text, and the quote is what makes them visible.
     */
    @Test
    fun `the run confirmation spells out a control byte in the line it will send`() {
        val manager = seededSnippets()
        manager.save(SnippetDraft(label = "Beep", command = "echo ok\u0007 \${{host}}"))
        manager.run(manager.snippets.first().id) { _, _ -> }
        runForm({ SnippetRunDialog(manager) }) {
            val drawn = drawnText()
            assertTrue(
                drawn.any { it.contains("<U+0007>") },
                "the control byte is spelled out in the previewed line, was $drawn",
            )
        }
    }

    @Test
    fun `the runbook start dialog draws the label and the description filtered`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = runbookRunner(scope)
        val runbook = Runbook(
            id = "rb",
            label = label,
            description = command,
            // The step line is what the user approves: it carries the payload too.
            steps = listOf(RunbookStep.Command(id = "s1", command = command, confirm = false)),
        )
        try {
            runner.requestStart(runbook, silentTarget())
            runForm({ RunbookStartDialog(runner) }) {
                assertNothingReordered()
                // Nothing in a variable-free runbook takes focus, so the scrim holds it: named, or
                // the confirmation that lists every command about to run opens in silence.
                onNodeWithContentDescription(
                    string(Res.string.runbook_run_title) + ": " + untrustedLabel(label),
                ).assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    /**
     * The step line of the start dialog is the one the runner will send. Its literal text is already
     * stripped of the reordering characters by the template engine, but the control bytes it keeps
     * as the author's own reach the screen — a step that ends in a BEL draws as one that does not.
     */
    @Test
    fun `the runbook start dialog spells out a control byte in the line it will run`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = runbookRunner(scope)
        val runbook = Runbook(
            id = "rb-ctl",
            label = "Deploy",
            steps = listOf(RunbookStep.Command(id = "s1", command = "echo ok\u0007 rm -rf /", confirm = false)),
        )
        try {
            runner.requestStart(runbook, silentTarget())
            runForm({ RunbookStartDialog(runner) }) {
                val drawn = drawnText()
                assertTrue(
                    drawn.any { it.contains("<U+0007>") },
                    "the control byte is spelled out where the user reads the line, was $drawn",
                )
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    private fun runbookRunner(scope: CoroutineScope) = RunbookRunner(
        scope = scope,
        newId = { "run" },
        environment = {
            SnippetRunEnvironment(
                moment = SnippetMoment(2026, 8, 15, 12, 0, 0, epochSeconds = 1_786_000_000L),
                newUuid = { "u" },
                randomChars = { n, _ -> "r".repeat(n) },
            )
        },
    )

    /** A target that accepts the run and sends nowhere: the dialog is what these tests read. */
    private fun silentTarget() = RunbookTarget(
        sessionId = "tab-1",
        send = { _, _ -> },
        expectStep = { _, _ -> },
        takeMark = { null },
        outputVersion = { 0L },
    )

    /** The desktop card lists a shared runbook's steps beside its own Run button. */
    @Test
    fun `the runbook card draws neither the step title nor its command raw`() {
        val runbook = Runbook(
            id = "rb-card",
            label = label,
            steps = listOf(RunbookStep.Command(id = "s1", title = label, command = command, confirm = false)),
        )
        runForm({ RunbookRunCard(RunbookEntry(runbook), DesktopDesignState(), {}, {}) }) {
            assertNothingReordered()
            onNodeWithText(boundedVisibleText(command), useUnmergedTree = true).assertExists()
        }
    }

    /** The share picker names records the local library holds, some of them written by a peer. */
    @Test
    fun `the share picker draws neither the label nor the detail raw`() {
        val items = listOf(ShareItem(id = "s1", label = label, detail = command))
        runForm({ SharePickerDialog(title = "Share", items = items, emptyText = "", onPick = {}, onDismiss = {}) }) {
            assertNothingReordered()
            onNodeWithText(untrustedLabel(label), useUnmergedTree = true).assertExists()
        }
    }

    /**
     * A command longer than the block draws says so, on the surface that sends it: cut in silence,
     * the confirmed line and the line the user read are not the same line.
     */
    @Test
    fun `the run confirmation says when it shows only part of the line`() {
        val manager = seededSnippets()
        manager.save(SnippetDraft(label = "Long", command = "echo \${{host}} " + "x".repeat(4_000)))
        manager.run(manager.snippets.first().id) { _, _ -> }
        runForm({ SnippetRunDialog(manager) }) {
            onNodeWithText(string(Res.string.command_clipped_partial), substring = true, useUnmergedTree = true)
                .assertExists()
        }
    }

    /**
     * The preview is rebuilt on every keystroke in a parameter field and the notice carries the
     * character count, so a live region here reads a fresh line out per character typed. The amber
     * line stays; it is the announcement that has to be silent, as it already is on the runbook
     * dialog's per-step notice.
     */
    @Test
    fun `the clipped notice does not read itself out on every keystroke`() {
        val manager = seededSnippets()
        manager.save(SnippetDraft(label = "Long", command = "echo \${{host}} " + "x".repeat(4_000)))
        manager.run(manager.snippets.first().id) { _, _ -> }
        runForm({ SnippetRunDialog(manager) }) {
            val notice = string(Res.string.command_clipped_partial)
            val announced = onRoot(useUnmergedTree = true).fetchSemanticsNode().liveRegionText()
            assertTrue(
                announced.none { it.contains(notice) },
                "the notice is announced from a live region that changes per keystroke, was $announced",
            )
        }
    }

    /**
     * The gate diverts commands with no variables, so the dialog it opens has no field to autofocus
     * and focus falls to the scrim — a full-screen box with no name, which opens the dialog in
     * silence for anyone reading it aloud. The scrim carries the dialog's name in that case, and the
     * clipped notice is announced here, where nothing can change the preview under it.
     */
    @Test
    fun `a confirmation with nothing to fill in says what it is`() {
        val manager = seededSnippets()
        manager.save(SnippetDraft(label = "Long", command = "echo " + "x".repeat(4_000)))
        manager.run(manager.snippets.first().id, oneTap = true) { _, _ -> }
        runForm({ SnippetRunDialog(manager) }) {
            onNodeWithContentDescription(string(Res.string.lib_snippets_run_title) + ": Long").assertExists()
            val announced = onRoot(useUnmergedTree = true).fetchSemanticsNode().liveRegionText()
            assertTrue(
                announced.any { it.contains(string(Res.string.command_clipped_partial)) },
                "the dialog never says it is showing part of the line, was $announced",
            )
        }
    }

    /**
     * The desktop runbook screens draw a label and a step line that arrived with the record: the
     * library row, the terminal palette, and — behind them — the same filters the run panel uses.
     * Composed over the real shell rather than one composable at a time, because each of these
     * screens writes its own row and only the record they read is shared.
     */
    @Test
    fun `the desktop runbook screens draw neither the label nor the step raw`() =
        runDesktopShell(withSessions = true) { shell ->
            shell.runbooks.save(
                RunbookDraft(
                    label = label,
                    description = command,
                    steps = listOf(RunbookStep.Command(id = "s1", title = label, command = command)),
                ),
            )
            shell.state.showView(DesktopView.Runbooks)
            waitForIdle()
            assertNothingReordered()

            shell.state.showView(DesktopView.Terminal)
            waitForIdle()
            clickIconWhenEnabled(string(Res.string.runbook_toolbar_tip), shell)
            waitForIdle()
            assertNothingReordered()

            // And the screen the confirmation hands over to: the run itself, where the same title
            // and line are drawn again by a second hand-written row.
            onNodeWithText(untrustedLabel(label)).performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.runbook_run)).performClick()
            waitUntil("the run starts", timeoutMillis = 10_000) { shell.runner.run != null }
            waitForIdle()
            assertNothingReordered()
            // The positive half: dropping the override would pass the check above too, and the rule
            // on a run surface is that the line is spelled out, not quietly shortened.
            onNodeWithText(boundedVisibleText(command), substring = true, useUnmergedTree = true).assertExists()
        }

    /**
     * The confirmation lists what a runbook will do, and the note above that list is the author's
     * own. Cut to fit and shown in silence, the note the user reads is not the note that was
     * written — the same rule the command quote follows one line below it.
     */
    @Test
    fun `the runbook confirmation says when the description was cut`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = runbookRunner(scope)
        val runbook = Runbook(
            id = "rb",
            label = "Rollout",
            description = "note ".repeat(200),
            steps = listOf(RunbookStep.Command(id = "s1", command = "uptime", confirm = false)),
        )
        try {
            runner.requestStart(runbook, silentTarget())
            runForm({ RunbookStartDialog(runner) }) {
                onNodeWithText(string(Res.string.command_clipped_partial), substring = true, useUnmergedTree = true)
                    .assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    /**
     * The note is drawn, filtered, and named — on the four surfaces that show it without a pointer.
     * The negative check above passes just as well when the note was dropped altogether, and the note
     * is the whole point on two of them: the palette row runs its command on one click, and the
     * library row can be in the list because the search matched a note and nothing else.
     */
    private fun ComposeUiTest.assertNoteDrawn() {
        onNodeWithContentDescription(
            string(Res.string.lib_snippets_field_notes) + ", " + drawnNote,
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun ComposeUiTest.assertNothingReordered() {
        // Every root, not the first: a palette is a Popup, and a popup is a root of its own.
        val drawn = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.allText() }
        assertTrue(drawn.isNotEmpty(), "the surface drew nothing")
        assertTrue(
            drawn.none { text -> text.hasFormatCodePoint() },
            "a reordering character reached the screen, was $drawn",
        )
    }

    /**
     * By code point, not by char: an astral formatting character — a tag block one, say — is a
     * surrogate pair, and both halves report SURROGATE, so a per-char check would never see it.
     */
    private fun String.hasFormatCodePoint(): Boolean =
        codePoints().anyMatch { Character.getType(it) == Character.FORMAT.toInt() }

    /** What a screen reader would be read out unprompted — every live region's own text. */
    private fun SemanticsNode.liveRegionText(): List<String> {
        val own = config.getOrNull(SemanticsProperties.LiveRegion)
            ?.let { config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .orEmpty()
        return own + children.flatMap { it.liveRegionText() }
    }

}
