package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.files.TransferEntry
import app.skerry.ui.files.TransferStatus
import app.skerry.ui.files.fileDisplayName
import app.skerry.ui.files.transferFailureText
import app.skerry.ui.forward.humanRate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_close
import app.skerry.ui.generated.resources.ftail_file_fallback
import app.skerry.ui.generated.resources.ftail_transfer_counter
import app.skerry.ui.generated.resources.sftp_meta_joined
import app.skerry.ui.generated.resources.sftp_queue_done
import app.skerry.ui.generated.resources.sftp_queue_progress
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Transfer queue under the panes: one row per operation — what is moving now, and the last few
 * that finished, so the outcome of a transfer is still readable after it ends. Empty queue, no
 * strip. [onDismiss] drops a finished row by its id.
 */
@Composable
internal fun TransferQueueStrip(queue: List<TransferEntry>, mono: FontFamily, onDismiss: (Long) -> Unit) {
    if (queue.isEmpty()) return
    HLine()
    Column(
        Modifier.fillMaxWidth().background(Skerry.colors.surface).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        queue.forEach { entry -> key(entry.id) { TransferQueueRow(entry, mono, onDismiss) } }
    }
}

/** Name column of a queue row — wide enough for a release archive, fixed so the bars line up. */
private val QUEUE_NAME_WIDTH = 180.dp

/** How wide the trailing status text may grow before it starts to ellipsize. */
private val QUEUE_TAIL_MAX_WIDTH = 220.dp

@Composable
private fun TransferQueueRow(entry: TransferEntry, mono: FontFamily, onDismiss: (Long) -> Unit) {
    val done = entry.status == TransferStatus.Done
    val failed = entry.status as? TransferStatus.Failed
    val active = entry.status == TransferStatus.Active
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
                else -> Skerry.colors.teal
            },
        )
        // Blank is not "unprintable": a transfer that failed before it named a file has no name yet.
        val name = if (entry.name.isBlank()) stringResource(Res.string.ftail_file_fallback) else fileDisplayName(entry.name)
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
        // A finished row is the user's to clear; a running one has nothing to dismiss yet.
        if (active) Box(Modifier.size(22.dp)) else IconBtn("close", label = stringResource(Res.string.shell_tip_close), onClick = { onDismiss(entry.id) }, box = 22, icon = 14.sp)
    }
}

/** Right-hand text of a queue row: the failure, "done", or percent · bytes · speed while running. */
@Composable
private fun transferTailText(entry: TransferEntry): String {
    (entry.status as? TransferStatus.Failed)?.let { return transferFailureText(it.failure) }
    if (entry.status == TransferStatus.Done) return stringResource(Res.string.sftp_queue_done)
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
