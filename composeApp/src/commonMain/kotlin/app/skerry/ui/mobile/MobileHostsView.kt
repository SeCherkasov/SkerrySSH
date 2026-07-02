package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.collectAsState
import app.skerry.ui.sync.SyncIndicatorLevel
import app.skerry.ui.sync.syncIndicatorLocalized
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.ungroupedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_hosts
import app.skerry.ui.generated.resources.shell_search_hosts
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.design.D
import app.skerry.ui.host.HostDragState
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileTab
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.host.folderHeaderAnchor
import app.skerry.ui.host.folderRangeAnchor
import app.skerry.ui.host.hostBoundsAnchor
import app.skerry.ui.host.hostChipLabel
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.host.draggableFolderHeader
import app.skerry.ui.host.draggableHostRow

/** Превью-каталог для пути без живого [LocalHosts] (офскрин/превью). */
internal val MOBILE_PREVIEW_HOSTS = listOf(
    Host("p1", "prod-web-01", "192.168.1.45", 22, "root", "Production"),
    Host("p2", "db-master", "192.168.1.50", 22, "root", "Production"),
    Host("p3", "homelab-pi", "10.0.0.12", 22, "pi", "Homelab"),
    Host("p4", "nas-truenas", "10.0.0.20", 22, "admin", "Homelab"),
)

/**
 * Корневой экран таба Hosts: шапка с заголовком и аватаром (→ More), строка поиска, лента
 * фильтр-чипсов по тегам, секции хостов и FAB «новое подключение». Каталог — живой [LocalHosts]
 * (за гейтом vault), либо [MOBILE_PREVIEW_HOSTS] на пути превью. Тап по хосту открывает
 * [MobileRoute.HostDetail].
 */
@Composable
fun MobileHostsScreen(state: MobileDesignState) {
    val controller = LocalHosts.current
    val hosts = controller?.hosts ?: MOBILE_PREVIEW_HOSTS
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf(ALL_HOSTS_CHIP) }
    val list = remember(hosts, query, chip) { buildMobileHostList(hosts, query, chip) }
    // Состояние ручной сортировки (тач-DnD): жест отдаёт цель, перестановку фиксирует контроллер.
    // Общее ядро с desktop ([HostDragState] + чистая геометрия [hostDropTarget]/[folderDropTarget]).
    val dragState = remember { HostDragState() }
    // Свежий список папок для drag-целей: жест читает его на момент отпускания (а не на старте).
    val foldersUpdated = rememberUpdatedState(list.sections)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HostsHeader(onAvatar = { state.select(MobileTab.More) })
            HostsSearch(query, onChange = { query = it })
            HostsChips(list.chips, active = chip, onSelect = { chip = it })
            Spacer(Modifier.height(2.dp))
            // Линия вставки при перетаскивании папки: перед папкой на целевом индексе (или в конце).
            val otherFolders = list.sections.filter { it.name != dragState.draggingFolderName }
            val folderLineIndex = dragState.draggingFolderName?.let { dragState.activeFolderDropIndex }
            val folderLineBefore = folderLineIndex?.takeIf { it < otherFolders.size }?.let { otherFolders[it].name }
            list.sections.forEach { folder ->
                key(folder.name) {
                    if (folder.name == folderLineBefore) MobileDropLine()
                    MobileHostFolder(folder, state, controller, dragState) { foldersUpdated.value }
                }
            }
            if (folderLineIndex != null && folderLineIndex == otherFolders.size) MobileDropLine()
            Spacer(Modifier.height(96.dp)) // место под таб-бар и FAB
        }
        HostsFab(
            onClick = state::openNewConn,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp),
        )
    }
}

/**
 * Папка хостов: заголовок со схлопыванием (шеврон) + список строк, оба с drag-перетаскиванием для
 * ручной сортировки. Drag и линии-вставки активны только в живом каталоге ([controller] != null) —
 * в превью (мок-хосты) сортировать нечего и нечем (стор отсутствует). Свёрнутая папка скрывает
 * список хостов (и их drag-цели).
 */
