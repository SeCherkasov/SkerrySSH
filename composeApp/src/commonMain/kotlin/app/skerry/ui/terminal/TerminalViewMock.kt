package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

// Mock preview of the terminal view (offscreen render/design preview without live sessions):
// static demo terminal, mocked AI card, and static split pane.

/** Demo terminal (mock path without live sessions: offscreen render/preview). */
@Composable
internal fun MockTerminalPane(state: DesktopDesignState, modifier: Modifier = Modifier) {
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

            // AI suggestion card is behind a feature flag; hidden by default.
            if (LocalFeatures.current.ai) AiSuggestionCard()

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

// AI suggestion card.

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
                Txt("Insert into terminal", color = D.ink, size = 11.5.sp, weight = FontWeight.SemiBold)
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

// Static split pane for the mock preview.

@Composable
internal fun SplitPane(modifier: Modifier = Modifier) {
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
