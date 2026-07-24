package app.skerry.ui.terminal

import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.parseAsciicast
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_player_open
import app.skerry.ui.vault.importTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/** What came back from "Play a recording": a recording, nothing (cancelled), or an unusable file. */
sealed interface CastOpenResult {
    /** [fileName] is the picked file's name — the player tab is labelled with it. */
    data class Loaded(val cast: Asciicast, val fileName: String) : CastOpenResult

    data object Cancelled : CastOpenResult

    /** The file was picked but isn't an asciicast v2 recording (or was too big to read). */
    data object Invalid : CastOpenResult
}

/**
 * Asks the user for a `.cast` file and parses it. Parsing runs off the main thread — a long
 * recording is megabytes of JSON lines, and the picker returns on the UI dispatcher.
 */
suspend fun openCastFile(): CastOpenResult {
    val file = importTextFile(getString(Res.string.term_player_open)) ?: return CastOpenResult.Cancelled
    val cast = withContext(Dispatchers.Default) { parseAsciicast(file.text) } ?: return CastOpenResult.Invalid
    return CastOpenResult.Loaded(cast, file.name)
}
