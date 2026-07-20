package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_record_empty
import app.skerry.ui.generated.resources.term_record_saved
import app.skerry.ui.generated.resources.term_record_truncated
import org.jetbrains.compose.resources.stringResource

/**
 * Localized notice for a finished recording. Built here rather than in the button so the outcome
 * stays a plain enum and the text isn't baked into one language at the call site.
 */
@Composable
fun recordingOutcomeMessage(outcome: RecordingOutcome): String = when (outcome) {
    RecordingOutcome.Saved -> stringResource(Res.string.term_record_saved)
    RecordingOutcome.SavedTruncated -> stringResource(Res.string.term_record_truncated)
    RecordingOutcome.Empty -> stringResource(Res.string.term_record_empty)
    // Never shown (see RecordingOutcome.worthReporting), but the mapping stays total.
    RecordingOutcome.Cancelled -> ""
}
