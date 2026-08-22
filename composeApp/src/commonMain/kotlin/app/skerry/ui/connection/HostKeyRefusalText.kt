package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import app.skerry.shared.ssh.HostKeyRefusal
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_err_hostkey_cert
import app.skerry.ui.generated.resources.conn_err_hostkey_changed
import app.skerry.ui.generated.resources.conn_err_hostkey_jump
import app.skerry.ui.generated.resources.conn_err_hostkey_locked
import app.skerry.ui.generated.resources.conn_err_hostkey_refused
import app.skerry.ui.generated.resources.conn_err_hostkey_untrusted
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The line shown for a refused host key. A resource rather than a string: the two call sites read
 * it differently — the terminal notice from a composition, the tunnel list from a coroutine — and
 * both must say the same thing.
 *
 * Every reason ends in the move that resolves it; a refusal the user can't act on is the failure
 * this mapping exists to avoid.
 */
internal fun hostKeyRefusalText(refusal: HostKeyRefusal): StringResource = when (refusal) {
    HostKeyRefusal.KeyChanged -> Res.string.conn_err_hostkey_changed
    HostKeyRefusal.NotTrustedYet -> Res.string.conn_err_hostkey_untrusted
    HostKeyRefusal.TrustStoreUnreadable -> Res.string.conn_err_hostkey_locked
    HostKeyRefusal.CertificateRejected -> Res.string.conn_err_hostkey_cert
    HostKeyRefusal.RejectedByUser -> Res.string.conn_err_hostkey_refused
}

/**
 * The line as shown, with [hop] marking a key that belonged to a ProxyJump hop rather than to the
 * host being dialed. Without the mark the advice sends the user to the wrong machine: a bastion that
 * rotated its key reads as "the host you asked for changed its key", and the target's entry is
 * intact.
 */
@Composable
internal fun hostKeyRefusalLine(refusal: HostKeyRefusal, hop: Boolean): String {
    val line = stringResource(hostKeyRefusalText(refusal))
    return if (hop) stringResource(Res.string.conn_err_hostkey_jump, line) else line
}

/** [hostKeyRefusalLine] for a caller outside a composition; null when the refusal carried no reason. */
internal suspend fun hostKeyRefusalLine(e: SshHostKeyRejectedException): String? {
    val line = getString(hostKeyRefusalText(e.refusal ?: return null))
    return if (e.hop) getString(Res.string.conn_err_hostkey_jump, line) else line
}
