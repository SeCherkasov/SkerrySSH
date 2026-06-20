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

private data class Snippet(val icon: String, val title: String, val cmd: String, val selected: Boolean = false)

private val SNIPPETS = listOf(
    Snippet("monitoring", "Disk usage report", "df -h | sort -k5 -r", selected = true),
    Snippet("memory", "Top memory procs", "ps aux --sort=-%mem | head"),
    Snippet("restart_alt", "Restart nginx", "sudo systemctl reload nginx"),
    Snippet("cleaning_services", "Clear docker cache", "docker system prune -af"),
)

/** Snippets view: библиотека сниппетов (sidebar) + редактор (main). */
@Composable
fun SnippetsView() {
    val mono = LocalFonts.current.mono
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0x08FFFFFF)).border(1.dp, D.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("search", size = 16.sp, color = D.faint)
                    Txt("Search snippets…", color = D.faint, size = 12.5.sp)
                }
            }
            HLine()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
                Txt("LIBRARY", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp))
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SNIPPETS.forEach { SnippetRow(it, mono) }
                }
            }
            HLine()
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                PrimaryButton("New snippet", onClick = {}, icon = "add", modifier = Modifier.fillMaxWidth())
            }
        }
        VLine(D.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(D.bg).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sym("monitoring", size = 20.sp, color = D.cyanBright)
                    Txt("Disk usage report", color = D.text, size = 17.sp, weight = FontWeight.SemiBold)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("#monitoring")
                    Chip("#disk")
                    Chip("+ tag")
                }
            }
            HLine()
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                FieldLabelSnip("Command")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(D.terminalBg).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Txt("df", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -h | ", color = D.textBright, size = 13.sp, font = mono)
                    Txt("sort", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -k5 -r | ", color = D.textBright, size = 13.sp, font = mono)
                    Txt("head", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -n 10", color = D.textBright, size = 13.sp, font = mono)
                }
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabelSnip("Run on")
                        SnipSelect("Current host")
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabelSnip("Shortcut")
                        SnipInput("⌘⇧D", mono)
                    }
                }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Run now", onClick = {}, icon = "play_arrow")
                    GhostButton("Save", onClick = {})
                }
            }
        }
    }
}

@Composable
private fun SnippetRow(snippet: Snippet, mono: FontFamily) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (snippet.selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (snippet.selected) D.cyan.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym(snippet.icon, size = 15.sp, color = if (snippet.selected) D.cyanBright else D.dim)
            Txt(snippet.title, color = if (snippet.selected) D.cyanBright else D.textBright, size = 12.5.sp, weight = FontWeight.Medium)
        }
        Txt(snippet.cmd, color = if (snippet.selected) D.dim else D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FieldLabelSnip(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SnipSelect(value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 13.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}

@Composable
private fun SnipInput(value: String, mono: FontFamily) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
        Txt(value, color = D.text, size = 13.sp, font = mono)
    }
}
