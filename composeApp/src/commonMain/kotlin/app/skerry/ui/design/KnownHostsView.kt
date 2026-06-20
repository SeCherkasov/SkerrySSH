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

private data class KnownHost(val name: String, val keyType: String, val fp: String, val seen: String, val changed: Boolean)

private val KNOWN = listOf(
    KnownHost("prod-web-01", "ed25519", "8c3F1a2bQz…pK9R", "Jan 12 2026", false),
    KnownHost("db-master", "ed25519", "2dE7bLm4xR…wQ1z", "Jan 12 2026", false),
    KnownHost("nas-truenas", "ed25519", "9aB0cTn2wE…changed", "Mar 4 2026", true),
    KnownHost("homelab-pi", "rsa", "5fG1hKp8s…vB3n", "Feb 2 2026", false),
)

/** Known hosts view: заголовок + предупреждение о смене ключа + таблица + панель сравнения отпечатков. */
@Composable
fun KnownHostsView() {
    val mono = LocalFonts.current.mono
    Column(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 22.dp, vertical = 14.dp)) {
            Txt("Known hosts", color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
            Txt("Verified host key fingerprints. Skerry warns when a key changes.", color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(10.dp)).background(D.sunset.copy(alpha = 0.05f)).border(1.dp, D.sunset.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Sym("gpp_maybe", size = 20.sp, color = D.sunset)
                    Column(Modifier.weight(1f)) {
                        Txt("Host key changed for nas-truenas", color = D.sunset, size = 13.sp, weight = FontWeight.SemiBold)
                        Txt("The ED25519 fingerprint differs from the one recorded on Mar 4. This could be a re-install — or a man-in-the-middle. Verify before reconnecting.", color = D.dim, size = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("Review fingerprint", D.sunset, D.sunset.copy(alpha = 0.4f), bold = true)
                            SmallButton("Dismiss", D.dim, D.cyan14)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, D.cyan08, RoundedCornerShape(10.dp))) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        KHeader("HOST", Modifier.weight(1f))
                        KHeader("KEY TYPE", Modifier.width(90.dp))
                        KHeader("FINGERPRINT (SHA256)", Modifier.weight(1.4f))
                        KHeader("FIRST SEEN", Modifier.width(100.dp))
                        KHeader("STATUS", Modifier.width(80.dp), end = true)
                    }
                    KNOWN.forEach { host ->
                        HLine()
                        KnownRow(host, mono)
                    }
                }
            }
            VLine(D.line)
            MismatchPanel(mono)
        }
    }
}

@Composable
private fun KHeader(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Txt(text, color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun KnownRow(host: KnownHost, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Txt(host.name, color = D.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
        Txt(host.keyType, color = D.dim, size = 12.sp, modifier = Modifier.width(90.dp))
        Txt(host.fp, color = if (host.changed) D.sunset else D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1.4f))
        Txt(host.seen, color = D.faint, size = 12.sp, modifier = Modifier.width(100.dp))
        Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (host.changed) {
                Sym("error", size = 14.sp, color = D.sunset)
                Txt("Changed", color = D.sunset, size = 11.sp)
            } else {
                Sym("verified", size = 14.sp, color = D.moss)
                Txt("Verified", color = D.moss, size = 11.sp)
            }
        }
    }
}

@Composable
private fun MismatchPanel(mono: FontFamily) {
    Column(Modifier.width(322.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("policy", size = 18.sp, color = D.sunset)
            Txt("Fingerprint mismatch", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        Txt("nas-truenas · ed25519", color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
        Txt("PREVIOUSLY RECORDED · MAR 4", color = D.moss, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp))
        FpBox("SHA256:9aB0cTn2wE4rXp1kLm7sQ8vZ", D.dim, D.moss.copy(alpha = 0.2f), mono)
        Txt("NOW OFFERED · TODAY", color = D.sunset, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FpBox("SHA256:Kp3xQ9zR1tWv7nB4mL0sJ2dF", D.sunset, D.sunset.copy(alpha = 0.3f), mono)
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp).clip(RoundedCornerShape(7.dp)).background(D.sunset.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Sym("info", size = 15.sp, color = D.sunset)
            Txt("If you recently re-installed this host, accepting is safe. Otherwise treat this as a possible interception.", color = D.dim, size = 11.sp, lineHeight = 16.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Accept new key", onClick = {}, modifier = Modifier.fillMaxWidth())
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.sunset.copy(alpha = 0.12f)).border(1.dp, D.sunset, RoundedCornerShape(7.dp)).padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt("Reject & block", color = D.sunset, size = 12.5.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FpBox(text: String, color: Color, border: Color, mono: FontFamily) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.terminalBg).border(1.dp, border, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Txt(text, color = color, size = 10.5.sp, font = mono)
    }
}

@Composable
private fun SmallButton(label: String, fg: Color, border: Color, bold: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, border, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Txt(label, color = fg, size = 11.5.sp, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}
