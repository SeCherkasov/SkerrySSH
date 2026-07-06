package app.skerry.ui.sftp

/**
 * Picks a local location for an SFTP transfer via the native platform dialog. Returns a handle
 * ([DownloadTarget]/[UploadSource]) rather than a raw path, because on Android the picker returns a
 * `content://` Uri, not a filesystem path sshj understands. Transfers always go through a staging
 * path, with post-processing encapsulated in the handle:
 * - desktop: staging = the real path, finalize/discard are no-ops;
 * - android: staging = a temp file in cache, finalize copies it to the chosen Uri.
 *
 * Returns `null` if the user cancelled or the platform doesn't support picking.
 */
expect suspend fun pickDownloadTarget(suggestedName: String): DownloadTarget?

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
