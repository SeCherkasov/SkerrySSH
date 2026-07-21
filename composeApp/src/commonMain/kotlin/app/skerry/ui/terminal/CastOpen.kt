package app.skerry.ui.terminal

import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.parseAsciicast
import app.skerry.ui.vault.importTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What came back from "Play a recording": a recording, nothing (cancelled), or an unusable file. */
sealed interface CastOpenResult {
    data class Loaded(val cast: Asciicast) : CastOpenResult

    data object Cancelled : CastOpenResult

    /** The file was picked but isn't an asciicast v2 recording (or was too big to read). */
    data object Invalid : CastOpenResult
}

/**
 * Asks the user for a `.cast` file and parses it. Parsing runs off the main thread — a long
 * recording is megabytes of JSON lines, and the picker returns on the UI dispatcher.
 */
suspend fun openCastFile(): CastOpenResult {
    val text = importTextFile() ?: return CastOpenResult.Cancelled
    val cast = withContext(Dispatchers.Default) { parseAsciicast(text) } ?: return CastOpenResult.Invalid
    return CastOpenResult.Loaded(cast)
}
