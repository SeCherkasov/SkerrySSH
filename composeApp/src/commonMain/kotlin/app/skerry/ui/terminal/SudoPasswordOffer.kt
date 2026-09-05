package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.terminal.isSudoPasswordPrompt
import kotlin.concurrent.Volatile

/**
 * The cursor row an offer is anchored to: where it sits in the buffer and what it says. Both halves
 * are needed to tell one prompt from the next — sudo re-asking after a wrong password prints the
 * same words on a new row, and a row that merely repainted says something else.
 */
data class PromptRow(val row: Int, val text: String)

/**
 * How long a prompt has to have been on screen before Enter may answer it with the saved password.
 *
 * This is the whole difference between the user confirming an offer and the host harvesting a
 * keystroke. Any process on the connected machine can write to the tty, so it can print a prompt of
 * its own the instant before an Enter the user was already going to press — for the newline ending
 * the command they just typed. Without a dwell that Enter would hand it the account's SSH password,
 * which is a password sshd checked and that process never saw. Requiring the offer to have been
 * standing first means the keypress answers something the user had time to read.
 */
internal const val OFFER_DWELL_MS = 500L

/**
 * The password this session authenticated with, held so it can be offered back to a `sudo` prompt
 * for the same account (issue #360). Opt-in: a session is given one only when Terminal → "Offer the
 * saved password to sudo" is on and the profile actually authenticates with a password.
 *
 * The offer never sends anything by itself. A remote process can print whatever it likes, a
 * convincing prompt included, so what turns the offer into bytes on the wire is a keypress the user
 * made while the offer stood on screen — the same act as typing the password, with the client
 * naming whose secret it is about to hand over first. Everything here exists to keep that one act
 * honest:
 *
 * * [observe] is the only way an offer arms, and it arms nothing that is not sudo asking *this*
 *   account ([isSudoPasswordPrompt]);
 * * [take] answers only after [OFFER_DWELL_MS] on the same row, so the keypress belongs to a prompt
 *   the user saw rather than to one drawn underneath it;
 * * [decline] is what any other input does — the user is entering a password themselves, and the
 *   Enter that ends it must commit what they entered;
 * * [revoke] ends the offer for good when the setting goes off or the session closes.
 *
 * Arming is anchored to the row rather than latched, which is what lets sudo's re-ask after a wrong
 * password be answered again while the prompt already answered stays answered — each re-ask serving
 * its own dwell. Where the two cannot be told apart — a full scrollback pins the cursor to the same
 * row index and the re-ask says the same words — the offer stays spent and the password is typed by
 * hand: a heuristic that fails by not offering costs a keystroke, one that fails by offering costs
 * the secret.
 *
 * [take] and [decline] run on the UI thread while [observe] runs on the emulator's own coroutine, so
 * a keystroke can race a redraw. The states are snapshot-backed and the loser of such a race finds
 * the offer already gone: [take] then returns null and its caller delivers the keystroke as the
 * plain Enter it was, which is why nothing here may be the only thing that acts on a keypress.
 *
 * The password is a secret and is redacted from `toString` like every other one in the app; it lives
 * as a `String` for the same reason [app.skerry.shared.vault.CredentialSecret] does (the JVM cannot
 * zero one), which is why [revoke] drops the reference as soon as the session has no use for it.
 */
@Stable
class SudoPasswordOffer(
    private val username: String,
    /** Account and host as the hint names them, so a user nested elsewhere sees the mismatch. */
    val account: String,
    password: String,
    private val dwellMillis: Long = OFFER_DWELL_MS,
) {

    /** The prompt on screen and when it got there, or null when no offer stands. */
    private var standing: Standing? by mutableStateOf(null)

    /** The prompt this offer was already answered on — by being taken, or by being declined. */
    private var spentAt: PromptRow? by mutableStateOf(null)

    /**
     * The password, until [revoke] drops it. A plain field, not snapshot state: nothing in a
     * composition reads it, and `mutableStateOf` would defeat the drop. A snapshot state object
     * keeps its previous record until another write can recycle it, and `secret = null` is the last
     * write there ever is — so the record holding the plaintext would stay reachable from the pane
     * for the rest of the process, which is exactly what the vault lock calls [revoke] to prevent.
     * Volatile because [observe] reads it from the emulator's coroutine, while [take] writes it
     * from the UI thread and [revoke] from whichever of the two ends the offer — the vault lock on
     * the UI thread, or the session's own teardown on that coroutine.
     */
    @Volatile
    private var secret: String? = password

    /** Whether an offer stands right now. Snapshot state: the hint recomposes when it changes. */
    val stands: Boolean get() = standing != null

    /**
     * Take in the cursor row as it was just drawn. Arms the offer when the row is a prompt for this
     * account that has not been answered, restarts the dwell whenever the row changes, and disarms
     * as soon as the prompt is no longer what the cursor is on.
     */
    fun observe(prompt: PromptRow, now: Long) {
        // Disarming on a dead offer is unconditional, not an early return: [revoke] writes the
        // secret and the standing state one after the other, and a redraw landing between the two
        // could otherwise leave the hint on screen for the rest of the session with nothing behind
        // it. Any stray arming heals on the next row drawn.
        if (secret == null) {
            standing = null
            return
        }
        val current = standing
        if (current?.prompt == prompt) return // same row, still standing: its dwell keeps running
        standing = if (prompt != spentAt && isSudoPasswordPrompt(prompt.text, username)) {
            Standing(prompt, now)
        } else {
            null
        }
    }

    /**
     * The password, once, if an offer has stood long enough to have been read. Null when there is
     * none, when the dwell is unserved, or when a redraw took the offer away first — the caller
     * must then deliver the keystroke normally.
     */
    fun take(now: Long): String? {
        val current = standing ?: return null
        if (now - current.since < dwellMillis) return null
        spentAt = current.prompt
        standing = null
        return secret
    }

    /** The user is answering the prompt themselves; this offer is over. */
    fun decline() {
        val current = standing ?: return
        spentAt = current.prompt
        standing = null
    }

    /**
     * End the offer and drop the password. Called when the setting is turned off under a live
     * session and when the session closes: the credential outliving its connection is what the
     * connection controller already avoids by dropping its own copy.
     */
    fun revoke() {
        secret = null
        standing = null
    }

    override fun toString(): String = "SudoPasswordOffer(account=$account, password=redacted)"

    private data class Standing(val prompt: PromptRow, val since: Long)
}
