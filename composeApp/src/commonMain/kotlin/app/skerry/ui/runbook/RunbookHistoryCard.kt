package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.runbook.RunbookRunOutcome
import app.skerry.shared.runbook.RunbookRunRecord
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_history
import app.skerry.ui.generated.resources.runbook_history_empty
import app.skerry.ui.generated.resources.runbook_history_failed_at
import app.skerry.ui.generated.resources.runbook_panel_progress
import app.skerry.ui.sftp.fileDateText
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** How many past runs the card lists; the store keeps more, the card shows the ones still relevant. */
private const val HISTORY_ROWS = 5

/**
 * Past runs of this runbook: when each started, how far it got, how long it took — and, for the ones
 * that failed, which step ended them. What ran and what it printed is deliberately absent (see
 * [RunbookRunRecord]); this card answers "did it work last time".
 */
@Composable
internal fun RunbookHistoryCard(records: List<RunbookRunRecord>, mono: FontFamily, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(11.dp)).background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.line, RoundedCornerShape(11.dp)).padding(14.dp),
    ) {
        Txt(
            stringResource(Res.string.runbook_history), color = Skerry.colors.text, size = 12.5.sp,
            weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp),
        )
        if (records.isEmpty()) {
            Txt(stringResource(Res.string.runbook_history_empty), color = Skerry.colors.faint, size = 11.5.sp)
            return@Column
        }
        records.take(HISTORY_ROWS).forEach { record ->
            key(record.id) { HistoryRow(record, mono) }
        }
    }
}

@Composable
private fun HistoryRow(record: RunbookRunRecord, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(
            fileDateText(record.startedAt / MILLIS_PER_SECOND),
            color = Skerry.colors.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f),
        )
        Txt(runOutcomeText(record), color = outcomeColor(record.outcome), size = 11.5.sp, font = mono)
    }
}

/** The right-hand half of a history row: what the run got through, or where it stopped. */
@Composable
private fun runOutcomeText(record: RunbookRunRecord): String {
    val failed = record.host.failedStep
    if (record.outcome == RunbookRunOutcome.FAILED && failed != null) {
        return stringResource(Res.string.runbook_history_failed_at, failed)
    }
    val progress = stringResource(Res.string.runbook_panel_progress, record.host.stepsDone, record.host.stepsTotal)
    return progress + " · " + runbookDurationText(record.durationMillis)
}

@Composable
private fun outcomeColor(outcome: RunbookRunOutcome): Color = when (outcome) {
    RunbookRunOutcome.DONE -> Skerry.colors.moss
    RunbookRunOutcome.DONE_WITH_FAILURES -> Skerry.colors.storm
    RunbookRunOutcome.FAILED -> Skerry.colors.sunset
    RunbookRunOutcome.STOPPED -> Skerry.colors.dim
}

private const val MILLIS_PER_SECOND = 1_000L
