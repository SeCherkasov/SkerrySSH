package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Терминальный view: hosts-sidebar + main (toolbar, панели, AI-bar) + info-panel. */
@Composable
fun TerminalView(state: DesktopDesignState) {
    Row(Modifier.fillMaxSize()) {
        HostsSidebar(state)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SessionToolbar(state)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                TerminalPane(state, Modifier.weight(1f))
                if (state.split) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(D.cyan14))
                    SplitPane(Modifier.weight(1f))
                }
                if (state.infoPanel) InfoPanel()
            }
            AiBar()
        }
    }
}

// ──────────────────────────────── hosts sidebar ────────────────────────────────

@Composable
private fun HostsSidebar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0x08FFFFFF))
                    .border(1.dp, D.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = D.faint)
                Txt("Search hosts, tags, IPs…", color = D.faint, size = 12.5.sp, modifier = Modifier.weight(1f))
                Box(Modifier.border(1.dp, D.cyan14, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Txt("⌘K", color = D.faint, size = 10.sp, font = mono)
                }
            }
            Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip("All", active = true)
                Chip("#prod")
                Chip("#docker")
                Chip("#db")
            }
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt("HOSTS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Sym("create_new_folder", size = 14.sp, color = D.faint)
            }
            HOST_GROUPS.forEach { group -> HostGroupBlock(group, state, mono) }
            Txt(
                "RECENT",
                color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 4.dp),
            )
            Row(
                Modifier.padding(start = 16.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("history", size = 14.sp, color = D.faint)
                Txt("user@vps.example.com", color = D.dim, size = 11.5.sp, font = mono)
            }
        }
        HLine()
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            PrimaryButton("New connection", onClick = state::openModal, icon = "add_link", modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HostGroupBlock(group: HostGroup, state: DesktopDesignState, mono: FontFamily) {
    Column(Modifier.padding(bottom = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Sym("expand_more", size = 16.sp, color = D.faint)
            Sym("folder_open", size = 15.sp, color = D.cyanBright)
            Txt(group.name, color = D.dim, size = 12.5.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Txt(group.hosts.size.toString(), color = D.faint, size = 10.sp)
            }
        }
        Column(Modifier.padding(start = 22.dp)) {
            group.hosts.forEach { host -> HostRow(host, state, mono) }
        }
    }
}

@Composable
private fun HostRow(host: MockHost, state: DesktopDesignState, mono: FontFamily) {
    val active = state.selectedHost == host.name
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) D.cyan10 else Color.Transparent)
            .clickable { state.selectHost(host.name) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(host.status.color)
        Txt(host.name, color = if (active) D.cyanBright else D.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
        if (host.badge != null) {
            val strict = host.badge == "STRICT"
            Badge(host.badge, bg = if (strict) D.strictBg else D.devBg, fg = if (strict) D.strictFg else D.moss)
        }
    }
}

// ──────────────────────────────── session toolbar ────────────────────────────────

@Composable
private fun SessionToolbar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    Column {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("root@prod-web-01", color = D.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("192.168.1.45:22", color = D.dim, size = 11.5.sp)
                    Txt(" · ", color = D.faint, size = 11.5.sp)
                    Txt("●", color = D.moss, size = 11.5.sp)
                    Txt(" 04:12:45", color = D.faint, size = 11.5.sp)
                }
                Txt("SSHv2 · aes256-gcm · ed25519", color = D.faint, size = 11.5.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconBtn("splitscreen_right", onClick = state::toggleSplit)
                IconBtn("folder", onClick = { state.showView(DesktopView.Sftp) })
                IconBtn("lan", onClick = { state.showView(DesktopView.Ports) })
                IconBtn("info", onClick = state::toggleInfo)
                IconBtn("power_settings_new", onClick = {}, tint = D.sunset)
            }
        }
        HLine()
    }
}

// ──────────────────────────────── terminal pane ────────────────────────────────

