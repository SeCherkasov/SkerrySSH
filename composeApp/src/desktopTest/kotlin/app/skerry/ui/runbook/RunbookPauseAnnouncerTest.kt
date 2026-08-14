package app.skerry.ui.runbook

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.ui.desktop.runForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * The announcer's message across the run's own pauses: it must change on every pause — a repeat of
 * the same string is deduplicated into silence by the live region, which is exactly why the step
 * number is part of it (two consecutive interactive steps would otherwise announce once).
 */
@OptIn(ExperimentalTestApi::class)
class RunbookPauseAnnouncerTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `each pause announces itself with its step number`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = RunbookRunner(
            scope = scope,
            newId = { "run" },
            environment = {
                SnippetRunEnvironment(
                    moment = SnippetMoment(2026, 8, 14, 12, 0, 0, epochSeconds = 1_786_000_000L),
                    newUuid = { "u" },
                    randomChars = { n, _ -> "r".repeat(n) },
                )
            },
        )
        val target = RunbookTarget(
            sessionId = "tab-1",
            send = {},
            expectStep = { _, _ -> },
            takeMark = { null },
            outputVersion = { 0L },
        )
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "mc", confirm = false, interactive = true),
                RunbookStep.Command(id = "s3", command = "reboot", confirm = true),
            ),
        )
        try {
            runForm({ RunbookPauseAnnouncer(runner) }) {
                // Composed before the run exists: the first pause is a change, not an insertion.
                onNode(polite).assert(hasContentDescription(""))

                runner.requestStart(runbook, target)
                runner.confirmStart { "" }
                waitForIdle()
                onNode(polite).assert(hasContentDescription("Step 1", substring = true))

                runner.completeStep()
                waitForIdle()
                // The next interactive pause is a different string — not deduplicated into silence.
                onNode(polite).assert(hasContentDescription("Step 2", substring = true))

                runner.completeStep()
                waitForIdle()
                // A confirmation pause announces too, with its own wording.
                onNode(polite).assert(hasContentDescription("Step 3", substring = true))

                runner.stop()
                waitForIdle()
                onNode(polite).assert(hasContentDescription(""))
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }
}
