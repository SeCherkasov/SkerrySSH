package app.skerry.ui.vault

/**
 * Saves [content] to a file chosen via the native "Save As" dialog with [suggestedName]. Used to
 * export a key from the vault (public key / private PEM). Returns `true` if the file was written,
 * `false` on cancel or an unsupported platform.
 */
expect suspend fun exportTextFile(suggestedName: String, content: String): Boolean
