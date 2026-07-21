package app.skerry.ui.vault

/**
 * Largest file the app will read into memory through [importTextFile]. Matches the recording cap in
 * [app.skerry.shared.terminal.SessionRecorder]: a `.cast` Skerry wrote never exceeds it, and a file
 * from elsewhere shouldn't be able to exhaust memory just by being picked.
 */
const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

/** A file the user picked: its display name (for labels) and contents. */
data class ImportedFile(val name: String, val text: String)

/**
 * Reads a text file chosen by the user in the native picker, as UTF-8. Returns `null` on cancel, on
 * a read failure, or when the file is larger than [maxBytes] — nothing is imported silently.
 */
expect suspend fun importTextFile(maxBytes: Int = MAX_IMPORT_BYTES): ImportedFile?
