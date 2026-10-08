package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.HLine
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.files.TransferEntry
import app.skerry.ui.files.TransferStatus
import app.skerry.ui.files.isFinished
import app.skerry.ui.files.transferDisplayName
import app.skerry.ui.files.transferFailureText
import app.skerry.ui.forward.humanRate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_queue_file_counter
import app.skerry.ui.generated.resources.sftp_queue_operation_bytes
import app.skerry.ui.generated.resources.sftp_meta_joined
import app.skerry.ui.generated.resources.sftp_queue_backlog
import app.skerry.ui.generated.resources.sftp_queue_cancel
import app.skerry.ui.generated.resources.sftp_cancel
import app.skerry.ui.generated.resources.sftp_queue_cancelled
import app.skerry.ui.generated.resources.sftp_queue_clear
import app.skerry.ui.generated.resources.sftp_queue_done
import app.skerry.ui.generated.resources.sftp_queue_progress
import app.skerry.ui.generated.resources.sftp_queue_state
import app.skerry.ui.generated.resources.sftp_queue_waiting
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Transfer queue under the panes: one row per operation — what is waiting for the channel, what is
 * moving now, and the last few that finished, so the outcome of a transfer is still readable after
 * it ends. Empty queue, no strip. [onDismiss] drops a row by its id: a finished one is cleared, a
 * waiting or active one is cancelled.
 */
@Composable
internal fun TransferQueueStrip(
    queue: List<TransferEntry>,
    mono: FontFamily,
    cancelModifier: Modifier = Modifier,
    onDismiss: (Long) -> Unit,
) {
    // Above the early return, so the region survives the strip appearing and disappearing.
    StatusAnnouncer(transferQueueAnnouncement(queue))
    if (queue.isEmpty()) return
    HLine()
    Column(
        Modifier.fillMaxWidth().background(Skerry.colors.surface).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Keep the running operation visible while browsing a long backlog.
        queue.firstOrNull { it.status == TransferStatus.Active }?.let {
            TransferQueueRow(it, mono, onDismiss, cancelModifier = cancelModifier)
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = QUEUE_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(queue.filter { it.status != TransferStatus.Active }, key = { it.id }) {
                TransferQueueRow(it, mono, onDismiss)
            }
        }
    }
}

/** A few backlog rows; active progress is pinned outside this viewport. */
private val QUEUE_MAX_HEIGHT = 116.dp

