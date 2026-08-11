package app.skerry.ui.sftp

import app.skerry.ui.design.untrustedLabel

/**
 * Picks a local location for an SFTP transfer via the native platform dialog. Returns a handle
 * ([DownloadTarget]/[UploadSource]) rather than a raw path, because on Android the picker returns a
 * `content://` Uri, not a filesystem path sshj understands. Transfers always go through a staging
 * path, with post-processing encapsulated in the handle:
 * - desktop: staging = the real path, finalize/discard are no-ops;
 * - android: staging = a temp file in cache, finalize copies it to the chosen Uri.
 *
 * Returns `null` if the user cancelled or the platform doesn't support picking.
 *
 * [suggestedName] is only ever a *name*: the caller passes it through [safeDownloadName], because
 * the string comes from the remote side and this is the one hop where it would become part of a
 * local file name.
 */
expect suspend fun pickDownloadTarget(suggestedName: String): DownloadTarget?

/**
 * A remote entry's name, made fit to seed a save dialog: no directory part, no characters that
 * would draw it as a different name in the dialog's own field.
 *
 * Not [app.skerry.shared.io.safeFileStem], which maps everything outside a small whitelist to `-`:
 * that is right for a name this app invents (a recording's file name) and wrong here, where the
 * user is about to read the preset and would find `report (1).log` rewritten for no reason.
 */
internal fun safeDownloadName(raw: String): String =
    untrustedLabel(raw.substringAfterLast('/').substringAfterLast('\\'))
        // Every leading dot, not one: `..` would otherwise save as `.`, and `..name` as `.name` —
        // a file the file manager then hides, which is not what the user pressed Save for.
        .trimStart('.')
        .ifBlank { DOWNLOAD_FALLBACK_NAME }

/** What a name that is nothing but path separators or unprintable characters saves as. */
private const val DOWNLOAD_FALLBACK_NAME = "download"

expect suspend fun pickUploadSource(): UploadSource?

/**
 * Local download target. The SFTP client writes bytes to [stagingPath]; on success [finalize] is
 * called (on Android, copies staging to the Uri), on error/cancel [discard] is called (cleans up
 * staging). Orchestrated by [app.skerry.ui.files.TransferCoordinator].
 */
interface DownloadTarget {
    /** Display name for the UI (transfer banner). */
    val displayName: String

    /** Filesystem path the SFTP client writes bytes to. */
    val stagingPath: String

    /** Moves staging to the real target. Called exactly once on a successful transfer. */
    suspend fun finalize()

    /** Releases staging without moving it (transfer error/cancel, or [finalize] failure). */
    suspend fun discard()
}

/**
 * Local upload source. By the time [pickUploadSource] returns, the bytes are already available at
 * [stagingPath] (on Android, copied from the Uri to a temp file). The SFTP client reads from there;
 * on completion (success or error) [app.skerry.ui.files.TransferCoordinator] calls [cleanup].
 */
interface UploadSource {
    /** File name on the remote side (no path). */
    val name: String

    /** Filesystem path the SFTP client reads bytes from. */
    val stagingPath: String

    /** Releases staging. Called exactly once after the transfer completes. */
    suspend fun cleanup()
}