@Composable
private fun TerminalPane(state: DesktopDesignState, modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    Column(modifier.fillMaxHeight().background(D.terminalBg)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Txt("Last login: Sat Jun 21 14:22:10 2026 from 10.0.0.15", color = D.faint, size = 13.sp, font = mono, lineHeight = 20.sp)
            Prompt(mono, "df -h")
            TermOut("Filesystem      Size  Used Avail Use% Mounted on", mono)
            Row {
                Txt("/dev/sda1        50G   42G  ", color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
                Txt("5.2G   87%", color = D.sunset, size = 13.sp, font = mono, lineHeight = 20.sp)
                Txt(" /", color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
            }
            TermOut("/dev/sda2       200G  120G   75G  62% /var", mono)
            TermOut("tmpfs           4.0G  1.2M  4.0G   1% /tmp", mono)
            Prompt(mono, "tail -f /var/log/nginx/access.log")
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:01] \"GET /api/v1/status HTTP/1.1\" ", "200", " 154", D.moss)
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:05] \"POST /api/v1/telemetry HTTP/1.1\" ", "201", " 12", D.moss)
            TermOut("127.0.0.1 - - [21/Jun/2026:14:25:12] \"GET /admin/config HTTP/1.1\" 403 89", mono, color = D.sunset)
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:15] \"GET /assets/main.css HTTP/1.1\" ", "200", " 4521", D.moss)
            TermOut("127.0.0.1 - - [21/Jun/2026:14:25:22] \"POST /api/auth HTTP/1.1\" 500 245", mono, color = D.storm)

            AiSuggestionCard()

            state.termLines.forEach { line ->
                if (line.isCmd) Prompt(mono, line.text) else TermOut(line.text, mono, color = line.color)
            }

            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PromptLabel(mono)
                BasicTextField(
                    value = state.cmd,
                    onValueChange = state::onCmd,
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    textStyle = TextStyle(color = D.white, fontSize = 13.sp, fontFamily = mono),
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.runCmd() }),
                    decorationBox = { inner ->
                        if (state.cmd.isEmpty()) {
                            Txt("type a command — try: ls, df -h, help", color = D.faint, size = 13.sp, font = mono)
                        }
                        inner()
                    },
                )
            }
        }
    }
}

