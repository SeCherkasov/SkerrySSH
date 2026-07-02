package app.skerry.ui.mobile

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_host_address
import app.skerry.ui.generated.resources.shtail_host_ask_on_connect
import app.skerry.ui.generated.resources.shtail_host_auth
import app.skerry.ui.generated.resources.shtail_host_group
import app.skerry.ui.generated.resources.shtail_host_port
import app.skerry.ui.generated.resources.shtail_host_saved_credential
import app.skerry.ui.host.ungroupedLabel
import app.skerry.ui.generated.resources.shell_host
import app.skerry.ui.generated.resources.shell_host_not_found
import app.skerry.ui.generated.resources.shell_connect
import app.skerry.ui.generated.resources.shell_quick_sftp
import app.skerry.ui.generated.resources.shell_quick_tunnels
import app.skerry.ui.generated.resources.shell_quick_snippets
import app.skerry.ui.generated.resources.shell_details
import app.skerry.ui.generated.resources.shell_edit_host
import app.skerry.ui.generated.resources.shell_delete_host
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalOpenSftp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label

/** Коралл экрана детали (Delete host) — `#E07A5F` из мока; в палитре `D` отдельного токена нет. */
private val DetailCoral = Color(0xFFE07A5F)
private val DetailCoralBorder = Color(0x40E07A5F)

/** Строка карточки Details: подпись, значение и моноширинность значения (адрес/порт — моно). */
@Immutable
data class HostDetailRow(val label: String, val value: String, val mono: Boolean)

/**
 * Свести профиль [Host] к строкам карточки Details: Address, Port, Auth, Group. Только живые поля —
 * Auth отражает наличие привязанного keychain-секрета (`Saved credential` / `Ask on connect`), Group
 * падает в «Ungrouped». AI-политика и онлайн-статус из макета здесь отсутствуют (нет в модели).
 */
@Composable
fun mobileHostDetailRows(host: Host): List<HostDetailRow> = listOf(
    HostDetailRow(stringResource(Res.string.shtail_host_address), host.address, mono = true),
    HostDetailRow(stringResource(Res.string.shtail_host_port), host.port.toString(), mono = true),
    HostDetailRow(
        stringResource(Res.string.shtail_host_auth),
        if (host.credentialId != null) stringResource(Res.string.shtail_host_saved_credential) else stringResource(Res.string.shtail_host_ask_on_connect),
        mono = false,
    ),
    HostDetailRow(stringResource(Res.string.shtail_host_group), host.group?.takeIf { it.isNotBlank() } ?: ungroupedLabel(), mono = false),
)

/**
 * Полноэкранная деталь хоста (push поверх Hosts). Профиль берётся из живого [LocalHosts] по
 * [MobileDesignState.selectedHostId] (или превью-каталог вне гейта). Connect — переход на
 * [MobileRoute.Terminal]; Tunnels — на [MobileRoute.Ports]; Delete удаляет профиль через
 * [app.skerry.ui.host.HostManagerController] и возвращает к списку. SFTP подключается к хосту и
 * ведёт на таб Files; Snippets/edit — заглушка (без действия).
 */
@Composable
fun MobileHostDetailScreen(state: MobileDesignState) {
    val controller = LocalHosts.current
    val id = state.selectedHostId
    val host = id?.let { controller?.find(it) ?: MOBILE_PREVIEW_HOSTS.firstOrNull { h -> h.id == it } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                    onClick = state::pop,
                ),
            )
            Txt(stringResource(Res.string.shell_host), color = D.text, size = 18.sp, weight = FontWeight.Bold)
        }

        if (host == null) {
            Txt(
                stringResource(Res.string.shell_host_not_found),
                color = D.faint,
                size = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            return@Column
        }

        // Идентичность: плашка-иконка, имя, user@address. Онлайн-статус макета опущен (нет в модели).
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(62.dp).clip(RoundedCornerShape(18.dp)).background(D.cyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Sym("dns", size = 32.sp, color = D.cyanBright)
            }
            Spacer(Modifier.height(12.dp))
            Txt(host.label, color = D.text, size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Txt(
                "${host.username}@${host.address}",
                color = D.dim,
                size = 12.5.sp,
                font = LocalFonts.current.mono,
            )
        }

        // Connect: открыть живую сессию через LocalConnectHost (резолвит identity или спрашивает
        // пароль) и перейти на push-экран терминала; в превью/вне гейта это no-op.
        val connect = LocalConnectHost.current
        Box(Modifier.padding(horizontal = 22.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(D.cyan)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { connect(host) },
                    )
                    .padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Sym("terminal", size = 21.sp, color = Color(0xFF0A1A26))
                Txt(stringResource(Res.string.shell_connect), color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
            }
        }

        // Быстрые действия: SFTP подключается к хосту (как Connect, с резолвом секрета/паролем) и
        // ведёт сразу на таб Files — Remote-браузер активной сессии.
        // Tunnels — на Ports; Snippets — не реализован.
        val openSftp = LocalOpenSftp.current
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            QuickAction("folder", stringResource(Res.string.shell_quick_sftp), Modifier.weight(1f), onClick = { openSftp(host) })
            QuickAction("lan", stringResource(Res.string.shell_quick_tunnels), Modifier.weight(1f), onClick = { state.push(MobileRoute.Ports) })
            QuickAction("code_blocks", stringResource(Res.string.shell_quick_snippets), Modifier.weight(1f), onClick = null)
        }

        HostsDetailLabel(stringResource(Res.string.shell_details))
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0x08FFFFFF))
                .border(1.dp, D.cyan.copy(alpha = 0.07f), RoundedCornerShape(13.dp)),
        ) {
            val rows = mobileHostDetailRows(host)
            rows.forEachIndexed { i, row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(row.label, color = D.dim, size = 13.sp)
                    Txt(
                        row.value,
                        color = D.text,
                        size = 13.sp,
                        font = if (row.mono) LocalFonts.current.mono else null,
                    )
                }
                if (i < rows.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(D.cyan.copy(alpha = 0.05f)))
                }
            }
        }

        // Edit host: открыть лист New connection в режиме правки этого профиля (вне гейта — недоступно,
        // сохранять некуда). Delete: удалить профиль из живого каталога и вернуться к списку.
        Column(
            Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (controller != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.dp, D.cyan.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { state.openEditConn(host) },
                        )
                        .padding(13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(stringResource(Res.string.shell_edit_host), color = D.cyanBright, size = 14.sp, weight = FontWeight.Medium)
                }
            }
            val onDelete = controller?.let { ctrl -> { ctrl.delete(host.id); state.pop() } }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .border(1.dp, DetailCoralBorder, RoundedCornerShape(13.dp))
                    .then(if (onDelete != null) Modifier.clickable(onClick = onDelete) else Modifier)
                    .padding(13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.shell_delete_host), color = DetailCoral, size = 14.sp, weight = FontWeight.Medium)
            }
        }
    }
}

/** Карточка быстрого действия (иконка над подписью). [onClick]==null — действие отложено (без ripple). */
@Composable
private fun QuickAction(icon: String, label: String, modifier: Modifier, onClick: (() -> Unit)?) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, D.cyan08, RoundedCornerShape(13.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym(icon, size = 22.sp, color = D.cyanBright)
        Txt(label, color = D.dim, size = 11.sp)
    }
}

/** Капс-подпись секции на экране детали (Details). */
@Composable
private fun HostsDetailLabel(name: String) {
    Txt(
        name.uppercase(),
        color = D.faint,
        size = 11.5.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 6.dp),
    )
}
