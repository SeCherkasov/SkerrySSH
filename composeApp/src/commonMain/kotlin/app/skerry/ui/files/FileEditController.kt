package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Size cap for the built-in viewer/editor (F3/F4). The whole file is held in memory twice (bytes +
 * decoded buffer) and rendered by a single text field, so this is deliberately far below the SFTP
 * client's transport-level read cap: config files, scripts and logs fit, disk images don't.
 */
const val MAX_EDIT_BYTES: Long = 1L * 1024 * 1024

/** Typed, user-facing reason the viewer/editor couldn't open or save a file; localized by the UI. */
enum class FileEditFailure {
    /** The file couldn't be read (missing, no permission, transport failure). */
    Read,

    /** The file is over [MAX_EDIT_BYTES]. */
    TooLarge,

    /** The content isn't editable text: NUL bytes or not valid UTF-8. */
    Binary,

    /** The buffer couldn't be written back. */
    Write,
}

/** Viewer/editor state for one file. */
sealed interface FileEditState {
    /** The file is being read. */
    data object Loading : FileEditState

    /** Content loaded; [text] is the current buffer with `\n` line endings. */
    data class Ready(val text: String) : FileEditState

    /** The file couldn't be opened; [failure] is the typed reason, localized by the UI. */
    data class Failed(val failure: FileEditFailure) : FileEditState
}

/**
 * Controller behind F3 View / F4 Edit for a single file of one pane's source, local or remote alike
 * ([FileContentBrowser]). Reads the file whole (capped at [maxBytes]), decodes it as strict UTF-8,
 * hands the UI an editable buffer and writes it back on [save].
 *
 * Refusals are explicit rather than best-effort: content with NUL bytes or invalid UTF-8 is reported
 * as [FileEditFailure.Binary] instead of being decoded leniently — lenient decoding would replace
 * the offending bytes with U+FFFD and silently corrupt the file the moment it's saved. A UTF-8 BOM
 * is deliberately left in the buffer as the character it is, so saving writes the file back with the
 * BOM it came with.
 *
 * Overwriting is guarded: if the file's size/mtime changed between [open] and [save], the write is
 * held back and [conflict] is raised for the UI to confirm ([confirmOverwrite]/[dismissConflict]).
 * A failed save never discards the buffer — [saveFailure] is reported and the text stays editable.
 *
 * [scope] must outlive the editor UI (the session scope): a write in flight has to finish even if
 * the view leaves composition, otherwise the file is left truncated. For the same reason the write
 * runs inside [writeGuard] — the owner (the transfer coordinator) holds the transport open until it
 * returns, so closing the tab mid-save can't pull the channel out from under a half-written file.
 * [onSaved] fires after each successful write, for the pane to refresh its listing.
 *
 * Operations are serialized via [busy]; like the other file-panel controllers this is a plain flag,
 * safe only because the scope is main-confined.
 */
