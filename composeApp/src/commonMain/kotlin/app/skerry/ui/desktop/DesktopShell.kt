package app.skerry.ui.desktop

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.theme.SkerryColors

/**
 * Каркас окна Skerry: три ряда — titlebar (44dp),
 * рабочая область (сайдбар 260dp + основная зона) и statusbar (26dp). Содержимое подаётся
 * слотами, чтобы каркас не знал про данные хостов/сессий.
 */
@Composable
fun DesktopShell(
    titlebar: @Composable () -> Unit,
    sidebar: @Composable () -> Unit,
    statusbar: @Composable () -> Unit,
    main: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(SkerryColors.nightSea)) {
        Box(Modifier.fillMaxWidth().height(44.dp)) { titlebar() }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(260.dp).fillMaxHeight()) { sidebar() }
            Box(Modifier.fillMaxHeight().width(1.dp).background(SkerryColors.lineStrong))
            Box(Modifier.weight(1f).fillMaxHeight()) { main() }
        }
        Box(Modifier.fillMaxWidth().height(26.dp)) { statusbar() }
    }
}

/** Цвет статус-точки сессии: подключено — moss, идёт connect — amber, ошибка — storm, иначе faint. */
fun statusColor(state: ConnectionUiState?) = when (state) {
    is ConnectionUiState.Connected -> SkerryColors.moss
    is ConnectionUiState.Connecting -> SkerryColors.amber
    is ConnectionUiState.Error -> SkerryColors.storm
    else -> SkerryColors.textFaint
}

/** Знак Skerry: шхера-скала с маяком (упрощение SVG из прототипа), cyan-обводка + amber-огонь. */
@Composable
fun BrandLogo(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 32f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        val rock = Path().apply {
            moveTo(o(4f, 26f).x, o(4f, 26f).y)
            lineTo(o(8f, 18f).x, o(8f, 18f).y)
            lineTo(o(12f, 22f).x, o(12f, 22f).y)
            lineTo(o(16f, 14f).x, o(16f, 14f).y)
            lineTo(o(20f, 20f).x, o(20f, 20f).y)
            lineTo(o(24f, 16f).x, o(24f, 16f).y)
            lineTo(o(28f, 26f).x, o(28f, 26f).y)
            close()
        }
        drawPath(rock, SkerryColors.deep)
        drawPath(rock, SkerryColors.cyan, style = Stroke(width = 1.2f * u, cap = StrokeCap.Round))
        // Башня маяка
        drawLine(SkerryColors.cyan, o(15f, 16f), o(15f, 6f), 1f * u)
        drawLine(SkerryColors.cyan, o(17.5f, 16f), o(17.5f, 6f), 1f * u)
        // Огонь
        drawCircle(SkerryColors.amber, 2f * u, o(16f, 5f))
    }
}

/** Кнопка-иконка titlebar/панелей: 28dp, скруглённая, гасится при [enabled]=false. */
@Composable
fun ChromeIconButton(
    kind: SkerryIconKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 18.dp,
    tint: androidx.compose.ui.graphics.Color = SkerryColors.textDim,
) {
    Box(
        modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SkerryIcon(kind, tint = tint, size = iconSize)
    }
}

/** Пилюля-индикатор замка в titlebar: cyan, клик блокирует хранилище. */
@Composable
fun LockIndicator(onLock: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SkerryColors.cyanSoft)
            .border(1.dp, SkerryColors.cyanDim, RoundedCornerShape(6.dp))
            .clickable(onClick = onLock)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SkerryIcon(SkerryIconKind.LockOpen, tint = SkerryColors.cyan, size = 14.dp)
        Text("Разблокировано", color = SkerryColors.cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Одна вкладка сессии в titlebar. [active] — видимая в основной области (не «открыт каталог»). */
@Composable
fun SessionTab(
    title: String,
    state: ConnectionUiState?,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(if (active) SkerryColors.nightSea else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = 1.dp,
                color = if (active) SkerryColors.lineStrong else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor(state)))
        Text(
            title,
            color = if (active) SkerryColors.text else SkerryColors.textDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        ChromeIconButton(
            SkerryIconKind.Close,
            onClick = onClose,
            iconSize = 13.dp,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Session-bar основной области: слева хост и метаданные, справа действия. */
@Composable
fun DesktopSessionBar(
    title: String,
    meta: String,
    onDisconnect: () -> Unit,
    mono: FontFamily,
    modifier: Modifier = Modifier,
    onSftp: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SkerryColors.nightSeaSoft)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = SkerryColors.text, fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(meta, color = SkerryColors.textFaint, fontSize = 11.5.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ChromeIconButton(SkerryIconKind.Folder, onClick = { onSftp?.invoke() }, enabled = onSftp != null)
            // Проброс портов (-L/-R); cable-иконки нет — используем Tune.
            ChromeIconButton(SkerryIconKind.Tune, onClick = { onForward?.invoke() }, enabled = onForward != null)
            ChromeIconButton(SkerryIconKind.Info, onClick = { onInfo?.invoke() }, enabled = onInfo != null)
            ChromeIconButton(SkerryIconKind.Power, onClick = onDisconnect, tint = SkerryColors.sunset)
        }
    }
}

/**
 * AI-бар под терминалом — поле AI-ассистента, НЕ командная строка
 * (команды печатаются прямо в терминал). Бар пока неинтерактивный
 * плейсхолдер: amber-искра + подсказка + метка «Phase 2». Фокус не забирает — он у терминала.
 */
@Composable
fun AiBar(modifier: Modifier = Modifier, mono: FontFamily) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SkerryColors.nightSeaSoft)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(SkerryColors.amberSoft),
            contentAlignment = Alignment.Center,
        ) {
            SkerryIcon(SkerryIconKind.Ai, tint = SkerryColors.amber, size = 16.dp)
        }
        Text(
            "Спросить Skerry: «найди файлы больше 100 МБ»",
            color = SkerryColors.textFaint,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(SkerryColors.amberSoft)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("AI · Phase 2", color = SkerryColors.amber, fontFamily = mono, fontSize = 10.5.sp)
        }
    }
}

/** Нижний statusbar. [ok] подсвечивает левую точку (moss), иначе faint. Без вымышленных метрик. */
@Composable
fun DesktopStatusBar(
    left: String,
    right: String,
    ok: Boolean,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxSize()
            .background(SkerryColors.deep2)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) SkerryColors.moss else SkerryColors.textFaint))
            Text(left, color = if (ok) SkerryColors.moss else SkerryColors.textFaint, fontFamily = mono, fontSize = 10.5.sp)
        }
        Text(right, color = SkerryColors.textFaint, fontFamily = mono, fontSize = 10.5.sp)
    }
}
