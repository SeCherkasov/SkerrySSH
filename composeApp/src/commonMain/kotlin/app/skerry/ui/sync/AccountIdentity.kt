package app.skerry.ui.sync

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_account_id_label
import app.skerry.ui.generated.resources.sync_copied
import app.skerry.ui.generated.resources.sync_identity_hint
import app.skerry.ui.generated.resources.sync_sharing_fingerprint_label
import app.skerry.ui.vault.copyTextToClipboard
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Account identifiers for Teams invites: accountId and the own sharing-key fingerprint (X25519).
 * Shown in account settings (desktop Settings → Sync, mobile More → Sync) so they can be copied
 * and sent to a team owner. Both are public — copied via the normal clipboard, not marked sensitive.
 */
@Composable
fun AccountIdentityBlock(accountId: String, modifier: Modifier = Modifier) {
    val teams = LocalTeams.current
    // Fingerprint is derived from the identity pair in the personal vault; settings only open with an
    // unlocked vault, so null here means preview/offscreen without a Teams backend (or no key yet).
    val fingerprint = remember(teams, accountId) { teams?.ownFingerprint() }
    val mono = LocalFonts.current.mono
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1500)
            copied = null
        }
    }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IdentityRow(stringResource(Res.string.sync_account_id_label), accountId, mono, copied == accountId) { copied = accountId }
        if (fingerprint != null) {
            IdentityRow(stringResource(Res.string.sync_sharing_fingerprint_label), fingerprint, mono, copied == fingerprint, display = elideFingerprint(fingerprint)) { copied = fingerprint }
        }
        Txt(stringResource(Res.string.sync_identity_hint), color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp)
    }
}

/**
 * All 8 dash-separated groups don't fit a phone-width row; show head and tail only. The copy
 * button still copies the full value, and invite dialogs keep showing it whole for verification.
 */
internal fun elideFingerprint(value: String): String {
    val groups = value.split('-')
    if (groups.size < 5) return value
    return groups.take(2).joinToString("-") + "-…-" + groups.takeLast(2).joinToString("-")
}

@Composable
private fun IdentityRow(label: String, value: String, mono: FontFamily, copied: Boolean, display: String = value, onCopied: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(label.uppercase(), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Txt(display, color = Skerry.colors.cyanBright, size = 13.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
        }
        if (copied) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Sym("check", size = 16.sp, color = Skerry.colors.moss)
                Txt(stringResource(Res.string.sync_copied), color = Skerry.colors.moss, size = 11.sp)
            }
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .clickable {
                        copyTextToClipboard(value)
                        onCopied()
                    }
                    .padding(6.dp),
            ) {
                Sym("content_copy", size = 16.sp, color = Skerry.colors.dim)
            }
        }
    }
}