@Composable
private fun PromptLabel(mono: FontFamily) {
    Row {
        Txt("root@prod-web-01", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
        Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
        Txt("~", color = D.moss, size = 13.sp, font = mono)
        Txt("#", color = D.cyanBright, size = 13.sp, font = mono)
    }
}

@Composable
private fun Prompt(mono: FontFamily, cmd: String) {
    Row {
        PromptLabel(mono)
        Txt(" $cmd", color = D.white, size = 13.sp, font = mono, lineHeight = 20.sp)
    }
}

@Composable
private fun TermOut(text: String, mono: FontFamily, color: Color = D.textMid) {
    Txt(text, color = color, size = 13.sp, font = mono, lineHeight = 20.sp)
}

@Composable
private fun LogLine(mono: FontFamily, head: String, code: String, tail: String, codeColor: Color) {
    Row {
        Txt(head, color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
        Txt(code, color = codeColor, size = 13.sp, font = mono, lineHeight = 20.sp)
        Txt(tail, color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
    }
}

// ──────────────────────────────── AI suggestion card ────────────────────────────────

@Composable
private fun AiSuggestionCard() {
    val mono = LocalFonts.current.mono
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(D.sunset.copy(alpha = 0.04f))
            .border(1.dp, D.amber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym("auto_awesome", size = 15.sp, color = D.sunset)
            Txt("AI SUGGESTION (UNTRUSTED LOG SOURCE)", color = D.sunset, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
            Txt("Qwen 2.5 Coder · local", color = D.faint, size = 10.sp, font = mono)
        }
        HLine(D.sunset.copy(alpha = 0.2f))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Txt("I see a 500 error on POST /api/auth and disk is at 87%. To investigate the auth error, check the application error log:", color = D.dim, size = 12.sp, lineHeight = 18.sp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x4D000000))
                    .border(1.dp, D.cyan14, RoundedCornerShape(5.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Txt("tail -n 50 /var/log/nginx/error.log | grep -A 5 \"auth\"", color = D.text, size = 12.5.sp, font = mono)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(D.sunset.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym("warning", size = 14.sp, color = D.sunset)
                Txt("This analysis used untrusted log content. Inspect the command carefully — log entries can be crafted to manipulate AI output.", color = D.sunset, size = 11.5.sp, lineHeight = 16.sp)
            }
        }
        HLine(D.amber.copy(alpha = 0.15f))
        Row(
            Modifier.fillMaxWidth().background(Color(0x26000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(D.amber).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Insert into terminal", color = Color(0xFF0A1A26), size = 11.5.sp, weight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, D.cyan14, RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Explain", color = D.text, size = 11.5.sp, weight = FontWeight.Medium)
            }
            Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, D.cyan14, RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Dismiss", color = D.dim, size = 11.5.sp, weight = FontWeight.Medium)
            }
        }
    }
}

// ──────────────────────────────── split pane ────────────────────────────────

@Composable
private fun SplitPane(modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    Column(modifier.fillMaxHeight().background(D.terminalBg)) {
        Box(Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Txt("root@db-master · 192.168.1.50", color = D.dim, size = 11.sp, font = mono)
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row {
                Txt("root@db-master", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
                Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("~", color = D.moss, size = 13.sp, font = mono)
                Txt("# ", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("systemctl status postgresql", color = D.white, size = 13.sp, font = mono)
            }
            TermOut("● postgresql.service - PostgreSQL RDBMS", mono, color = D.moss)
            TermOut("   Loaded: loaded (/lib/systemd/system/postgresql.service)", mono)
            Row {
                Txt("   Active: ", color = D.textMid, size = 13.sp, font = mono)
                Txt("active (running)", color = D.moss, size = 13.sp, font = mono)
                Txt(" since Fri 2026-06-20", color = D.textMid, size = 13.sp, font = mono)
            }
            TermOut("   Memory: 412.0M", mono)
            TermOut("      CPU: 2h 14min 03s", mono)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("root@db-master", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
                Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("~", color = D.moss, size = 13.sp, font = mono)
                Txt("# ", color = D.cyanBright, size = 13.sp, font = mono)
                Box(Modifier.padding(start = 2.dp).size(width = 7.dp, height = 14.dp).background(D.cyan))
            }
        }
    }
}

// ──────────────────────────────── info panel ────────────────────────────────

@Composable
private fun InfoPanel() {
    val mono = LocalFonts.current.mono
    Column(Modifier.width(268.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt("CONNECTION", color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(D.moss)
                Txt("LIVE", color = D.moss, size = 10.sp)
            }
        }
        HLine()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            InfoRow("Host", "prod-web-01", mono)
            InfoRow("Address", "192.168.1.45:22", mono)
            InfoRow("User", "root", mono)
            InfoRow("Auth", "id_ed25519", mono)
            InfoRow("Cipher", "aes256-gcm", mono)
            InfoRow("Uptime", "04:12:45", mono)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Txt("LIVE METRICS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 8.dp))
            Meter("CPU", "34%", 0.34f, D.cyan, D.textBright, mono)
            Meter("Memory", "2.1 / 4 GB", 0.52f, D.moss, D.textBright, mono)
            Meter("Disk /", "87%", 0.87f, D.sunset, D.sunset, mono)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Txt("SYSTEM", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 8.dp))
            Txt("Ubuntu 22.04.4 LTS\nLinux 5.15.0-105 x86_64\n4 vCPU · load 0.42 0.51 0.48", color = D.dim, size = 10.5.sp, font = mono, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(label, color = D.faint, size = 11.5.sp)
        Txt(value, color = D.textBright, size = 11.5.sp, font = mono)
    }
}

@Composable
private fun Meter(label: String, value: String, fraction: Float, bar: Color, valueColor: Color, mono: FontFamily) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(label, color = D.dim, size = 11.sp)
            Txt(value, color = valueColor, size = 11.sp, font = mono)
        }
        MeterBar(fraction, bar)
    }
}

// ──────────────────────────────── AI bar ────────────────────────────────

@Composable
private fun AiBar() {
    val mono = LocalFonts.current.mono
    Column {
        HLine()
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.amber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym("auto_awesome", size = 16.sp, color = D.amber)
            }
            Txt("Ask Skerry: 'find files larger than 100MB'   ·   Ctrl / to focus", color = D.dim, size = 13.sp, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiBarTag("lock", "Local · Qwen 2.5", mono)
                AiBarTag("verified_user", "STRICT", mono)
            }
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.cyan), contentAlignment = Alignment.Center) {
                Sym("arrow_upward", size = 16.sp, color = Color(0xFF0A1A26))
            }
        }
    }
}

@Composable
private fun AiBarTag(icon: String, text: String, mono: FontFamily) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, D.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Sym(icon, size = 12.sp, color = D.faint)
        Txt(text, color = D.faint, size = 10.5.sp, font = mono)
    }
}
