package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailedCommandSnapshotTest {
    private fun row(text: String, wrapped: Boolean = false): List<TermCell> =
        TermSnapshotRow(text.map { TermCell(it) }, wrapped)

    private fun mark(
        inputRow: Int = 1,
        inputCol: Int = 2,
        outputRow: Int = 2,
        outputCol: Int = 0,
        endRow: Int = 3,
        endCol: Int = 0,
    ) = ShellCommandMark(1, 0, inputRow, inputCol, outputRow, outputCol, endRow, endCol, 1)

    @Test fun capturesOnlyTheSelectedCommandAndItsOutput() {
        val screen = listOf(row("previous secret"), row("$ false"), row("failure"), row("$ next secret"))
        val snapshot = assertNotNull(failedCommandSnapshot(screen, mark()))
        assertEquals("false", snapshot.command)
        assertEquals("failure", snapshot.output)
        assertEquals(1, snapshot.exitCode)
        assertFalse(snapshot.outputTruncated)
    }

    @Test fun columnsExcludePromptAndFollowingCommandOnTheSameRow() {
        val screen = listOf(row("older"), row("$ badERROR$ following"))
        val snapshot = assertNotNull(failedCommandSnapshot(screen, mark(outputRow = 1, outputCol = 5, endRow = 1, endCol = 10)))
        assertEquals("bad", snapshot.command)
        assertEquals("ERROR", snapshot.output)
    }

    @Test fun emptyOutputIsNotReplacedWithOtherScrollback() {
        val screen = listOf(row("foreign"), row("$ false"), row("$ next"))
        val snapshot = assertNotNull(failedCommandSnapshot(screen, mark(endRow = 2)))
        assertEquals("false", snapshot.command)
        assertEquals("", snapshot.output)
    }

    @Test fun missingInvalidRunningAndSuccessfulMarksAreUnavailable() {
        val screen = listOf(row("older"), row("$ false"), row("error"), row("next"))
        val base = mark()
        listOf(
            base.copy(inputRow = null), base.copy(inputCol = null), base.copy(outputRow = null),
            base.copy(endRow = null), base.copy(endCol = null), base.copy(exitCode = null),
            base.copy(exitCode = 0), base.copy(inputRow = -1), base.copy(endRow = 9),
            base.copy(inputCol = 99), base.copy(outputRow = 0), base.copy(endRow = 1),
            base.copy(promptRow = -1),
        ).forEach { assertNull(failedCommandSnapshot(screen, it), "$it") }
    }

    @Test fun softWrapsAreJoinedAndConcealedCellsNeverBecomeOutboundText() {
        val screen = listOf(
            row("older"), row("$ echo ", wrapped = true), row("test"),
            row("abc", wrapped = true),
            listOf(TermCell("hidden-value", TermStyle(hidden = true)), TermCell('!')),
            row("next"),
        )
        val snapshot = assertNotNull(failedCommandSnapshot(screen, mark(outputRow = 3, endRow = 5)))
        assertEquals("echo test", snapshot.command)
        assertEquals("abc•!", snapshot.output)
    }

    @Test fun retainsBoundedTailAndReportsTruncation() {
        val screen = listOf(row("older"), row("$ bad")) + (1..200).map { row("error $it") } + listOf(row("next"))
        val snapshot = assertNotNull(failedCommandSnapshot(screen, mark(endRow = 202)))
        assertTrue(snapshot.outputTruncated)
        assertTrue(snapshot.output.endsWith("error 200"))
        assertFalse(snapshot.output.contains("error 1\n"))
        assertTrue(snapshot.output.lines().size <= FAILED_COMMAND_OUTPUT_ROWS)
        assertTrue(snapshot.output.length <= FAILED_COMMAND_OUTPUT_CHARS)
    }

    @Test fun oversizedCommandIsUnavailableRatherThanLosingItsBeginning() {
        val screen = listOf(row("older"), row("$ " + "x".repeat(FAILED_COMMAND_INPUT_CHARS + 1)), row("error"), row("next"))
        assertNull(failedCommandSnapshot(screen, mark()))
    }

    @Test fun tailDoesNotStartMidLogicalLineOrSplitASecretLabel() {
        val screen = listOf(row("older"), row("$ bad"), row("PASSWORD=", wrapped = true)) +
            (1..150).map { row("short-value", wrapped = it < 150) } + listOf(row("last error"), row("next"))
        val snapshot = assertNotNull(failedCommandSnapshot(screen, mark(endRow = screen.lastIndex)))
        assertEquals("last error", snapshot.output)
        assertTrue(snapshot.outputTruncated)
    }

    @Test fun tailReadDoesNotWalkTheWholeScrollback() {
        var reads = 0
        val screen = object : AbstractList<List<TermCell>>() {
            override val size = 100_004
            override fun get(index: Int): List<TermCell> {
                reads++
                return when (index) {
                    1 -> row("$ bad")
                    size - 1 -> row("next")
                    else -> row("error")
                }
            }
        }
        assertNotNull(failedCommandSnapshot(screen, mark(endRow = screen.lastIndex)))
        assertTrue(reads < 400, "Read $reads rows for a bounded tail")
    }

    @Test fun emulatorPendingWrapPreservesExclusiveCommandAndOutputBounds() {
        val terminal = TerminalEmulator(cols = 8, rows = 10)
        terminal.feed(("\u001b]133;A\u0007$ \u001b]133;B\u0007abcdef\u001b]133;C\u0007" +
            "12345678\u001b]133;D;1\u0007").encodeToByteArray())
        val snapshot = assertNotNull(failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().single()))
        assertEquals("abcdef", snapshot.command)
        assertEquals("12345678", snapshot.output)
        terminal.resize(5, 10)
        assertEquals(snapshot, failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().single()))
    }

    @Test fun terminalPaddingDoesNotConsumeTheOutputTextBudget() {
        val terminal = TerminalEmulator(cols = 160, rows = 110)
        val output = (1..100).joinToString("\r\n") { "error $it" }
        terminal.feed(("\u001b]133;A\u0007$ \u001b]133;B\u0007false\r\n\u001b]133;C\u0007" +
            output + "\r\n\u001b]133;D;1\u0007").encodeToByteArray())
        val snapshot = assertNotNull(failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().single()))
        assertEquals(output.replace("\r\n", "\n"), snapshot.output)
        assertFalse(snapshot.outputTruncated)
    }

    @Test fun completedFailureKeepsItsOwnTextAfterLaterCursorAddressedOutput() {
        val terminal = TerminalEmulator(cols = 40, rows = 10)
        terminal.feed(("\u001b]133;A\u0007$ \u001b]133;B\u0007false\r\n\u001b]133;C\u0007failure\r\n" +
            "\u001b]133;D;1\u0007\u001b]133;A\u0007$ next").encodeToByteArray())
        val original = assertNotNull(failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().first()))
        terminal.feed("\u001b[2;1Hforeign-sensitive-text\u001b[K".encodeToByteArray())
        assertEquals(original, failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().first()))
        terminal.resize(20, 10)
        assertEquals(original, failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().first()))
    }

    @Test fun completedFailureWithUnavailableCaptureNeverReadsReplacementCells() {
        val terminal = TerminalEmulator(cols = 80, rows = 40)
        terminal.feed(("\u001b]133;A\u0007$ \u001b]133;B\u0007" + "x".repeat(FAILED_COMMAND_INPUT_CHARS + 1) +
            "\r\n\u001b]133;C\u0007failure\r\n\u001b]133;D;1\u0007").encodeToByteArray())
        val mark = terminal.shellCommandMarks().single()
        assertNull(failedCommandSnapshot(terminal.lines, mark))
        val replacements = List(terminal.lines.size) { row("replacement".padEnd(80)) }
        assertNull(failedCommandSnapshot(replacements, mark))
    }

    @Test fun emulatorReflowPreservesTheSelectedCommandBoundaries() {
        val terminal = TerminalEmulator(cols = 16, rows = 10)
        terminal.feed("\u001b]133;A\u0007$ \u001b]133;B\u0007missing-command\r\n\u001b]133;C\u0007not found\r\n\u001b]133;D;127\u0007\u001b]133;A\u0007$ next".encodeToByteArray())
        val before = assertNotNull(failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().first()))
        terminal.resize(9, 10)
        val after = assertNotNull(failedCommandSnapshot(terminal.lines, terminal.shellCommandMarks().first()))
        assertEquals(before, after)
    }
}
