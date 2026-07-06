package app.skerry.ui.mobile

import androidx.compose.runtime.Composable
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_known_changed
import app.skerry.ui.generated.resources.shtail_known_key_changed_title
import app.skerry.ui.generated.resources.shtail_known_mismatch_body
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import app.skerry.ui.known.shortFingerprint
import org.jetbrains.compose.resources.stringResource

/**
 * Pure logic for the mobile Known hosts screen over the live
 * [app.skerry.ui.known.KnownHostsController]: no table or side-by-side fingerprint comparison,
 * just row cards and a key-change banner with Accept/Reject inline.
 */

/** Key type without the `ssh-` prefix (ed25519, rsa, …). */
internal fun mobileKnownKeyType(keyType: String): String = keyType.removePrefix("ssh-")

/**
 * Known-host row subtitle: `<type> · <short fingerprint>` for a trusted key, `<type> · changed`
 * for a row with an unresolved key change (the exact fingerprint isn't shown since it's in question).
 */
@Composable
internal fun mobileKnownSubtitle(entry: KnownHostEntry): String {
    val type = mobileKnownKeyType(entry.host.keyType)
    return if (entry.status == KnownHostStatus.Changed) {
        "$type · ${stringResource(Res.string.shtail_known_changed)}"
    } else {
        "$type · ${shortFingerprint(entry.host.fingerprint)}"
    }
}

/** Row status icon: `verified` (trusted) / `error` (key changed). */
internal fun mobileKnownStatusIcon(status: KnownHostStatus): String =
    if (status == KnownHostStatus.Changed) "error" else "verified"

/** Key-change banner title: `Key changed: <host>`. */
@Composable
internal fun mobileKnownBannerTitle(mismatch: HostKeyMismatch): String =
    stringResource(Res.string.shtail_known_key_changed_title, mismatch.host)

/** Banner body: which key type changed, prompting verification. */
@Composable
internal fun mobileKnownBannerBody(mismatch: HostKeyMismatch): String =
    stringResource(Res.string.shtail_known_mismatch_body, mobileKnownKeyType(mismatch.keyType).uppercase())
