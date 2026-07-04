package app.skerry.ui.teams

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
import androidx.compose.foundation.shape.CircleShape
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
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_members
import app.skerry.ui.generated.resources.lib_teams_recent_activity
import app.skerry.ui.generated.resources.lib_teams_sidebar
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine

private data class Member(val initials: String, val avatar: Color, val name: String, val email: String, val role: String, val roleBg: Color, val roleFg: Color)
private data class SharedHost(val name: String, val members: String, val online: Boolean)
private data class Activity(val icon: String, val iconColor: Color, val prefix: String, val target: String, val time: String)

private val MEMBERS = listOf(
    Member("MK", D.cyan, "Maya Kovac", "maya@skerry.dev", "OWNER", D.amber.copy(alpha = 0.14f), D.amber),
    Member("TR", D.moss, "Theo Reyes", "theo@skerry.dev", "ADMIN", D.cyan.copy(alpha = 0.12f), D.cyanBright),
    Member("JL", D.dim, "June Lin", "june@skerry.dev", "MEMBER", Color(0x0DFFFFFF), D.dim),
)

private val SHARED_HOSTS = listOf(
    SharedHost("prod-web-01", "5 members", true),
    SharedHost("prod-web-02", "5 members", true),
    SharedHost("db-master", "3 members", true),
    SharedHost("k3s-control", "2 members", false),
)

private val ACTIVITY = listOf(
    Activity("login", D.moss, "Theo connected to ", "prod-web-02", "3 min ago"),
    Activity("add", D.cyan, "Maya shared host ", "k3s-control", "1 h ago"),
    Activity("key", D.amber, "June rotated key ", "deploy_ci", "Yesterday"),
)

/** Статический макет Teams — мок-путь/превью (LocalTeams == null), рисует данные прототипа. */
@Composable
internal fun TeamsMockView() {
    val mono = LocalFonts.current.mono
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(222.dp).fillMaxHeight().background(D.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
            Txt(stringResource(Res.string.lib_teams_sidebar), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            TeamRow("rocket_launch", "Platform crew", active = true)
            TeamRow("database", "Data team")
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.moss.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Sym("sync", size = 15.sp, color = D.moss)
                Txt("Synced 2 min ago", color = D.moss, size = 11.5.sp)
            }
        }
        VLine(D.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(D.bg).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Txt("Platform crew", color = D.text, size = 16.sp, weight = FontWeight.SemiBold)
                    Txt("5 members · 9 shared hosts · 2 shared vaults", color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                PrimaryButton(stringResource(Res.string.lib_teams_invite), onClick = {}, icon = "person_add")
            }
            HLine()
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                SectionLabel(stringResource(Res.string.lib_teams_members))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MEMBERS.forEach { MemberRow(it) }
                }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Shared hosts · 9")
                        SHARED_HOSTS.forEach { SharedHostRow(it, mono) }
                    }
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Shared vaults · 2")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SharedVaultRow(D.cyanBright, "Production secrets", "12 items · read-only for members")
                            SharedVaultRow(D.amber, "CI / deploy keys", "4 items · admins only")
                        }
                    }
                }
                Box(Modifier.padding(top = 24.dp))
                SectionLabel(stringResource(Res.string.lib_teams_recent_activity))
                ACTIVITY.forEach { ActivityRow(it, mono) }
            }
        }
    }
}

@Composable
private fun TeamRow(icon: String, name: String, active: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (active) D.cyan10 else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (active) D.cyanBright else D.dim)
        Txt(name, color = if (active) D.cyanBright else D.dim, size = 12.5.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun MemberRow(m: Member) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(m.avatar), contentAlignment = Alignment.Center) {
            Txt(m.initials, color = Color(0xFF0A1A26), size = 13.sp, weight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Txt(m.name, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(m.email, color = D.faint, size = 11.5.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(m.roleBg).padding(horizontal = 9.dp, vertical = 2.dp)) {
            Txt(m.role, color = m.roleFg, size = 10.sp, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SharedHostRow(h: SharedHost, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Dot(if (h.online) D.moss else D.faint)
        Txt(h.name, color = D.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
        Txt(h.members, color = D.faint, size = 10.sp)
    }
}

@Composable
private fun SharedVaultRow(iconColor: Color, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("folder_shared", size = 18.sp, color = iconColor)
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 12.5.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 10.5.sp)
        }
    }
}

@Composable
private fun ActivityRow(a: Activity, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(a.icon, size = 16.sp, color = a.iconColor)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Txt(a.prefix, color = D.textBright, size = 12.sp)
            Txt(a.target, color = D.cyanBright, size = 12.sp, font = mono)
        }
        Txt(a.time, color = D.faint, size = 11.sp)
    }
}
