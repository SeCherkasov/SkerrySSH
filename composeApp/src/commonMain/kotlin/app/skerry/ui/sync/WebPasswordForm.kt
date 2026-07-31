package app.skerry.ui.sync

import app.skerry.shared.sync.MAX_WEB_PASSWORD_LENGTH
import app.skerry.shared.sync.MIN_WEB_PASSWORD_LENGTH

/**
 * Validation for the Web access form (Settings → Sync), shared by desktop and mobile so the gate and
 * its wording can't drift between them. The password itself lives in the composable as a String and
 * goes to [SyncCoordinator] as a CharArray, which the coordinator wipes.
 *
 * The bounds are the server's ([MIN_WEB_PASSWORD_LENGTH]…[MAX_WEB_PASSWORD_LENGTH]); checking them
 * here only saves the round trip that would come back 400 and read as a protocol error.
 */
data class WebPasswordForm(
    val password: String = "",
    val confirm: String = "",
) {
    /** Typed something, but not enough of it. Empty input is not an error — nothing is typed yet. */
    val tooShort: Boolean get() = password.isNotEmpty() && password.length < MIN_WEB_PASSWORD_LENGTH

    val tooLong: Boolean get() = password.length > MAX_WEB_PASSWORD_LENGTH

    /** The repeat field disagrees. Only once it has been typed into. */
    val mismatch: Boolean get() = confirm.isNotEmpty() && password != confirm

    val canSubmit: Boolean get() = password.length in MIN_WEB_PASSWORD_LENGTH..MAX_WEB_PASSWORD_LENGTH && password == confirm
}