@Composable
private fun MobileHostFolder(
    folder: HostFolder,
    state: MobileDesignState,
    controller: HostManagerController?,
    dragState: HostDragState,
    foldersProvider: () -> List<HostFolder>,
) {
    // Ключ группы папки: у пустой берём её имя (как FolderBounds), иначе группу первого хоста.
    // Синтетический «Ungrouped» — это null-группа.
    val group = folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL }
    val collapsed = state.isGroupCollapsed(folder.name)
    val onToggle = remember(state, folder.name) { { state.toggleGroupCollapsed(folder.name) } }
    // Карандаш правки в заголовке — только в живом каталоге и кроме синтетической корзины «Ungrouped»
    // (её не переименовать). Паритет desktop-`LiveHostFolder`. remember безусловный (стабильная
    // позиция в слот-таблице), видимость карандаша даёт takeIf.
    val onEdit = remember(state, folder.name) { { state.openRenameGroup(folder.name) } }
        .takeIf { controller != null && folder.name != UNGROUPED_LABEL }
    // Подсветка целевой папки, пока над ней тащат хост.
    val isDropTarget = dragState.draggingHostId != null && dragState.activeHostDrop?.group == group
    val folderAlpha = if (dragState.draggingFolderName == folder.name) 0.4f else 1f
    // Линия вставки внутри папки: индекс — без перетаскиваемого хоста (как moveHostToGroup).
    val others = folder.hosts.filter { it.id != dragState.draggingHostId }
    val dropIndex = if (isDropTarget) dragState.activeHostDrop?.index?.coerceIn(0, others.size) else null
    val lineBeforeId = dropIndex?.takeIf { it < others.size }?.let { others[it].id }
    Column(
        Modifier
            .alpha(folderAlpha)
            .let { if (controller != null) it.folderRangeAnchor(dragState, folder.name) else it },
    ) {
        val headerMod = if (controller != null) {
            Modifier
                .folderHeaderAnchor(dragState, folder.name)
                .draggableFolderHeader(dragState, folder.name, foldersProvider, longPress = true) { index ->
                    controller.moveFolder(group, index)
                }
        } else {
            Modifier
        }
        Box(headerMod) {
            // folder.name — стабильный ключ (drag/collapse); для корзины без группы показываем
            // локализованную подпись, оставляя ключ техническим ([UNGROUPED_LABEL]).
            val folderTitle = if (folder.name == UNGROUPED_LABEL) ungroupedLabel() else folder.name
            MobileFolderHeader(folderTitle, folder.hosts.size, collapsed, isDropTarget, onToggle, onEdit)
        }
        // Свёрнутая папка показывает только заголовок; список хостов (и его drag-цели) скрыт.
        if (!collapsed) {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                folder.hosts.forEach { host ->
                    key(host.id) {
                        if (host.id == lineBeforeId) MobileDropLine(horizontal = 0.dp)
                        // Забываем геометрию строки, когда хост уходит из списка (перенос/фильтр).
                        // clearHostBounds безопасен и без drag (map.remove) → эффект безусловный, как desktop.
                        DisposableEffect(host.id) { onDispose { dragState.clearHostBounds(host.id) } }
                        // Лямбду открытия стабилизируем: каждый кадр drag меняет draggingHostId/activeHostDrop
                        // и перерисовывает папку — без remember лямбда пересоздавалась бы и дёргала строку.
                        val onOpen = remember(host.id, state) { { state.openHost(host.id) } }
                        val rowMod = if (controller != null) {
                            Modifier
                                .alpha(if (dragState.draggingHostId == host.id) 0.4f else 1f)
                                .hostBoundsAnchor(dragState, host.id)
                                .draggableHostRow(dragState, host.id, foldersProvider, longPress = true) { drop ->
                                    controller.moveHost(host.id, drop.group, drop.index)
                                }
                        } else {
                            Modifier
                        }
                        Box(rowMod) {
                            MobileHostRow(host, onClick = onOpen)
                        }
                    }
                }
                // Сброс в конец папки — линия после последней строки.
                if (dropIndex != null && dropIndex == others.size) MobileDropLine(horizontal = 0.dp)
            }
        }
    }
}

