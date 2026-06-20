package app.skerry.ui.design

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Vault view: категории секретов (sidebar) + список SSH-ключей + панель деталей ключа. */
@Composable
fun VaultView() {
    val mono = LocalFonts.current.mono
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(222.dp).fillMaxHeight().background(D.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
            Txt("VAULT", color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            VaultCategory("key", "SSH keys", "4", active = true)
            VaultCategory("badge", "Identities", "3")
            VaultCategory("password", "Passwords", "12")
            VaultCategory("vpn_lock", "Certificates", "2")
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).background(D.moss.copy(alpha = 0.06f)).border(1.dp, D.moss.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("lock", size = 15.sp, color = D.moss)
                    Txt("End-to-end encrypted", color = D.moss, size = 11.sp, weight = FontWeight.SemiBold)
                }
                Txt("Private keys are sealed with your master password and never sync in plaintext.", color = D.dim, size = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        VLine(D.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(D.bg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt("SSH keys", color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
                PrimaryButton("Generate key", onClick = {}, icon = "add")
            }
            HLine()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KeyCard(
                        iconBg = D.cyan.copy(alpha = 0.12f), iconColor = D.cyanBright, icon = "key",
                        name = "id_ed25519", badges = listOf("ED25519" to false, "DEFAULT" to true),
                        meta = "SHA256:8c3F1a…Qz9pK · used by 6 hosts", mono = mono,
                        border = D.cyan.copy(alpha = 0.18f), bg = D.cyan.copy(alpha = 0.04f),
                        trailing = { CopyButton() },
                    )
                    KeyCard(
                        iconBg = Color(0x0DFFFFFF), iconColor = D.dim, icon = "key",
                        name = "id_rsa_legacy", badges = listOf("RSA-4096" to null),
                        meta = "SHA256:2dE7b…Lm4xR · used by 2 hosts", mono = mono,
                        border = D.cyan08, bg = Color.Transparent,
                        trailing = { CopyButton() },
                    )
                    KeyCard(
                        iconBg = D.sunset.copy(alpha = 0.12f), iconColor = D.sunset, icon = "warning",
                        name = "deploy_ci", badges = listOf("ROTATE SOON" to false), rotateBadge = true,
                        meta = "SHA256:9aB0c…Tn2wE · created 412 days ago", mono = mono,
                        border = D.sunset.copy(alpha = 0.25f), bg = D.sunset.copy(alpha = 0.04f),
                        trailing = {
                            Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, D.sunset.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Txt("Rotate", color = D.sunset, size = 11.5.sp, weight = FontWeight.SemiBold)
                            }
                        },
                    )
                }
                VLine(D.line)
                KeyDetail(mono)
            }
        }
    }
}

@Composable
private fun VaultCategory(icon: String, label: String, count: String, active: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) D.cyan10 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (active) D.cyanBright else D.dim)
        Txt(label, color = if (active) D.cyanBright else D.dim, size = 12.5.sp, modifier = Modifier.weight(1f))
        Txt(count, color = D.faint, size = 10.sp)
    }
}

@Composable
private fun KeyCard(
    iconBg: Color,
    iconColor: Color,
    icon: String,
    name: String,
    badges: List<Pair<String, Boolean?>>,
    meta: String,
    mono: FontFamily,
    border: Color,
    bg: Color,
    rotateBadge: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, border, RoundedCornerShape(10.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(iconBg), contentAlignment = Alignment.Center) {
            Sym(icon, size = 20.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(name, color = D.text, size = 13.5.sp, weight = FontWeight.SemiBold)
                badges.forEach { (text, default) ->
                    when {
                        rotateBadge -> Badge(text, bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, radius = 3, size = 9.5.sp)
                        default == true -> Badge(text, bg = D.cyan14, fg = D.cyanBright, radius = 3, size = 9.5.sp)
                        default == false -> Badge(text, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, radius = 3, size = 9.5.sp)
                        else -> Badge(text, bg = Color(0x0FFFFFFF), fg = D.dim, radius = 3, size = 9.5.sp)
                    }
                }
            }
            Txt(meta, color = D.dim, size = 11.sp, font = mono, modifier = Modifier.padding(top = 6.dp))
        }
        trailing()
    }
}

@Composable
private fun CopyButton() {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Sym("content_copy", size = 16.sp, color = D.dim)
    }
}

@Composable
private fun KeyDetail(mono: FontFamily) {
    Column(Modifier.width(340.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(D.cyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym("key", size = 21.sp, color = D.cyanBright)
            }
            Column {
                Txt("id_ed25519", color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
                Txt("ED25519 · 256-bit", color = D.dim, size = 11.5.sp)
            }
        }
        DetailLabel("Public key")
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(D.terminalBg).border(1.dp, D.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Txt("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIH8c3F1a2bQz9pK7mLwR0vNqz9pKmaya@skerry.dev", color = D.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp)
        }
        DetailLabel("Fingerprint")
        Txt("SHA256:8c3F1a2bQz9pK7mLwR0vNqz9pK", color = D.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
        DetailLabel("Used by · 6 hosts")
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HostPill("prod-web-01", mono)
            HostPill("prod-web-02", mono)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HostPill("db-master", mono)
            HostPill("homelab-pi", mono)
            HostPill("+2", mono, dim = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Copy public key", onClick = {}, icon = "content_copy", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Export", onClick = {}, modifier = Modifier.weight(1f))
                GhostButton("Delete", onClick = {}, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailLabel(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun HostPill(name: String, mono: FontFamily, dim: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Txt(name, color = if (dim) D.dim else D.textBright, size = 11.sp, font = mono)
    }
}
