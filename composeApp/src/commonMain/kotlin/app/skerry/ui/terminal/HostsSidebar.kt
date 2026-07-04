package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalRunSnippetOnHost
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTeams
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.team.TeamMemberStatus
import androidx.compose.runtime.collectAsState
import app.skerry.ui.generated.resources.lib_teams_sidebar
import app.skerry.ui.design.Badge
import app.skerry.ui.design.Chip
import app.skerry.ui.design.D
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_hosts_section
import app.skerry.ui.generated.resources.term_menu_delete
import app.skerry.ui.generated.resources.term_menu_edit
import app.skerry.ui.generated.resources.term_menu_run_snippet
import app.skerry.ui.generated.resources.term_new_connection
import app.skerry.ui.generated.resources.term_no_hosts_match
import app.skerry.ui.generated.resources.term_recent_section
import app.skerry.ui.generated.resources.term_search_hosts_placeholder
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.host.HOST_GROUPS
import app.skerry.ui.host.HostDragState
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostGroup
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.MockHost
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.draggableFolderHeader
import app.skerry.ui.host.draggableHostRow
import app.skerry.ui.host.filterHosts
import app.skerry.ui.host.folderHeaderAnchor
import app.skerry.ui.host.folderRangeAnchor
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.host.hostBoundsAnchor
import app.skerry.ui.host.hostChipLabel
import app.skerry.ui.host.hostTagChips
import app.skerry.ui.session.sessionDotColor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// Сайдбар хостов терминального view: поиск, тег-фильтры, папки каталога (живой drag-and-drop
// или мок-превью), секция RECENT и кнопка «New connection».

