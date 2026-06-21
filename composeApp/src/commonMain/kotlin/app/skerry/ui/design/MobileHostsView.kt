package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host

/** Превью-каталог для пути без живого [LocalHosts] (офскрин/превью) — состав и группы из макета. */
private val MOBILE_PREVIEW_HOSTS = listOf(
    Host("p1", "prod-web-01", "192.168.1.45", 22, "root", "Production"),
    Host("p2", "db-master", "192.168.1.50", 22, "root", "Production"),
    Host("p3", "homelab-pi", "10.0.0.12", 22, "pi", "Homelab"),
    Host("p4", "nas-truenas", "10.0.0.20", 22, "admin", "Homelab"),
)

/**
 * Корневой экран таба Hosts мобильного макета `Skerry Mobile.html`, 1:1 с шаблоном: шапка с
 * заголовком и аватаром (→ More), строка поиска, лента фильтр-чипов групп, секции хостов и FAB
 * «новое подключение». Каталог — живой [LocalHosts] (за гейтом vault), либо [MOBILE_PREVIEW_HOSTS]
 * на пути превью. Тап по хосту открывает [MobileRoute.HostDetail] (наполняется слайсом 2B); статус
 * соединения (живая точка) подключается со слайсом терминала.
 */
@Composable
fun MobileHostsScreen(state: MobileDesignState) {
    val controller = LocalHosts.current
    val hosts = controller?.hosts ?: MOBILE_PREVIEW_HOSTS
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf(ALL_HOSTS_CHIP) }
    val list = remember(hosts, query, chip) { buildMobileHostList(hosts, query, chip) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HostsHeader(onAvatar = { state.select(MobileTab.More) })
            HostsSearch(query, onChange = { query = it })
            HostsChips(list.chips, active = chip, onSelect = { chip = it })
            Spacer(Modifier.height(2.dp))
            list.sections.forEach { folder ->
                HostsSectionLabel(folder.name)
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    folder.hosts.forEach { host ->
                        MobileHostRow(host, onClick = { state.openHost(host.id) })
                    }
                }
            }
            Spacer(Modifier.height(96.dp)) // место под таб-бар и FAB
        }
        HostsFab(
            onClick = state::openNewConn,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp),
        )
    }
}

/** Шапка: «Hosts» (28sp) + круглый аватар-аккаунт справа (ведёт на таб More). */
@Composable
private fun HostsHeader(onAvatar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt("Hosts", color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(D.cyan).clickable(onClick = onAvatar),
            contentAlignment = Alignment.Center,
        ) {
            Sym("person", size = 19.sp, color = Color(0xFF0A1A26))
        }
    }
}

/** Строка поиска по имени/адресу/пользователю/группе хоста. */
@Composable
private fun HostsSearch(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0x0DFFFFFF))
            .border(1.dp, D.cyan08, RoundedCornerShape(11.dp))
            .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("search", size = 19.sp, color = D.faint)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Txt("Search hosts, tags…", color = D.faint, size = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = D.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
                cursorBrush = SolidColor(D.cyan),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Лента фильтр-чипов: «All» + группы; активный подсвечен cyan. Горизонтальный скролл, как в макете. */
@Composable
private fun HostsChips(chips: List<String>, active: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        chips.forEach { chip ->
            key(chip) {
                val on = chip == active
                val onClick = remember(chip) { { onSelect(chip) } }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) D.cyan.copy(alpha = 0.14f) else Color(0x0DFFFFFF))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                        .padding(horizontal = 13.dp, vertical = 5.dp),
                ) {
                    Txt(
                        chip,
                        color = if (on) D.cyanBright else D.dim,
                        size = 12.5.sp,
                        weight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Заголовок секции-папки: капс, разрядка, приглушённый цвет. */
@Composable
private fun HostsSectionLabel(name: String) {
    Txt(
        name.uppercase(),
        color = D.faint,
        size = 12.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * Строка хоста: иконка-плашка + имя + `user@address` моноширинно + точка статуса. Иконка и точка
 * статуса в живой модели общие (нет per-host иконки/AI-политики/онлайна) — плашка `dns`, точка
 * нейтральная до подключения живых сессий (слайс терминала).
 */
@Composable
private fun MobileHostRow(host: Host, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(D.cyan.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Sym("dns", size = 21.sp, color = D.cyanBright)
        }
        Column(Modifier.weight(1f)) {
            Txt(host.label, color = D.text, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Txt(
                "${host.username}@${host.address}",
                color = D.dim,
                size = 11.5.sp,
                font = LocalFonts.current.mono,
                maxLines = 1,
            )
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(D.faint))
    }
}

/** Плавающая кнопка добавления подключения (открывает лист New connection). */
@Composable
private fun HostsFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(D.cyan)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Sym("add", size = 28.sp, color = Color(0xFF0A1A26))
    }
}
