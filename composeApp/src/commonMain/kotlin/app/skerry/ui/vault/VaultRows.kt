package app.skerry.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialUsage
import app.skerry.shared.vault.SshCertificateInfo
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.shared.vault.securityMoment
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_meta_rotated
import app.skerry.ui.generated.resources.vault_meta_valid_until
import app.skerry.ui.known.shortFingerprint
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Width of the selection edge on the left of a selected row. */
private val EDGE_WIDTH = 2.dp

/**
 * One secret in the vault list: type glyph, name, a monospace line of what it actually is
 * (algorithm, fingerprint, what uses it), and the type badge on the right. Dense by design — a
 * keychain is scanned, not admired, so rows sit close and are separated by a hairline rather than
 * floating as cards.
 *
 * The selected row carries a teal edge and a lifted background, the same signal the host tree and
 * the tunnel table use for "this is what the panel on the right is showing".
 *
 * [status] renders extra badges the row must not hide (an expired certificate, an unreadable key
 * file); they sit before the type badge, loudest first.
 */
@Composable
fun SecretRow(
    icon: String,
    iconColor: Color,
    tintedIcon: Boolean,
    name: String,
    meta: String,
    mono: FontFamily,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        val edge = Skerry.colors.teal
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Skerry.colors.overlaySoft else Color.Transparent)
                // The selection edge is painted, not laid out: a strip inside a Row can't stretch to
                // the row's height (the Row is still measuring it), and it must not shift the text of
                // an unselected row by so much as a pixel.
                .drawBehind { if (selected) drawRect(edge, size = Size(EDGE_WIDTH.toPx(), size.height)) }
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (tintedIcon) iconColor.copy(alpha = 0.12f) else Skerry.colors.overlayMed),
                contentAlignment = Alignment.Center,
            ) {
                Sym(icon, size = 16.sp, color = if (tintedIcon) iconColor else Skerry.colors.dim)
            }
            Column(Modifier.weight(1f)) {
                Txt(
                    name,
                    color = Skerry.colors.text, size = 12.5.sp, weight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // A secret with nothing to say about it (a fresh password bound to nothing) gets no
                // empty second line reserving height under its name.
                if (meta.isNotEmpty()) {
                    Txt(
                        meta,
                        modifier = Modifier.padding(top = 2.dp),
                        color = Skerry.colors.faint, size = 11.sp, font = mono,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                status()
            }
        }
        HLine()
    }
}

/** Separator between the facts on a row's meta line — typography, identical in every locale. */
private const val META_SEPARATOR = " · "

/**
 * The monospace line under a secret's name: the facts about it, separated by dots — what it is
 * technically (algorithm and fingerprint for a key, length and rotation for a password, validity for
 * a certificate, the path for a file-backed one), then what depends on it ([usedBy]).
 *
 * The *type* is deliberately absent: the glyph on the left and the sidebar category already say it.
 * So is the length of a password — a character count narrows a guess for anyone reading the screen.
 * Metadata still being parsed off the main thread ([rememberKeyInfo]) simply contributes nothing
 * rather than a placeholder.
 */
@Composable
fun secretMetaLine(
    secret: CredentialSecret,
    keyInfo: SshPublicKeyInfo?,
    certInfo: SshCertificateInfo?,
    usage: CredentialUsage?,
    usedBy: String?,
): String {
    val facts = when (secret) {
        is CredentialSecret.Certificate -> listOfNotNull(
            certInfo?.let { "${it.keyTypeLabel}-cert" },
            certInfo?.let { stringResource(Res.string.vault_meta_valid_until, it.validUntil) },
        )
        is CredentialSecret.PrivateKey -> listOfNotNull(
            keyInfo?.keyTypeLabel,
            keyInfo?.let { shortFingerprint(it.fingerprintSha256) },
        )
        is CredentialSecret.Password -> listOfNotNull(
            // Only when the date actually parses: "rotated —" would read as data rather than as
            // "unknown", unlike the standalone em dash in the panel's fact rows.
            usage?.changedAt?.takeIf { securityMoment(it) != null }
                ?.let { stringResource(Res.string.vault_meta_rotated, momentLabel(it)) },
        )
        // The location is the whole secret here; the badge already says it lives in a file.
        is CredentialSecret.KeyFile -> listOf(secret.privateKeyRef)
    }
    return (facts + listOfNotNull(usedBy)).joinToString(META_SEPARATOR)
}
