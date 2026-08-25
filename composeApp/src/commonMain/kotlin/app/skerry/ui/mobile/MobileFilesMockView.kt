package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

private data class MockFileEntry(val icon: String, val name: String, val meta: String, val trailing: String, val selected: Boolean = false)

/** Static Remote pane data (preview/offscreen). */
private val MOCK_REMOTE_FILES = listOf(
    MockFileEntry("folder", "html", "drwxr-xr-x · 4 items", "chevron_right"),
    MockFileEntry("folder", "releases", "drwxr-xr-x · 12 items", "chevron_right"),
    MockFileEntry("description", "nginx.conf", "3.1 KB · Jun 20", "ios_share", selected = true),
    MockFileEntry("terminal", "deploy.sh", "1.8 KB · Jun 18", "ios_share"),
    MockFileEntry("description", "robots.txt", "112 B · May 30", "ios_share"),
)

/** Static mock of the Files section (preview/offscreen, no backend). */
@Composable
internal fun MockMobileFilesView(mono: FontFamily) {
    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle()
            // The screenshot path draws what the live screen draws: a preview breadcrumb without
            // the refresh the live one now carries is store-asset drift.
            MobileFilesBreadcrumbRow(label = "prod-web-01", path = "/var/www", mono = mono, onRefresh = {})
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp)) {
                MOCK_REMOTE_FILES.forEach { MockFileRow(it, mono) }
            }
            MockTransferCard(mono)
            Spacer(Modifier.height(96.dp))
        }
        MobileFabButton(open = false, onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp))
    }
}

@Composable
private fun MockFileRow(entry: MockFileEntry, mono: FontFamily) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (entry.selected) Skerry.colors.cyan06 else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Sym(entry.icon, size = 23.sp, color = if (entry.icon == "folder") Skerry.colors.cyanBright else Skerry.colors.dim)
        Column(Modifier.weight(1f)) {
            Txt(entry.name, color = Skerry.colors.text, size = 14.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt(entry.meta, color = Skerry.colors.faint, size = 11.sp)
        }
        Sym(entry.trailing, size = 20.sp, color = Skerry.colors.faint)
    }
}

/** Layout's transfer card (static, 64%). */
@Composable
private fun MockTransferCard(mono: FontFamily) {
    Column(
        Modifier
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Sym("upload", size = 17.sp, color = Skerry.colors.cyan)
            Txt("backup.tar.gz", color = Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Txt("64%", color = Skerry.colors.dim, size = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Skerry.colors.overlayStrong)) {
            MeterBar(0.64f, Skerry.colors.cyan, Modifier.fillMaxWidth())
        }
    }
}