@Stable
class FileEditController(
    private val source: FileContentBrowser,
    private val item: FileItem,
    readOnly: Boolean,
    private val scope: CoroutineScope,
    private val maxBytes: Long = MAX_EDIT_BYTES,
    private val onSaved: () -> Unit = {},
    private val writeGuard: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    /**
     * Whether the buffer is view-only (opened with F3). Not fixed for the editor's lifetime: F4
     * turns viewing into editing in place ([enableEditing]), the way mc does, so the user doesn't
     * have to close and re-open the file to change one line.
     */
    var readOnly: Boolean by mutableStateOf(readOnly)
        private set

    /** File name for the editor header. */
    val name: String get() = item.name

    /** Absolute path in the source's namespace, for the header subtitle. */
    val path: String get() = item.path

    var state: FileEditState by mutableStateOf(FileEditState.Loading)
        private set

    /** A save is in flight: the UI disables editing and the Save action. */
    var saving: Boolean by mutableStateOf(false)
        private set

    /**
     * Reason the last save failed, shown alongside the (preserved) buffer. Cleared when the next
     * save starts or the buffer is edited again.
     */
    var saveFailure: FileEditFailure? by mutableStateOf(null)
        private set

    /**
     * The file changed on the source since it was opened, and a save is waiting for confirmation.
     * The buffer is untouched while this is set.
     */
    var conflict: Boolean by mutableStateOf(false)
        private set

    /** Content as loaded (or last saved), for [dirty] and for restoring on cancel. */
    private var baseline: String by mutableStateOf("")

    /** Line ending the file used when it was read; restored on save. */
    private var lineEnding: LineEnding = LineEnding.Lf

    /** Size/mtime of the file when it was read (or last written), for conflict detection. */
    private var snapshot: FileItem? = null

    private var busy = false

    /** Whether the buffer differs from what's on the source. */
    val dirty: Boolean get() = (state as? FileEditState.Ready)?.text?.let { it != baseline } == true

    /** F4 in view mode: makes the loaded buffer editable. */
    fun enableEditing() {
        readOnly = false
    }

    /** Reads the file. Call once when the editor opens. */
    fun open() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val bytes = source.readFile(item.path, maxBytes)
                val decoded = decodeEditableText(bytes)
                if (decoded == null) {
                    state = FileEditState.Failed(FileEditFailure.Binary)
                    return@launch
                }
                lineEnding = detectLineEnding(decoded)
                baseline = normalizeLineEndings(decoded)
                snapshot = runCatching { source.stat(item.path) }.getOrNull() ?: item
                state = FileEditState.Ready(baseline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileBrowserException) {
                state = FileEditState.Failed(
                    if (e.failure == FileBrowserFailure.TooLarge) FileEditFailure.TooLarge else FileEditFailure.Read,
                )
            } catch (_: Exception) {
                // Anything the source didn't wrap still has to land somewhere: an unhandled type
                // would otherwise leave the editor spinning on Loading with no explanation.
                state = FileEditState.Failed(FileEditFailure.Read)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Buffer edit from the text field. Ignored in read-only mode, while a save is in flight, and
     * while a [conflict] is pending — the pending write is of the buffer as it was when the
     * conflict was raised, so it must not shift underneath the confirmation.
     */
    fun edit(value: String) {
        if (readOnly || saving || conflict) return
        val ready = state as? FileEditState.Ready ?: return
        if (ready.text == value) return
        saveFailure = null
        state = FileEditState.Ready(value)
    }

    /**
     * Writes the buffer back. No-op when read-only, unchanged, or already busy. If the file changed
     * on the source since [open], nothing is written and [conflict] is raised instead.
     */
    fun save() {
        if (readOnly || !dirty || busy) return
        busy = true
        scope.launch {
            try {
                val current = runCatching { source.stat(item.path) }.getOrNull()
                if (changedUnderneath(current)) {
                    conflict = true
                    return@launch
                }
                write()
            } finally {
                busy = false
            }
        }
    }

    /** Confirms the [conflict] dialog: writes the buffer over the changed file. */
    fun confirmOverwrite() {
        if (!conflict) return
        conflict = false
        if (readOnly || !dirty || busy) return
        busy = true
        scope.launch {
            try {
                write()
            } finally {
                busy = false
            }
        }
    }

    /** Cancels the [conflict] dialog, keeping the buffer unsaved. */
    fun dismissConflict() {
        conflict = false
    }

    /**
     * Whether the file on the source differs from the snapshot taken when it was read. An
     * unreadable/vanished stat isn't treated as a conflict: the write itself will fail with a real
     * error, which is more informative than a spurious "changed on the server".
     */
    private fun changedUnderneath(current: FileItem?): Boolean {
        val base = snapshot ?: return false
        if (current == null) return false
        return current.size != base.size || current.modifiedEpochSeconds != base.modifiedEpochSeconds
    }

    /** Encodes and writes the buffer, re-baselining on success. Callers own the [busy] flag. */
    private suspend fun write() {
        val text = (state as? FileEditState.Ready)?.text ?: return
        saving = true
        saveFailure = null
        try {
            writeGuard {
                source.writeFile(item.path, restoreLineEndings(text, lineEnding).encodeToByteArray())
                baseline = text
                // Re-baseline against what's actually on the source now, so the next save doesn't
                // see our own write (new size/mtime) as someone else's change. If that stat fails,
                // the previous snapshot is kept rather than dropped: a stale snapshot costs at most
                // one spurious "changed on the source?" prompt, while a null one would silently
                // disable conflict detection for the rest of the session.
                snapshot = runCatching { source.stat(item.path) }.getOrNull() ?: snapshot
            }
            onSaved()
        } catch (e: CancellationException) {
            throw e
        } catch (_: FileBrowserException) {
            saveFailure = FileEditFailure.Write
        } finally {
            saving = false
        }
    }
}

/** Line ending a file uses; the buffer always holds `\n` and the original is restored on save. */
internal enum class LineEnding { Lf, Crlf, Cr }

/**
 * Decodes [bytes] as editable text, or null if the content isn't text. Strict UTF-8 (a malformed
 * sequence is a refusal, not a replacement character) plus a NUL-byte check, since NUL is the
 * classic binary marker and valid UTF-8 all the same.
 */
internal fun decodeEditableText(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * Line ending of [text]: CRLF when every `\n` is preceded by `\r`, CR for a classic-Mac file with
 * bare `\r` and no `\n` at all, LF otherwise. A file mixing endings counts as LF and is normalized
 * throughout on save — rare enough to prefer one consistent ending over reproducing the mixture
 * line by line.
 */
internal fun detectLineEnding(text: String): LineEnding {
    val lf = text.count { it == '\n' }
    val crlf = countOccurrences(text, "\r\n")
    val cr = text.count { it == '\r' }
    return when {
        crlf > 0 && crlf == lf && crlf == cr -> LineEnding.Crlf
        lf == 0 && cr > 0 -> LineEnding.Cr
        else -> LineEnding.Lf
    }
}

/**
 * Puts [text] into the editor's own form: every ending becomes `\n`. Bare `\r` is translated too,
 * not just `\r\n` — a stray carriage return left in the buffer renders and moves the caret in ways
 * a text field doesn't define.
 */
internal fun normalizeLineEndings(text: String): String =
    if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text

/** Converts the buffer's `\n` endings back to the file's own [ending]. */
internal fun restoreLineEndings(text: String, ending: LineEnding): String =
    when (ending) {
        LineEnding.Lf -> text
        LineEnding.Crlf -> text.replace("\n", "\r\n")
        LineEnding.Cr -> text.replace('\n', '\r')
    }

private fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var index = text.indexOf(needle)
    while (index >= 0) {
        count++
        index = text.indexOf(needle, index + needle.length)
    }
    return count
}
