package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.connection.ConnectionUiState

/**
 * Корневой таб More мобильного макета `docs/new/Skerry Mobile.html` (слайс 5): заголовок + карточка
 * профиля + список разделов-ссылок. Хаб навигации к push-экранам Port forwarding / Known hosts / Team
 * и к действию «Lock Skerry».
 *
 * Живой путь ([onLock] != null, за гейтом vault): карточка профиля — локальный vault (аккаунт/sync —
 * Phase 2, не выдумываем имя/PRO), подзаголовки Port forwarding/Known hosts — живые счётчики
 * ([mobileMorePortsSubtitle]/[mobileMoreKnownSubtitle]) из [LocalSessions]/[LocalKnownHosts], строки
 * AI/Appearance/Security инертны (разделы вне MVP), «Lock Skerry» реально запирает vault. Превью/
 * офскрин ([onLock] == null) — статичная карточка и подписи ровно из макета для сверки 1:1.
 */
@Composable
fun MobileMoreScreen(state: MobileDesignState, onLock: (() -> Unit)?) {
    val preview = onLock == null
    Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 14.dp)) {
            Txt("More", color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        }
        if (preview) MockProfileCard() else LocalVaultCard()

        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            val ports = if (preview) "2 active" else portsSubtitle()
            val known = if (preview) "1 changed" else knownSubtitle()
            val knownWarn = if (preview) true else knownChanged() > 0

            MoreRow("lan", D.cyanBright, "Port forwarding", ports, D.moss, onClick = { state.push(MobileRoute.Ports) })
            MoreRow("fingerprint", D.cyanBright, "Known hosts", known, if (knownWarn) D.sunset else D.moss, onClick = { state.push(MobileRoute.Known) })
            MoreRow("groups", D.cyanBright, "Team", if (preview) "Platform crew" else null, D.dim, onClick = { state.push(MobileRoute.Team) })
            MoreRow("auto_awesome", D.amber, "AI & privacy", "Local", D.dim, onClick = null)
            MoreRow("palette", D.cyanBright, "Appearance", "Night Sea", D.dim, onClick = null)
            MoreRow("shield_lock", D.cyanBright, "Security & sync", if (preview) "Synced" else "Local only", D.dim, onClick = null)
            MoreRow("lock", D.sunset, "Lock Skerry", null, D.dim, labelColor = D.sunset, divider = false, onClick = onLock)
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun portsSubtitle(): String {
    val active = LocalSessions.current?.active ?: return mobileMorePortsSubtitle(null)
    val controller = active.controller
    if (controller.uiState !is ConnectionUiState.Connected) return mobileMorePortsSubtitle(null)
    // runCatching: между проверкой uiState и openPortForwards() сессия могла отключиться с другого
    // экрана (connection стал null → error()) — на гонке закрытия трактуем как «нет данных».
    val count = runCatching { mobileActiveTunnelCount(controller.openPortForwards().forwards) }.getOrNull()
    return mobileMorePortsSubtitle(count)
}

@Composable
private fun knownChanged(): Int = LocalKnownHosts.current?.mismatches?.size ?: 0

@Composable
private fun knownSubtitle(): String = mobileMoreKnownSubtitle(knownChanged())

/**
 * Строка раздела хаба: ведущая иконка + название + подпись справа + chevron. [onClick] == null —
 * инертная строка (раздел вне текущего слайса/MVP, как в макете без onclick). [divider] — нижняя
 * линия (нет у последней строки).
 */
@Composable
private fun MoreRow(
    icon: String,
    iconColor: Color,
    label: String,
    subtitle: String?,
    subtitleColor: Color,
    labelColor: Color = D.text,
    divider: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) {
        base.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        base
    }
    Row(
        clickable.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Sym(icon, size = 21.sp, color = iconColor)
        Txt(label, color = labelColor, size = 14.5.sp, modifier = Modifier.weight(1f))
        if (!subtitle.isNullOrEmpty()) Txt(subtitle, color = subtitleColor, size = 11.sp)
        if (onClick != null && labelColor != D.sunset) Sym("chevron_right", size = 20.sp, color = D.faint)
    }
    if (divider) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(D.cyan.copy(alpha = 0.05f)))
}

// ──────────────────────────────────────── профиль ────────────────────────────────────────

/**
 * Живая карточка профиля: аккаунт/sync — Phase 2, поэтому показываем честную локальную сущность
 * (зашифрованный мастер-паролем vault на этом устройстве), без выдуманных имени/почты/PRO макета.
 */
@Composable
private fun LocalVaultCard() {
    ProfileCard(initials = "S", avatarBg = D.cyan, title = "Local vault", subtitle = "Encrypted on this device", badge = null)
}

/** Статичная карточка профиля ровно из макета (превью/офскрин) — для сверки 1:1. */
@Composable
private fun MockProfileCard() {
    ProfileCard(initials = "MK", avatarBg = D.cyan, title = "Maya Kovac", subtitle = "maya@skerry.dev", badge = "PRO")
}

@Composable
private fun ProfileCard(initials: String, avatarBg: Color, title: String, subtitle: String, badge: String?) {
    Row(
        Modifier
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(avatarBg), contentAlignment = Alignment.Center) {
            Txt(initials, color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
            Txt(subtitle, color = D.dim, size = 12.sp)
        }
        if (badge != null) {
            Badge(badge, bg = D.amber.copy(alpha = 0.14f), fg = D.amber, radius = 20, size = 9.5.sp)
        }
    }
}
