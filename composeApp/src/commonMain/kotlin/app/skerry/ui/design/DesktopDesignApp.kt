package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Корень десктопного макета `docs/new/Skerry.html`, воспроизведённого 1:1. Поставляет шрифты
 * через [LocalFonts], держит [DesktopDesignState] и собирает структуру: titlebar (44dp) →
 * rail (62dp) + viewport → statusbar (26dp). Поверх — оверлеи lock / new-connection / settings.
 *
 * Это визуальный перенос прототипа: данные мок-статичные ([DesktopMockData]), демо-терминал и
 * переключатели — заглушки; живые SSH/SFTP/vault подключаются отдельным слоем.
 */
@Composable
fun DesktopDesignApp(state: DesktopDesignState = remember { DesktopDesignState() }) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    CompositionLocalProvider(LocalFonts provides fonts) {
        Box(Modifier.fillMaxSize().background(D.bg)) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(state)
                HLine()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    IconRail(state)
                    VLine(D.line)
                    Box(Modifier.weight(1f).fillMaxHeight()) { Viewport(state) }
                }
                HLine()
                StatusBar()
            }
            if (state.modalOpen) NewConnectionModal(state)
            if (state.settingsOpen) SettingsPanel(state)
            if (state.locked) LockScreen(state)
        }
    }
}

@Composable
private fun TitleBar(state: DesktopDesignState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Brush.verticalGradient(listOf(D.titleTop, D.titleBottom)))
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            BrandMark(size = 28.dp)
            Txt("Skerry", color = D.text, size = 14.5.sp, weight = FontWeight.Bold, letterSpacing = (-0.2).sp)
        }
        Row(
            Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.tabs.forEachIndexed { i, tab ->
                SessionTabChip(tab, active = i == state.activeTab, onClick = { state.setTab(i) }, onClose = { state.closeTab(i) })
            }
            IconBtn("add", onClick = state::openModal, box = 26, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(D.cyan08)
                    .border(1.dp, D.cyan20, RoundedCornerShape(6.dp))
                    .clickable(onClick = state::lock)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym("lock_open", size = 14.sp, color = D.cyan)
                Txt("Unlocked", color = D.cyan, size = 11.sp, weight = FontWeight.Medium)
            }
            IconBtn("tune", onClick = state::openSettings)
            IconBtn("more_vert", onClick = {})
        }
    }
}

@Composable
private fun SessionTabChip(tab: SessionTab, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(if (active) D.bg else Color.Transparent)
            .border(
                1.dp,
                if (active) D.cyan14 else Color.Transparent,
                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(tab.dot)
        Txt(
            tab.name,
            color = if (active) D.text else D.dim,
            size = 12.sp,
            weight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp),
        )
        IconBtn("close", onClick = onClose, box = 16, icon = 14.sp, tint = D.dim)
    }
}

@Composable
private fun IconRail(state: DesktopDesignState) {
    Column(
        Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(D.railBg)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        RAIL.forEach { item ->
            RailButton(
                icon = item.icon,
                label = item.label,
                active = state.view == item.view,
                onClick = { state.showView(item.view) },
            )
        }
        Spacer(Modifier.weight(1f))
        RailButton(icon = "settings", label = "Settings", active = false, onClick = state::openSettings)
    }
}

@Composable
private fun RailButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val fg = if (active) D.cyanBright else D.faint
    Box(Modifier.fillMaxWidth()) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 9.dp)
                    .width(2.dp)
                    .height(20.dp)
                    .background(D.cyan, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) D.cyan10 else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Sym(icon, size = 21.sp, color = fg)
            Txt(label, color = fg, size = 9.sp, weight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
        }
    }
}

@Composable
private fun StatusBar() {
    val mono = LocalFonts.current.mono
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Color(0xFF0A1A26))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusItem("circle", "Connected", color = D.moss, iconSize = 11.sp, mono = mono)
            StatusItem("network_ping", "42 ms", mono = mono)
            StatusItem("arrow_upward", "1.2 KB/s", mono = mono)
            StatusItem("arrow_downward", "8.4 KB/s", mono = mono)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusItem("memory", "SSH-2.0-OpenSSH_8.9p1", mono = mono)
            Txt("UTF-8 · LF", color = D.faint, size = 10.5.sp, font = mono)
            Txt("80 × 24", color = D.faint, size = 10.5.sp, font = mono)
        }
    }
}

@Composable
private fun StatusItem(
    icon: String,
    text: String,
    color: Color = D.faint,
    iconSize: TextUnit = 13.sp,
    mono: FontFamily,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Sym(icon, size = iconSize, color = color)
        Txt(text, color = color, size = 10.5.sp, font = mono)
    }
}
