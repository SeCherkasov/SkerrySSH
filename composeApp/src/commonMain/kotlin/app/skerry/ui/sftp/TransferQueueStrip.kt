package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.HLine
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
import app.skerry.ui.generated.resources.ftail_transfer_counter
import app.skerry.ui.generated.resources.sftp_meta_joined
import app.skerry.ui.generated.resources.sftp_queue_backlog
import app.skerry.ui.generated.resources.sftp_queue_cancel
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
 * waiting one is cancelled.
 */
@Composable
internal fun TransferQueueStrip(queue: List<TransferEntry>, mono: FontFamily, onDismiss: (Long) -> Unit) {
    // Above the early return, so the region survives the strip appearing and disappearing.
    StatusAnnouncer(transferQueueAnnouncement(queue))
    if (queue.isEmpty()) return
    HLine()
    Column(
        // Bounded and scrollable: the queue is as long as the user makes it (holding F5 keeps
        // submitting), and the strip is measured before the weighted panes above it — an unbounded
        // one would squeeze them and push the cancel buttons, the only way to hand a queued handle
        // back, off the window.
        Modifier
            .fillMaxWidth()
            .background(Skerry.colors.surface)
            .heightIn(max = QUEUE_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        queue.forEach { entry -> key(entry.id) { TransferQueueRow(entry, mono, onDismiss) } }
    }
}

/** About five rows: the whole finished history plus a running transfer and some of its backlog. */
private val QUEUE_MAX_HEIGHT = 116.dp

/** Name column of a queue row — wide enough for a release archive, fixed so the bars line up. */
private val QUEUE_NAME_WIDTH = 180.dp

/** How wide the trailing status text may grow before it starts to ellipsize. */
private val QUEUE_TAIL_MAX_WIDTH = 220.dp

@Composable
private fun TransferQueueRow(entry: TransferEntry, mono: FontFamily, onDismiss: (Long) -> Unit) {
    val done = entry.status == TransferStatus.Done
    val failed = entry.status as? TransferStatus.Failed
    val active = entry.status == TransferStatus.Active
    val waiting = entry.status == TransferStatus.Waiting
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val up = entry.direction == TransferDirection.Upload
        Sym(
            if (failed != null) "error" else if (up) "upload" else "download",
            size = 15.sp,
            color = when {
                failed != null -> Skerry.colors.sunset
                done -> Skerry.colors.moss
                // Dimmed until it is this row's turn: the strip is read at a glance, and only one
                // row can be moving bytes.
                waiting -> Skerry.colors.dim
                else -> Skerry.colors.teal
            },
        )
        val name = transferDisplayName(entry.name)
        val title = if (entry.fileCount > 1) {
            stringResource(Res.string.ftail_transfer_counter, name, entry.fileIndex, entry.fileCount)
        } else {
            name
        }
        Txt(
            title,
            color = if (active) Skerry.colors.textBright else Skerry.colors.dim,
            size = 11.5.sp,
            font = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(QUEUE_NAME_WIDTH),
        )
        val percent = transferPercent(entry.transferred, entry.total)
        MeterBar(
            if (done) 1f else (percent ?: 0) / 100f,
            when {
                failed != null -> Skerry.colors.sunset
                done -> Skerry.colors.moss
                else -> Skerry.colors.cyan
            },
            Modifier.weight(1f),
        )
        Txt(
            transferTailText(entry),
            color = when {
                failed != null -> Skerry.colors.sunset
                done -> Skerry.colors.moss
                else -> Skerry.colors.dim
            },
            size = 11.sp,
            font = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Bounded rather than weighted: the meter is what the row is about (the mockup gives
            // it nearly the whole width), but a long failure message must not push the meter and
            // the dismiss button out of the row either.
            modifier = Modifier.widthIn(max = QUEUE_TAIL_MAX_WIDTH),
        )
        // A finished row is the user's to clear and a waiting one theirs to cancel; a running one
        // has nothing to dismiss yet. The two say different things out loud: cancelling drops work
        // the user asked for, clearing drops a line of history.
        if (active) {
            Box(Modifier.size(22.dp))
        } else {
            // Named after the row it belongs to: the queue routinely holds several rows of the same
            // status, and a list of identical "Cancel" controls is not navigable without sight.
            val label = stringResource(if (waiting) Res.string.sftp_queue_cancel else Res.string.sftp_queue_clear, name)
            IconBtn("close", label = label, onClick = { onDismiss(entry.id) }, box = 22, icon = 14.sp)
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
private fun outcomeText(status: TransferStatus): String =
    (status as? TransferStatus.Failed)?.let { transferFailureText(it.failure) } ?: stringResource(Res.string.sftp_queue_done)
