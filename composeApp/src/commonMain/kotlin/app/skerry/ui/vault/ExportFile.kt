package app.skerry.ui.vault

import kotlinx.coroutines.CancellationException

/**
 * How an export ended. A `Boolean` cannot separate the two ways nothing was written — the user
 * closing the Save-As dialog, and the write itself failing — and the caller has to, because one of
 * them is worth interrupting them over and the other is not.
 */
enum class ExportOutcome {
    Saved,
    Cancelled,
    Failed;

    /** A cancelled Save-As is the user's own choice; a failed write is not, and silence reads as success. */
    val worthReporting: Boolean get() = this == Failed
}

/**
 * Saves [content] to a file chosen via the native "Save As" dialog with [suggestedName]. Used to
 * export a private key from the vault and to save a session recording. The content is secret in both
 * cases, so implementations write it as a private file (0600 where the platform has permissions).
 */
internal expect suspend fun exportTextFile(suggestedName: String, content: String): ExportOutcome

/**
 * [exportTextFile] with anything it throws collapsed to [ExportOutcome.Failed]. The platform writers
 * guard their own IO, but the step before it — opening the picker — is not theirs: `launch()` on an
 * Android document contract throws when no provider handles the intent, and that exception would
 * escape into the calling screen's `rememberCoroutineScope()`, cancel it, and leave every later
 * action on that screen silently doing nothing.
 *
 * [CancellationException] is rethrown rather than reported: a cancelled export is the composition
 * going away, not a failure to tell the user about.
 */
internal suspend fun exportFileGuarded(suggestedName: String, content: String): ExportOutcome =
    guardedExport { exportTextFile(suggestedName, content) }

/**
 * Runs [write] and collapses anything it throws to [ExportOutcome.Failed], rethrowing
 * [CancellationException]. Shared by [exportFileGuarded] and the vault's export actions, which have
 * to guard an injected writer rather than this one.
 */
internal suspend fun guardedExport(write: suspend () -> ExportOutcome): ExportOutcome =
    runCatching { write() }
        .getOrElse { if (it is CancellationException) throw it else ExportOutcome.Failed }