@Composable
internal fun HostsSidebar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val liveHosts = LocalHosts.current
    // Состояние ручной сортировки (drag-and-drop) живого каталога; в мок-пути не используется.
    val dragState = remember { HostDragState() }
    // Активный фильтр-чип (тег). Только для живого каталога; в мок-пути чипсы статичны.
    var activeChip by remember { mutableStateOf(ALL_HOSTS_CHIP) }
    val chips = liveHosts?.let { remember(it.hosts) { hostTagChips(it.hosts) } } ?: emptyList()
    // Если активный тег исчез (хост отредактирован/удалён), фильтр откатывается к «All» — не зависает на пустом.
    val effectiveChip = if (activeChip in chips) activeChip else ALL_HOSTS_CHIP
    Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            HostSearchField(state, mono)
            // Лента тегов-фильтров уезжает за край (узкий сайдбар) — прокручиваем по горизонтали. На
            // desktop вертикальное колесо мыши само в горизонталь не переводится, поэтому ловим Scroll
            // и крутим [chipScroll] вручную (delta.y, а если ось горизонтальная — delta.x); на тач/Android
            // прокрутка работает обычным drag через horizontalScroll.
            val chipScroll = rememberScrollState()
            val chipScope = rememberCoroutineScope()
            Row(
                Modifier
                    .padding(top = 9.dp)
                    .horizontalScroll(chipScroll)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll) continue
                                val d = event.changes.firstOrNull()?.scrollDelta ?: continue
                                val delta = if (d.y != 0f) d.y else d.x
                                if (delta != 0f) {
                                    chipScope.launch { chipScroll.scrollBy(delta * 64f) }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (liveHosts != null) {
                    // Чипсы = теги живого каталога; клик переключает фильтр.
                    chips.forEach { chip ->
                        key(chip) {
                            Chip(hostChipLabel(chip), active = chip == effectiveChip, onClick = { activeChip = chip })
                        }
                    }
                } else {
                    Chip("All", active = true)
                    Chip("#prod"); Chip("#docker"); Chip("#db")
                }
            }
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(stringResource(Res.string.term_hosts_section), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                // Создать новую (пока пустую) группу — в живом каталоге; в мок-пути иконка декоративна.
                if (liveHosts != null) {
                    IconBtn("create_new_folder", onClick = state::openCreateGroup, box = 20, icon = 14.sp, tint = D.faint)
                } else {
                    Sym("create_new_folder", size = 14.sp, color = D.faint)
                }
            }
            // Живой каталог из HostManagerController, если подан (за гейтом vault); иначе мок-данные
            // (путь офскрин-рендера/превью). Папки — по группам, сузив активным тег-чипом.
            if (liveHosts != null) {
                val query = state.hostSearchQuery
                val folders = remember(liveHosts.hosts, effectiveChip, query, state.customGroups) {
                    val base = groupHostsByFolder(filterHosts(liveHosts.hosts, effectiveChip, query))
                    // Пустые пользовательские группы показываем как папки без хостов — но только вне
                    // фильтра (поиск/тег сужают по хостам, а пустой папке там нечем совпасть).
                    if (query.isNotBlank() || effectiveChip != ALL_HOSTS_CHIP) {
                        base
                    } else {
                        val present = base.map { it.name }.toSet()
                        base + state.customGroups.filter { it !in present }.map { HostFolder(it, emptyList()) }
                    }
                }
                // Сужение поиском/тегом ничего не нашло → подсказка вместо немой пустоты (в отличие от
                // пустого каталога, где ниже всё равно покажется секция RECENT/кнопка New connection).
                if (folders.isEmpty() && (query.isNotBlank() || effectiveChip != ALL_HOSTS_CHIP)) {
                    Txt(
                        stringResource(Res.string.term_no_hosts_match),
                        color = D.faint, size = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
                // Свежий список папок для drag-целей (жест фиксируется по ключу строки/папки).
                val foldersUpdated = rememberUpdatedState(folders)
                // Линия вставки при перетаскивании папки: перед папкой на целевом индексе (или в конце).
                val otherFolders = folders.filter { it.name != dragState.draggingFolderName }
                val folderLineIndex = dragState.draggingFolderName?.let { dragState.activeFolderDropIndex }
                val folderLineBefore = folderLineIndex?.takeIf { it < otherFolders.size }?.let { otherFolders[it].name }
                folders.forEach { folder ->
                    key(folder.name) {
                        if (folder.name == folderLineBefore) DropLine()
                        LiveHostFolder(folder, state, mono, dragState, liveHosts) { foldersUpdated.value }
                    }
                }
                if (folderLineIndex != null && folderLineIndex == otherFolders.size) DropLine()
                // Общие хосты команд (Teams): секции по командам под личным каталогом. Показываются
                // только вне поиска/фильтра — фильтры сужают личный каталог.
                if (query.isBlank() && effectiveChip == ALL_HOSTS_CHIP) {
                    TeamHostsSection(liveHosts.hosts, mono)
                }
            } else {
                HOST_GROUPS.forEach { group -> HostGroupBlock(group, state, mono) }
            }
            // Живой каталог: секция RECENT из реальной истории подключений ([DesktopDesignState.recentHostIds]),
            // резолвится в текущие профили — удалённые/неизвестные id просто скрыты. Пусто → секции нет.
            // Мок/превью (нет живого каталога): статичная строка.
            if (liveHosts != null) {
                // Секцию можно целиком скрыть (Settings → Appearance → Interface) и ограничить её размер.
                // Мемоизируем по (порядок недавних, состав каталога, лимит) — как соседний `folders`: иначе
                // резолв пересчитывался бы на каждой рекомпозиции сайдбара (drag/смена чипа/таба).
                val recent = remember(state.recentHostIds, liveHosts.hosts, state.recentLimit) {
                    state.recentHostIds.mapNotNull { liveHosts.find(it) }.take(state.recentLimit)
                }
                if (state.showRecent && recent.isNotEmpty()) {
                    RecentSectionHeader()
                    recent.forEach { host -> key(host.id) { RecentHostRow(host, mono) } }
                }
            } else {
                RecentSectionHeader()
                Row(
                    Modifier.padding(start = 16.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("history", size = 14.sp, color = D.faint)
                    Txt("user@vps.example.com", color = D.dim, size = 11.5.sp, font = mono)
                }
            }
        }
        HLine()
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            PrimaryButton(stringResource(Res.string.term_new_connection), onClick = state::openModal, icon = "add_link", modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Поле поиска по сайдбару хостов (имя/адрес/пользователь/группа/теги). Рамка/иконка/плейсхолдер —
 * в decorationBox, чтобы клик по всей площади ставил каретку (см. правило для рукописных полей).
 * Пока пусто — справа бейдж `⌘K`; при вводе он сменяется крестиком очистки.
 */
@Composable
private fun HostSearchField(state: DesktopDesignState, mono: FontFamily) {
    val query = state.hostSearchQuery
    BasicTextField(
        value = query,
        onValueChange = state::onHostSearch,
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(D.card)
                    .border(1.dp, D.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Txt(stringResource(Res.string.term_search_hosts_placeholder), color = D.faint, size = 12.5.sp)
                    inner()
                }
                if (query.isEmpty()) {
                    Box(Modifier.border(1.dp, D.cyan14, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Txt("⌘K", color = D.faint, size = 10.sp, font = mono)
                    }
                } else {
                    val onClear = remember(state) { { state.onHostSearch("") } }
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("close", size = 14.sp, color = D.faint)
                    }
                }
            }
        },
    )
}

/**
 * Заголовок папки хостов: шеврон-кнопка свёртки + иконка + имя + счётчик. Шеврон ([collapsed] →
 * `chevron_right`, иначе `expand_more`) кликабелен и переключает свёрнутость папки ([onToggle]) —
 * клик ловится строго на иконке, чтобы не мешать drag-перетаскиванию заголовка (reorder папок).
 */
@Composable
private fun FolderHeader(name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit, onEdit: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = D.faint)
        }
        Sym("folder_open", size = 15.sp, color = D.cyanBright)
        Txt(name, color = D.dim, size = 12.5.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        // Переименовать/удалить группу (живой каталог; не для синтетического «Ungrouped»).
        if (onEdit != null) IconBtn("edit", onClick = onEdit, box = 20, icon = 13.sp, tint = D.faint)
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = D.faint, size = 10.sp)
        }
    }
}

