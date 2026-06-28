package app.skerry.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import app.skerry.ui.known.KnownHostsController

/** Фон строки доверенного ключа (белый 3%). */
private val KnownRowBg = Color(0x08FFFFFF)

/**
 * Push-экран Known hosts: шапка-назад + баннеры смены ключа (Accept/Reject прямо в баннере — на
 * телефоне нет боковой панели сравнения отпечатков desktop-`KnownHostsView`) + список доверенных
 * ключей. Поверх живого [KnownHostsController] ([LocalKnownHosts]): баннеры из `mismatches`, строки
 * из `entries`, статус Verified/Changed. Забыть ключ — long-press → Forget (прячем в контекстное
 * меню). Без контроллера (превью/офскрин) — статичная заглушка.
 */
@Composable
fun MobileKnownScreen(state: MobileDesignState) {
    val mono = LocalFonts.current.mono
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobileKnownHeader(onBack = state::pop)
        when (val controller = LocalKnownHosts.current) {
            null -> MockMobileKnownBody(mono)
            else -> LiveMobileKnownBody(controller, mono)
        }
    }
}

/** Шапка push-экрана: chevron_left (назад) + «Known hosts». */
@Composable
private fun MobileKnownHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Sym(
            "chevron_left",
            size = 27.sp,
            color = D.cyanBright,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        )
        Txt("Known hosts", color = D.text, size = 18.sp, weight = FontWeight.Bold)
    }
}

// Живой путь.

/**
 * Живое тело экрана поверх [KnownHostsController]: баннеры по каждому незакрытому событию смены ключа
 * (Accept → принять новый ключ, Reject → отклонить, оставив прежний доверенным) + список доверенных
 * ключей со статусом. После Accept/Reject контроллер перечитывает сторы — баннер исчезает, строка
 * возвращается в Verified.
 */
@Composable
private fun LiveMobileKnownBody(controller: KnownHostsController, mono: FontFamily) {
    // Экран мог открыться после того, как реконнект записал новый ключ в общий стор — перечитываем.
    LaunchedEffect(Unit) { controller.refresh() }
    val mismatches = controller.mismatches
    val entries = controller.entries
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        mismatches.forEach { mismatch ->
            // key по идентичности: forEach переиспользует слоты позиционно — без ключа состояние
            // баннера «переехало» бы при снятии соседнего события.
            key(mismatch.host, mismatch.port, mismatch.keyType) {
                // Стабилизируем обработчики (mismatch — data class, валидный ключ remember), чтобы
                // forEach не пересоздавал лямбды баннера на каждой рекомпозиции тела.
                val onAccept = remember(mismatch) { { controller.acceptNewKey(mismatch) } }
                val onReject = remember(mismatch) { { controller.reject(mismatch) } }
                MobileMismatchBanner(
                    title = mobileKnownBannerTitle(mismatch),
                    body = mobileKnownBannerBody(mismatch),
                    onAccept = onAccept,
                    onReject = onReject,
                )
            }
        }
        if (entries.isEmpty()) {
            MobileEmptyKnown()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    val host = entry.host
                    key(host.host, host.port, host.keyType) {
                        // entry — @Immutable data class, валидный ключ remember для стабильного onForget.
                        val onForget = remember(entry) { { controller.forget(entry) } }
                        LiveKnownRow(entry, mono, onForget = onForget)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** Живая строка доверенного ключа: имя + подпись (тип·отпечаток / тип·changed) + иконка статуса; long-press → лист Forget. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveKnownRow(entry: KnownHostEntry, mono: FontFamily, onForget: () -> Unit) {
    var menuOpen by remember(entry.host.host, entry.host.port, entry.host.keyType) { mutableStateOf(false) }
    KnownRowContent(
        name = entry.host.host,
        subtitle = mobileKnownSubtitle(entry),
        status = entry.status,
        mono = mono,
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
    )
    if (menuOpen) {
        MobileActionSheet(
            title = entry.host.host,
            subtitle = mobileKnownSubtitle(entry),
            actions = listOf(
                MobileSheetAction("Forget key", onClick = onForget, icon = "delete", danger = true),
            ),
            onDismiss = { menuOpen = false },
        )
    }
}

/** Пустое состояние: доверенных ключей ещё нет (первый коннект запишет ключ по TOFU). */
@Composable
private fun MobileEmptyKnown() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("fingerprint", size = 28.sp, color = D.faint)
            Txt("No known hosts yet", color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt("Connect a host — its key is trusted on first use", color = D.faint, size = 12.sp)
        }
    }
}

// Общие куски разметки.

/**
 * Баннер смены ключа: коралловая карточка, заголовок с иконкой gpp_maybe, тело и две кнопки
 * Accept/Reject во всю ширину. [onAccept]/[onReject] == no-op в мок-пути (превью/офскрин).
 */
@Composable
private fun MobileMismatchBanner(title: String, body: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(D.sunset.copy(alpha = 0.05f))
            .border(1.dp, D.sunset.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("gpp_maybe", size = 19.sp, color = D.sunset)
            Txt(title, color = D.sunset, size = 13.5.sp, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Txt(body, color = D.dim, size = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BannerButton(
                label = "Accept",
                fg = D.text,
                bg = Color.Transparent,
                border = D.cyan14,
                bold = false,
                onClick = onAccept,
                modifier = Modifier.weight(1f),
            )
            BannerButton(
                label = "Reject",
                fg = D.sunset,
                bg = D.sunset.copy(alpha = 0.12f),
                border = D.sunset.copy(alpha = 0.3f),
                bold = true,
                onClick = onReject,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BannerButton(
    label: String,
    fg: Color,
    bg: Color,
    border: Color,
    bold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = fg, size = 12.5.sp, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Карточка-строка доверенного ключа: имя (mono) + подпись (mono) + иконка статуса; коралловый тон у сменившегося. */
@Composable
private fun KnownRowContent(
    name: String,
    subtitle: String,
    status: KnownHostStatus,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    val changed = status == KnownHostStatus.Changed
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (changed) D.sunset.copy(alpha = 0.05f) else KnownRowBg)
            .border(1.dp, if (changed) D.sunset.copy(alpha = 0.2f) else D.cyan08, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(name, color = D.text, size = 13.sp, font = mono)
            Txt(subtitle, color = if (changed) D.sunset else D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 2.dp))
        }
        Sym(mobileKnownStatusIcon(status), size = 15.sp, color = if (changed) D.sunset else D.moss)
    }
}

// Мок (превью/офскрин).

private data class MockKnownHost(val name: String, val subtitle: String, val status: KnownHostStatus)

/** Статичные строки для превью/офскрин. */
private val MOCK_KNOWN = listOf(
    MockKnownHost("prod-web-01", "ed25519 · 8c3F1a…pK9R", KnownHostStatus.Verified),
    MockKnownHost("db-master", "ed25519 · 2dE7b…wQ1z", KnownHostStatus.Verified),
    MockKnownHost("nas-truenas", "ed25519 · changed", KnownHostStatus.Changed),
)

/** Мок-тело (превью/офскрин): баннер смены ключа + статичные строки. */
@Composable
private fun MockMobileKnownBody(mono: FontFamily) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        MobileMismatchBanner(
            title = "Key changed: nas-truenas",
            body = "Fingerprint differs from Mar 4. Verify before reconnecting.",
            onAccept = {},
            onReject = {},
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MOCK_KNOWN.forEach { KnownRowContent(it.name, it.subtitle, it.status, mono) }
        }
        Spacer(Modifier.height(30.dp))
    }
}
