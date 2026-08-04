package app.skerry.ui.runbook

import app.skerry.shared.runbook.ResolvedRunbookStep
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookTransferDirection

/** Prefix marking a step that moves a file rather than typing a line, as the mockup writes it. */
private const val TRANSFER_PREFIX = "sftp: "

/** Between the two ends of a transfer, pointing the way the file travels. */
private const val ARROW = " → "

/**
 * The single monospace line that stands for a step wherever one is listed: the library rows, the
 * progress list, the start dialog. A command is its own line; a transfer reads as its two paths in
 * travel order, so a step's direction is visible without a second label.
 *
 * Not a string resource: both forms are paths and shell text, and the only word in them (`sftp`) is
 * a protocol name, not prose.
 */
fun RunbookStep.summaryLine(): String = when (this) {
    is RunbookStep.Command -> command
    is RunbookStep.Transfer -> transferLine(localPath, remotePath, direction)
}

/** [summaryLine] for a step whose variables are already filled in — what the run will actually do. */
fun ResolvedRunbookStep.summaryLine(): String = when (this) {
    is ResolvedRunbookStep.Command -> line
    is ResolvedRunbookStep.Transfer -> transferLine(localPath, remotePath, direction)
}

private fun transferLine(localPath: String, remotePath: String, direction: RunbookTransferDirection): String =
    when (direction) {
        RunbookTransferDirection.UPLOAD -> TRANSFER_PREFIX + localPath + ARROW + remotePath
        RunbookTransferDirection.DOWNLOAD -> TRANSFER_PREFIX + remotePath + ARROW + localPath
    }
