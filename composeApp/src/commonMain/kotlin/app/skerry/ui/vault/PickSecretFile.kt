package app.skerry.ui.vault

/**
 * Asks the user for a key/certificate *location* and returns it as a ref for
 * [app.skerry.shared.vault.CredentialSecret.KeyFile] — a filesystem path on desktop, a persisted
 * `content://` document Uri on Android. Null on cancel (or when no picker is available).
 *
 * Deliberately not [importTextFile]: that one reads the contents once, which is exactly what a
 * file-backed credential must not do — the point is to re-read the file on every connection, so
 * only the location is kept.
 *
 * [title] labels the desktop picker window; Android's Storage Access Framework has no custom-title
 * hook and ignores it.
 */
expect suspend fun pickSecretFileRef(title: String): String?