/** Заголовок секции RECENT в сайдбаре (общий для живого и мок-пути). */
@Composable
private fun RecentSectionHeader() {
    Txt(
        stringResource(Res.string.term_recent_section),
        color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Строка недавнего подключения: иконка истории + имя хоста ([Host.label]) с `user@address` вторичной
 * подписью под ним — иначе по голому `root@192.168.0.1` непонятно, какой именованный хост это (адреса
 * повторяются/безлики). Клик переподключает к хосту через [LocalConnectHost] — тот же путь, что клик
 * по строке в каталоге.
 */
@Composable
private fun TeamHostsSectionHeader() {
    Txt(
        stringResource(Res.string.lib_teams_sidebar),
        color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * Секции общих хостов команд под личным каталогом: по секции на активную команду с доехавшим
 * ключом, у которой в team-vault есть хосты. Хосты читаются из per-team vault'а; перечитка
 * привязана к ([hostsSnapshot], список команд) — после team-синка reloadManagers обновляет
 * каталог, меняя снапшот. Клик — тот же [LocalConnectHost] (секрет спросится: ссылки на
 * credential при шеринге стрипаются).
 */
@Composable
private fun TeamHostsSection(hostsSnapshot: List<Host>, mono: FontFamily) {
    val teams = LocalTeams.current ?: return
    val teamList by teams.teams.collectAsState()
    val sections = remember(teamList, hostsSnapshot) {
        teamList.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }.mapNotNull { team ->
            val vault = teams.teamVault(team.id) ?: return@mapNotNull null
            val shared = VaultHostStore(vault).all()
            if (shared.isEmpty()) null else team.name to shared
        }
    }
    if (sections.isEmpty()) return
    TeamHostsSectionHeader()
    sections.forEach { (name, shared) ->
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Sym("group", size = 14.sp, color = D.cyanBright)
            Txt(name, color = D.dim, size = 11.5.sp, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        shared.forEach { host -> key("team-${host.id}") { TeamHostRow(host, mono) } }
    }
}

/** Строка общего хоста команды — как [RecentHostRow], но с иконкой шеринга. */
@Composable
private fun TeamHostRow(host: Host, mono: FontFamily) {
    val connect = LocalConnectHost.current
    val onClick = remember(host, connect) { { connect(host) } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("lan", size = 14.sp, color = D.faint)
        Column(Modifier.weight(1f)) {
            Txt(host.label, color = D.dim, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt("${host.username}@${host.address}", color = D.faint, size = 10.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecentHostRow(host: Host, mono: FontFamily) {
    val connect = LocalConnectHost.current
    // Стабилизируем лямбду по (host, connect) — как строки каталога: без remember она пересоздавалась
    // бы на каждой рекомпозиции строки.
    val onClick = remember(host, connect) { { connect(host) } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("history", size = 14.sp, color = D.faint)
        Column(Modifier.weight(1f)) {
            Txt(
                host.label,
                color = D.dim, size = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Txt(
                "${host.username}@${host.address}",
                color = D.faint, size = 10.5.sp, font = mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HostGroupBlock(group: HostGroup, state: DesktopDesignState, mono: FontFamily) {
    val collapsed = state.isGroupCollapsed(group.name)
    val onToggleCollapsed = remember(state, group.name) { { state.toggleGroupCollapsed(group.name) } }
    Column(Modifier.padding(bottom = 2.dp)) {
        FolderHeader(group.name, group.hosts.size, collapsed, onToggleCollapsed)
        if (!collapsed) {
            Column(Modifier.padding(start = 22.dp)) {
                group.hosts.forEach { host -> HostRow(host, state, mono) }
            }
        }
    }
}

/**
 * Живая папка каталога: те же визуалы, но из [HostFolder] поверх [HostManagerController]. Клик по
 * хосту запускает подключение ([LocalConnectHost]); точка статуса и подсветка берутся из живых
 * сессий ([LocalSessions]) — хост подсвечен, если он у активной вкладки, цвет точки = состояние
 * соединения свежайшей его сессии.
 *
 * Ручная сортировка ([dragState]): заголовок папки перетаскивается для смены порядка папок, строки
 * хостов — для переупорядочивания внутри папки и переноса в другую (см. [HostSidebarDnd]). Сброс
 * фиксируется через [controller]; [foldersProvider] отдаёт свежий список папок на момент жеста.
 */
@Composable
private fun LiveHostFolder(
    folder: HostFolder,
    state: DesktopDesignState,
    mono: FontFamily,
    dragState: HostDragState,
    controller: HostManagerController,
    foldersProvider: () -> List<HostFolder>,
) {
    val sessions = LocalSessions.current
    val connect = LocalConnectHost.current
    // Ключ группы папки: у пустой берём её имя (как FolderBounds), иначе группу первого хоста.
    // Синтетический «Ungrouped» — это null-группа.
    val group = folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL }
    val collapsed = state.isGroupCollapsed(folder.name)
    // Лямбду свёртки стабилизируем по (state, имя папки) — как и прочие лямбды строк ниже: иначе при
    // каждой рекомпозиции папки (а во время любого drag это каждый кадр) заголовок перерисовывался бы.
    val onToggleCollapsed = remember(state, folder.name) { { state.toggleGroupCollapsed(folder.name) } }
    // Карандаш правки в заголовке — кроме синтетической корзины «Ungrouped» (её не переименовать).
    val onEditGroup = if (folder.name == UNGROUPED_LABEL) null
        else remember(state, folder.name) { { state.openRenameGroup(folder.name) } }
    // Подсветка целевой папки, пока над ней тащат хост.
    val isDropTarget = dragState.draggingHostId != null && dragState.activeHostDrop?.group == group
    val folderAlpha = if (dragState.draggingFolderName == folder.name) 0.4f else 1f
    // Линия вставки внутри папки: индекс считается без перетаскиваемого хоста (как moveHostToGroup),
    // поэтому к визуальным строкам он привязывается по соседям из того же отфильтрованного списка.
    val others = folder.hosts.filter { it.id != dragState.draggingHostId }
    val dropIndex = if (isDropTarget) dragState.activeHostDrop?.index?.coerceIn(0, others.size) else null
    val lineBeforeId = dropIndex?.takeIf { it < others.size }?.let { others[it].id }
    Column(
        Modifier
            .padding(bottom = 2.dp)
            .alpha(folderAlpha)
            .clip(RoundedCornerShape(6.dp))
            // После clip — bounds совпадают с видимой (скруглённой) областью папки, а не с её углами.
            .folderRangeAnchor(dragState, folder.name)
            .border(1.dp, if (isDropTarget) D.cyan else Color.Transparent, RoundedCornerShape(6.dp)),
    ) {
        Box(
            Modifier.folderHeaderAnchor(dragState, folder.name)
                .draggableFolderHeader(dragState, folder.name, foldersProvider) { index ->
                    controller.moveFolder(group, index)
                },
        ) {
            FolderHeader(folder.name, folder.hosts.size, collapsed, onToggleCollapsed, onEditGroup)
        }
        // Свёрнутая папка показывает только заголовок; список хостов (и его drag-цели) скрыт.
        if (!collapsed) Column(Modifier.padding(start = 22.dp)) {
            // key(host.id): позиционная идентичность строк фиксируется на хосте — открытое меню/состояние
            // строки не «переезжает» на соседа при переупорядочивании каталога после правки.
            folder.hosts.forEach { host ->
                key(host.id) {
                    if (host.id == lineBeforeId) DropLine()
                    // Лямбды стабилизируем по (host, …): иначе каждая рекомпозиция папки пересоздавала бы
                    // их и заставляла строку (nullable-функции нестабильны) перерисовываться впустую.
                    val onClick = remember(host, connect) { { connect(host) } }
                    val onEdit = remember(host, state) { { state.openEditModal(host) } }
                    val onDelete = remember(host, state) { { state.requestDeleteHost(host) } }
                    // Забываем геометрию строки, когда хост уходит из списка (удаление/фильтр).
                    DisposableEffect(host.id) { onDispose { dragState.clearHostBounds(host.id) } }
                    Box(
                        Modifier
                            .alpha(if (dragState.draggingHostId == host.id) 0.4f else 1f)
                            .hostBoundsAnchor(dragState, host.id)
                            .draggableHostRow(dragState, host.id, foldersProvider) { drop ->
                                controller.moveHost(host.id, drop.group, drop.index)
                            },
                    ) {
                        HostEntryRow(
                            label = host.label,
                            // Подсветку «хост активной вкладки» убрали: при split
                            // активных хостов два — подсветка одного вводила бы в заблуждение. Статус
                            // живого соединения по-прежнему показывает точка справа.
                            selected = false,
                            dot = sessionDotColor(sessions?.statusFor(host.id)),
                            badge = null,
                            onClick = onClick,
                            mono = mono,
                            // Объект хоста — для пункта «Run snippet…» (запуск сниппета на этом хосте).
                            host = host,
                            // Правка/удаление профиля — через контекстное меню (right-click/long-press),
                            // без отдельных кнопок/⋮.
                            onEdit = onEdit,
                            onDelete = onDelete,
                        )
                    }
                }
            }
            // Сброс в конец папки — линия после последней строки.
            if (dropIndex != null && dropIndex == others.size) DropLine()
        }
    }
}

/** Cyan-линия-индикатор позиции, куда вставится перетаскиваемый хост/папка. */
@Composable
private fun DropLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(D.cyan),
    )
}

@Composable
private fun HostRow(host: MockHost, state: DesktopDesignState, mono: FontFamily) {
    HostEntryRow(
        label = host.name,
        selected = state.selectedHost == host.name,
        dot = host.status.color,
        badge = host.badge,
        onClick = { state.selectHost(host.name) },
        mono = mono,
    )
}

/**
 * Общая строка хоста в сайдбаре (мок и живой каталог): точка статуса + имя + опц. бейдж. Клик по
 * строке — подключение ([onClick]). Когда переданы [onEdit]/[onDelete] (живой каталог) или доступен
 * запуск сниппета на хосте ([host] != null + [LocalSnippets] подан), в конце строки появляется кнопка
 * «⋮», открывающая выпадающее меню (Run snippet…/Edit/Delete); её собственный клик перехватывается
 * раньше [onClick], поэтому открытие меню не запускает подключение. «Run snippet…» открывает палитру
 * выбора сниппета и выполняет его на [host] через [LocalRunSnippetOnHost].
 */
@Composable
private fun HostEntryRow(
    label: String,
    selected: Boolean,
    dot: Color,
    badge: String?,
    onClick: () -> Unit,
    mono: FontFamily,
    host: Host? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val snippets = LocalSnippets.current
    val runSnippetOnHost = LocalRunSnippetOnHost.current
    val canRunSnippet = host != null && snippets != null
    val hasMenu = onEdit != null || onDelete != null || canRunSnippet
    var menuOpen by remember { mutableStateOf(false) }
    var snippetPickerOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        Txt(label, color = if (selected) D.cyanBright else D.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
        if (badge != null) {
            val strict = badge == "STRICT"
            Badge(badge, bg = if (strict) D.strictBg else D.devBg, fg = if (strict) D.strictFg else D.moss)
        }
        if (hasMenu) {
            Box {
                IconBtn("more_vert", onClick = { menuOpen = !menuOpen }, box = 22, icon = 16.sp, tint = D.faint)
                if (menuOpen) {
                    Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                        Column(
                            Modifier.clip(RoundedCornerShape(7.dp)).background(D.surface2).border(1.dp, D.lineStrong, RoundedCornerShape(7.dp)).padding(4.dp),
                        ) {
                            if (canRunSnippet) {
                                HostMenuItem(stringResource(Res.string.term_menu_run_snippet), D.text) { menuOpen = false; snippetPickerOpen = true }
                            }
                            onEdit?.let { edit ->
                                HostMenuItem(stringResource(Res.string.term_menu_edit), D.text) { menuOpen = false; edit() }
                            }
                            onDelete?.let { delete ->
                                HostMenuItem(stringResource(Res.string.term_menu_delete), D.sunset) { menuOpen = false; delete() }
                            }
                        }
                    }
                }
                // Палитра выбора сниппета: запуск на этом хосте (открывает/использует сессию и
                // выполняет команду после подключения). Пустая библиотека показывает «No snippets yet».
                if (snippetPickerOpen && host != null && snippets != null) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        onDismissRequest = { snippetPickerOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        SnippetPalette(snippets) { entry ->
                            runSnippetOnHost(host, entry.snippet.command)
                            snippetPickerOpen = false
                        }
                    }
                }
            }
        }
    }
}

/** Пункт контекстного меню строки хоста. */
@Composable
private fun HostMenuItem(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Txt(label, color = color, size = 12.sp)
    }
}