/**
 * Cyan-линия-индикатор позиции, куда вставится перетаскиваемый хост/папка (паритет desktop).
 * [horizontal] — боковой отступ: 18dp на уровне папок (внешняя колонка без отступа), 0dp внутри
 * колонки хостов (она уже даёт `padding(horizontal = 18.dp)`, иначе линия была бы вдвое у́же строк).
 */
@Composable
private fun MobileDropLine(horizontal: Dp = 18.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontal, vertical = 3.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(D.cyan),
    )
}

/** Шапка: «Hosts» (28sp) + круглый аватар-аккаунт справа (ведёт на таб More). */
@Composable
private fun HostsHeader(onAvatar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(stringResource(Res.string.shell_hosts), color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Индикатор sync по статусу сессии (см. syncIndicator), не только по доступности сервера:
            // «paused/error» при отсутствии рабочей сессии, а не ложно-зелёный online.
            val syncC = LocalSync.current
            val ind = syncC?.let { syncIndicatorLocalized(it.status.collectAsState().value, it.serverReachable.collectAsState().value) }
            if (ind != null) {
                Sym(ind.icon, size = 19.sp, color = when (ind.level) {
                    SyncIndicatorLevel.OK -> D.moss
                    SyncIndicatorLevel.WARN -> D.amber
                    SyncIndicatorLevel.ERROR -> D.sunset
                })
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(D.cyan).clickable(onClick = onAvatar),
                contentAlignment = Alignment.Center,
            ) {
                Sym("person", size = 19.sp, color = Color(0xFF0A1A26))
            }
        }
    }
}

/** Строка поиска по имени/адресу/пользователю/группе хоста. */
@Composable
private fun HostsSearch(query: String, onChange: (String) -> Unit) {
    // Внешний отступ — на обёртке; рамка — в decorationBox, чтобы клик по всей площади ставил каретку.
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 6.dp),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0x0DFFFFFF))
                    .border(1.dp, D.cyan08, RoundedCornerShape(11.dp))
                    .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("search", size = 19.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Txt(stringResource(Res.string.shell_search_hosts), color = D.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}

/** Лента фильтр-чипсов: «All» + теги (с префиксом `#`); активный подсвечен cyan. Горизонтальный скролл. */
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
                        hostChipLabel(chip),
                        color = if (on) D.cyanBright else D.dim,
                        size = 12.5.sp,
                        weight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Заголовок секции-папки: шеврон свёртки + капс-имя + (карандаш правки) + счётчик хостов. Клик по
 * шеврону переключает свёрнутость, клик по карандашу открывает диалог Rename/Delete группы — хит-зоны
 * строго на иконках ([onToggle]/[onEdit]), чтобы тапы не конфликтовали с drag-перетаскиванием
 * заголовка (reorder папок), как на desktop. [dropTarget] подсвечивает капс-имя при сбросе хоста сюда.
 * [onEdit] == null для синтетического «Ungrouped» и пути превью (карандаш скрыт).
 */
@Composable
private fun MobileFolderHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    dropTarget: Boolean,
    onToggle: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = D.faint)
        }
        Txt(
            name.uppercase(),
            color = if (dropTarget) D.cyanBright else D.faint,
            size = 12.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        if (onEdit != null) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onEdit,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Sym("edit", size = 14.sp, color = D.faint)
            }
        }
        Spacer(Modifier.weight(1f))
        Txt(count.toString(), color = D.faint, size = 11.sp)
    }
}

/**
 * Строка хоста: иконка-плашка + имя + `user@address` моноширинно + точка статуса. Плашка `dns`
 * общая (нет per-host иконки/AI-политики), а цвет точки — живой: берётся из состояния самой свежей
 * сессии хоста ([SessionsController.statusFor]) через общий с desktop [sessionDotColor] (подключено —
 * зелёный, connect — янтарный, ошибка/обрыв — закатный, нет сессии — приглушённый). Чтение uiState
 * внутри композиции подписывает строку на смену статуса — точка обновляется по факту коннекта.
 */
@Composable
private fun MobileHostRow(host: Host, onClick: () -> Unit) {
    val dotColor = sessionDotColor(LocalSessions.current?.statusFor(host.id))
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
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
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