/** Shared queue row: a flexible name, a separately measured file counter and byte telemetry. */
@Composable
internal fun TransferQueueRow(
    entry: TransferEntry,
    mono: FontFamily,
    onDismiss: (Long) -> Unit,
    touch: Boolean = false,
    cancelModifier: Modifier = Modifier,
) {
    val done = entry.status == TransferStatus.Done
    val cancelled = entry.status == TransferStatus.Cancelled
    val failed = entry.status as? TransferStatus.Failed
    val active = entry.status == TransferStatus.Active
    val waiting = entry.status == TransferStatus.Waiting
    val name = transferDisplayName(entry.name)
    val tint = when {
        failed != null -> Skerry.colors.sunset
        done -> Skerry.colors.moss
        active -> Skerry.colors.cyan
        else -> Skerry.colors.dim
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(if (failed != null) "error" else if (entry.direction == TransferDirection.Upload) "upload" else "download",
                size = 15.sp, color = tint)
            Txt(name, color = if (active) Skerry.colors.textBright else Skerry.colors.dim,
                size = if (touch) 12.5.sp else 11.5.sp, font = mono, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (entry.fileCount > 1) {
                Txt(stringResource(Res.string.sftp_queue_file_counter, entry.fileIndex, entry.fileCount),
                    color = Skerry.colors.dim, size = 11.sp, font = mono, maxLines = 1)
            }
            if (waiting || done || cancelled) {
                Txt(transferTailText(entry), color = tint, size = 11.sp, maxLines = 1)
            }
            if (active) {
                val label = stringResource(Res.string.sftp_queue_cancel, name)
                ChipButton(stringResource(Res.string.sftp_cancel), Skerry.colors.dim,
                    onClick = { onDismiss(entry.id) },
                    modifier = cancelModifier.heightIn(min = if (touch) 48.dp else 28.dp)
                        .semantics { contentDescription = label })
            } else {
                val label = stringResource(if (waiting) Res.string.sftp_queue_cancel else Res.string.sftp_queue_clear, name)
                IconBtn("close", label = label, onClick = { onDismiss(entry.id) }, box = if (touch) 48 else 22, icon = 14.sp)
            }
        }
        if (active || failed != null) {
            Txt(transferTailText(entry), color = tint, size = 11.sp, font = mono,
                maxLines = if (failed != null) 3 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
        }
        if (active && entry.fileCount > 1) {
            Txt(stringResource(Res.string.sftp_queue_operation_bytes, humanSize(entry.bytesDone)),
                color = Skerry.colors.dim, size = 11.sp, font = mono,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        val percent = transferPercent(entry.transferred, entry.total)
        // An unknown total has byte telemetry but no fabricated percentage or empty progress bar.
        if (active && percent != null) {
            MeterBar(percent / 100f, Skerry.colors.cyan, Modifier.fillMaxWidth().padding(top = 5.dp))
        }
    }
}

/**
 * Right-hand text of a queue row: the failure, "done", "waiting", or percent · bytes · speed while
 * running.
 */
@Composable
private fun transferTailText(entry: TransferEntry): String {
    (entry.status as? TransferStatus.Failed)?.let { return transferFailureText(it.failure) }
    if (entry.status == TransferStatus.Done) return stringResource(Res.string.sftp_queue_done)
    if (entry.status == TransferStatus.Cancelled) return stringResource(Res.string.sftp_queue_cancelled)
    if (entry.status == TransferStatus.Waiting) return stringResource(Res.string.sftp_queue_waiting)
    // Throughput goes through the app's one rate formatter (the tunnel table, the host monitor and
    // the mobile terminal header all read it), so the same speed never gets two spellings.
    val speedText = transferSpeed(entry.bytesDone, entry.elapsedMillis)?.let { humanRate(it) }
    val percent = transferPercent(entry.transferred, entry.total)
    // Without a reported size there is no percentage and no "of": what is left is how much has
    // moved so far, and how fast.
    val progress = if (percent != null) {
        stringResource(Res.string.sftp_queue_progress, percent, humanSize(entry.transferred), humanSize(entry.total))
    } else {
        humanSize(entry.transferred)
    }
    return if (speedText != null) stringResource(Res.string.sftp_meta_joined, progress, speedText) else progress
}

/**
 * What the queue is doing, for a screen reader: how the last operation ended, and how much is still
 * waiting for the channel. Both are otherwise silent — a picked upload that has to queue draws a row
 * and says nothing, and an operation abandoned when the session closed only stops being mentioned.
 *
 * It is the state, not its telemetry: progress and speed are deliberately left out, or every
 * callback would talk over the user.
 */
@Composable
internal fun transferQueueAnnouncement(queue: List<TransferEntry>): String {
    val last = queue.lastOrNull { it.status.isFinished }
    // Success is announced too, not only failure: on the desktop a finished row stays on the strip,
    // so a transfer that went through is a visible outcome, and one that is only visible is the gap
    // a live region exists to close.
    val outcome = last?.let { stringResource(Res.string.sftp_queue_state, transferDisplayName(it.name), outcomeText(it.status)) }
    val count = queue.count { it.status == TransferStatus.Waiting }
    val backlog = if (count > 0) stringResource(Res.string.sftp_queue_backlog, count) else ""
    // Both clauses, always: an operation that failed while others were still queued would otherwise
    // never be spoken at all. The cost is that advancing the queue restates the last outcome — and
    // every one of those restatements does follow a real change of state.
    return listOfNotNull(outcome, backlog).filter { it.isNotEmpty() }.joinToString(". ")
}

/** How a finished entry ended, in one word or one sentence. */
@Composable
private fun outcomeText(status: TransferStatus): String = when (status) {
    is TransferStatus.Failed -> transferFailureText(status.failure)
    TransferStatus.Cancelled -> stringResource(Res.string.sftp_queue_cancelled)
    else -> stringResource(Res.string.sftp_queue_done)
}
