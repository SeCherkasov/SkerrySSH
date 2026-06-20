package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

private data class TunnelRule(
    val type: String, val typeBg: Color, val typeFg: Color,
    val source: String, val arrow: String, val dest: String, val destDim: Boolean,
    val via: String, val active: Boolean,
)

private val TUNNELS = listOf(
    TunnelRule("LOCAL", D.cyan.copy(alpha = 0.12f), D.cyanBright, "127.0.0.1:8080", "arrow_forward", "10.0.0.5:80", false, "prod-web-01", true),
    TunnelRule("REMOTE", D.amber.copy(alpha = 0.14f), D.amber, "0.0.0.0:9000", "arrow_forward", "localhost:3000", false, "homelab-pi", true),
    TunnelRule("SOCKS", D.moss.copy(alpha = 0.14f), D.moss, "127.0.0.1:1080", "all_inclusive", "dynamic proxy", true, "db-master", false),
)

/** Port forwarding (Tunnels) view: заголовок + таблица правил + панель деталей туннеля. */
@Composable
fun TunnelsView() {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Txt("Port forwarding", color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
                Txt("SSH tunnels — local, remote and dynamic (SOCKS) port forwards.", color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            PrimaryButton("New rule", onClick = {}, icon = "add")
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, D.cyan08, RoundedCornerShape(10.dp))) {
                    TunnelHeaderRow()
                    TUNNELS.forEach { rule ->
                        HLine()
                        TunnelRow(rule)
                    }
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sym("bolt", size = 15.sp, color = D.moss)
                    Txt("2 active tunnels · 1.4 MB forwarded this session", color = D.faint, size = 11.5.sp)
                }
            }
            VLine(D.line)
            TunnelDetail()
        }
    }
}

@Composable
private fun TunnelHeaderRow() {
    Row(
        Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderCell("TYPE", Modifier.width(76.dp))
        HeaderCell("SOURCE", Modifier.weight(1f))
        Box(Modifier.width(20.dp))
        HeaderCell("DESTINATION", Modifier.weight(1f))
        HeaderCell("VIA HOST", Modifier.width(110.dp))
        HeaderCell("ACTIVE", Modifier.width(56.dp), end = true)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Txt(text, color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun TunnelRow(rule: TunnelRule) {
    val mono = LocalFonts.current.mono
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(76.dp)) {
            Badge(rule.type, bg = rule.typeBg, fg = rule.typeFg, radius = 4, size = 10.sp)
        }
        Txt(rule.source, color = D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
        Box(Modifier.width(20.dp)) { Sym(rule.arrow, size = 16.sp, color = D.faint) }
        Txt(rule.dest, color = if (rule.destDim) D.dim else D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
        Txt(rule.via, color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.width(110.dp))
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
            Toggle(rule.active, onToggle = {})
        }
    }
}

@Composable
private fun TunnelDetail() {
    val mono = LocalFonts.current.mono
    Column(
        Modifier.width(308.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge("LOCAL", bg = D.cyan.copy(alpha = 0.12f), fg = D.cyanBright, radius = 4, size = 10.sp)
            Txt("Tunnel detail", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        FieldLabel("Type")
        SelectField("Local forward")
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Bind address"); InputField("127.0.0.1", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel("Port"); InputField("8080", mono) }
        }
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Destination"); InputField("10.0.0.5", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel("Port"); InputField("80", mono) }
        }
        Box(Modifier.padding(bottom = 16.dp))
        FieldLabel("Live throughput")
        ThroughputRow("arrow_upward", D.cyanBright, 0.38f, "42 KB/s", mono)
        Box(Modifier.padding(bottom = 8.dp))
        ThroughputRow("arrow_downward", D.moss, 0.71f, "1.1 MB/s", mono)
        Box(Modifier.padding(bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save", onClick = {}, modifier = Modifier.weight(1f))
            GhostButton("Remove", onClick = {}, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun SelectField(value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 12.5.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}

@Composable
private fun InputField(value: String, mono: FontFamily) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Txt(value, color = D.text, size = 12.5.sp, font = mono)
    }
}

@Composable
private fun ThroughputRow(icon: String, color: Color, fraction: Float, value: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(icon, size = 14.sp, color = color)
        MeterBar(fraction, color, Modifier.weight(1f))
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            Txt(value, color = D.dim, size = 11.sp, font = mono)
        }
    }
}
