package app.skerry.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.Credential
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_copy_public_key
import app.skerry.ui.generated.resources.vault_delete
import app.skerry.ui.generated.resources.vault_e2e_description
import app.skerry.ui.generated.resources.vault_e2e_encrypted
import app.skerry.ui.generated.resources.vault_export
import app.skerry.ui.generated.resources.vault_header_summary
import app.skerry.ui.generated.resources.vault_item_count
import app.skerry.ui.generated.resources.vault_title
import app.skerry.ui.generated.resources.vault_generate_key
import app.skerry.ui.generated.resources.vault_label_public_key
import app.skerry.ui.generated.resources.vault_sidebar_header
import app.skerry.ui.generated.resources.vault_used_by
import app.skerry.ui.vault.VaultCategoryKind
import app.skerry.ui.vault.VaultPresentation
import app.skerry.ui.vault.title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry

/** Vault view (mock): categories, the secret list and the detail panel, over [mockSecrets]. */
@Composable
internal fun MockVaultView() {
    val mono = LocalFonts.current.mono
    val secrets = mockSecrets()
    val selected = secrets.first().first
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
            SidebarSectionTitle(stringResource(Res.string.vault_sidebar_header), Modifier.padding(start = 10.dp, bottom = 10.dp))
            VaultPresentation.sidebarCategories.forEach { kind ->
                VaultCategoryRow(
                    icon = kind.icon,
                    label = kind.title(),
                    count = VaultPresentation.count(kind, secrets.map { it.first }).toString(),
                    active = kind == VaultCategoryKind.SSH_KEYS,
                    onClick = {},
                )
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.moss.copy(alpha = 0.06f)).border(1.dp, Skerry.colors.moss.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("lock", size = 15.sp, color = Skerry.colors.moss)
                    Txt(stringResource(Res.string.vault_e2e_encrypted), color = Skerry.colors.moss, size = 11.sp, weight = FontWeight.SemiBold)
                }
                Txt(stringResource(Res.string.vault_e2e_description), color = Skerry.colors.dim, size = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        VLine(Skerry.colors.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(Skerry.colors.bg)) {
            SectionHeader(
                title = stringResource(Res.string.vault_title),
                subtitle = stringResource(
                    Res.string.vault_header_summary,
                    pluralStringResource(Res.plurals.vault_item_count, secrets.size, secrets.size),
                ),
                actions = { PrimaryButton(stringResource(Res.string.vault_generate_key), onClick = {}, icon = "add") },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    secrets.forEach { (credential, meta) ->
                        val style = VaultPresentation.secretStyle(credential.secret, Skerry.colors)
                        SecretRow(
                            icon = style.icon,
                            iconColor = style.color,
                            tintedIcon = style.tinted,
                            name = credential.label,
                            meta = meta,
                            mono = mono,
                            selected = credential.id == selected.id,
                            onClick = {},
                        )
                    }
                }
                VLine(Skerry.colors.line)
                MockSecretDetail(selected, mono)
            }
        }
    }
}

/** Detail panel of the mock selection: the same fact rows, key block and sections as the live one. */
@Composable
private fun MockSecretDetail(credential: Credential, mono: FontFamily) {
    Column(Modifier.width(DETAIL_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        DetailLabel(credential.label)
        SecretFactRows(
            typeLabel = "ED25519",
            fingerprint = "SHA256:9pQk…dR2f",
            secret = credential.secret,
            usage = mockUsage(),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        DetailLabel(stringResource(Res.string.vault_label_public_key))
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.terminalBg).border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Txt("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP9k2RmXq7f0LcV8m1Zb4t6Yh3sJdQ1oNp5uWxK deploy@skerry", color = Skerry.colors.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp)
        }
        DetailLabel(stringResource(Res.string.vault_used_by, 2))
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HostPill("prod-web-01", mono)
            HostPill("prod-web-02", mono)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(Res.string.vault_copy_public_key), onClick = {}, icon = "content_copy", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(stringResource(Res.string.vault_export), onClick = {}, modifier = Modifier.weight(1f))
                GhostButton(stringResource(Res.string.vault_delete), onClick = {}, fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
            }
        }
        SecretSectionLabel(encryptionSectionTitle())
        SecretEncryptionRows(syncing = true)
    }
}
