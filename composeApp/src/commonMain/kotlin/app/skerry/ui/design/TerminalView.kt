package app.skerry.ui.design

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.host.Host
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.ai.TerminalAiController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.shortCipher
import app.skerry.ui.terminal.TerminalScreenState
import kotlin.math.roundToInt
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.metrics.HostMetrics
import app.skerry.ui.metrics.formatUptime
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.terminal.TerminalScreen

/** Общая высота шапки панели (основной и split) — чтобы заголовки были вровень. */
private val PANE_HEADER_HEIGHT = 40.dp

/** Терминальный view: hosts-sidebar + main (toolbar, панели, AI-bar) + info-panel. */
@Composable
fun TerminalView(state: DesktopDesignState) {
    Row(Modifier.fillMaxSize()) {
        HostsSidebar(state)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Живой режим: split привязан к активной вкладке (своя вторичная сессия). Мок/превью —
                // глобальный флаг.
                val sessions = LocalSessions.current
                val activeId = sessions?.active?.id
                val showSplit = if (sessions != null) sessions.active?.splitOpen == true else state.split
                // В режиме split акцентная рамка (primary cyan) обводит сфокусированную панель — явно
                // показывает, с какой панелью идёт работа. focusedSplit=false → основная.
                val focusedSplit = sessions?.active?.focusedSplit == true
                fun paneFocusBorder(focused: Boolean): Modifier =
                    if (showSplit && focused) Modifier.border(1.dp, D.cyan.copy(alpha = 0.35f)) else Modifier
                // Пока split открыт — клик по основной панели возвращает ей фокус (заголовок чипа).
                val primaryMod = Modifier.weight(1f).fillMaxHeight()
                    .then(if (sessions != null && activeId != null && showSplit) Modifier.focusPaneOnPress(sessions, activeId, split = false) else Modifier)
                    .then(paneFocusBorder(!focusedSplit))
                // Основная панель = свой заголовок (тулбар) + терминал, симметрично split-панели: обе
                // шапки на одном уровне, без «перекоса».
                Column(primaryMod) {
                    SessionToolbar(state)
                    TerminalPane(state, Modifier.weight(1f).fillMaxWidth())
                }
                if (showSplit) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(D.cyan14))
                    val splitMod = Modifier.weight(1f).then(paneFocusBorder(focusedSplit))
                    if (sessions != null) LiveSplitPane(sessions, state, splitMod) else SplitPane(splitMod)
                }
                if (state.infoPanel) InfoPanel()
            }
            // AI-бар терминала: при живом AI-контроллере виден под per-host политикой (Off — скрыт);
            // в чистом дизайн-превью (только фича-флаг, без контроллера) — прежний декоративный мок.
            TerminalAiBarSlot()
        }
    }
}

// Сайдбар хостов.

@Composable
private fun HostsSidebar(state: DesktopDesignState) {
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
                Txt("HOSTS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
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
                        "No hosts match",
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
            } else {
                HOST_GROUPS.forEach { group -> HostGroupBlock(group, state, mono) }
            }
            // Живой каталог: секция RECENT из реальной истории подключений ([DesktopDesignState.recentHostIds]),
            // резолвится в текущие профили — удалённые/неизвестные id просто скрыты. Пусто → секции нет.
            // Мок/превью (нет живого каталога): статичная строка.
            if (liveHosts != null) {
                // Мемоизируем по (порядок недавних, состав каталога) — как соседний `folders`: иначе
                // резолв пересчитывался бы на каждой рекомпозиции сайдбара (drag/смена чипа/таба).
                val recent = remember(state.recentHostIds, liveHosts.hosts) {
                    state.recentHostIds.mapNotNull { liveHosts.find(it) }
                }
                if (recent.isNotEmpty()) {
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
            PrimaryButton("New connection", onClick = state::openModal, icon = "add_link", modifier = Modifier.fillMaxWidth())
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
                    .background(Color(0x08FFFFFF))
                    .border(1.dp, D.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Txt("Search hosts, tags, IPs…", color = D.faint, size = 12.5.sp)
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
        "RECENT",
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
                                HostMenuItem("Run snippet…", D.text) { menuOpen = false; snippetPickerOpen = true }
                            }
                            onEdit?.let { edit ->
                                HostMenuItem("Edit", D.text) { menuOpen = false; edit() }
                            }
                            onDelete?.let { delete ->
                                HostMenuItem("Delete", D.sunset) { menuOpen = false; delete() }
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

// Тулбар сессии.

@Composable
private fun SessionToolbar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    Column {
        Row(
            // Фиксированная высота шапки — общая со split-панелью (PANE_HEADER_HEIGHT), чтобы обе шапки
            // были вровень независимо от контента (слева крупнее из-за кнопок-иконок).
            Modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT).background(D.surface2).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active != null) {
                    // Живой заголовок: ярлык хоста + user@addr:port + точка состояния соединения.
                    // Зазоры/паддинги синхронизированы со split-шапкой (LiveSplitPane), чтобы обе панели
                    // выглядели одинаково.
                    Txt(active.title, color = D.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                    Txt(active.subtitle, color = D.dim, size = 11.5.sp, font = mono)
                    Dot(sessionDotColor(active.controller.uiState))
                } else if (sessions != null) {
                    // Живой режим без активной сессии: честное пустое состояние, без фейкового хоста.
                    Txt("No active session", color = D.faint, size = 12.sp, font = mono)
                } else {
                    // Мок/превью (офскрин-рендер без LocalSessions): статичный заголовок.
                    Txt("root@prod-web-01", color = D.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("192.168.1.45:22", color = D.dim, size = 11.5.sp)
                        Txt(" · ", color = D.faint, size = 11.5.sp)
                        Txt("●", color = D.moss, size = 11.5.sp)
                        Txt(" 04:12:45", color = D.faint, size = 11.5.sp)
                    }
                    Txt("SSHv2 · aes256-gcm · ed25519", color = D.faint, size = 11.5.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Split: живой режим переключает split АКТИВНОЙ вкладки (своя вторичная сессия);
                // мок/превью — глобальный флаг.
                IconBtn("splitscreen_right", onClick = { if (sessions != null) sessions.toggleSplit() else state.toggleSplit() })
                // Переключают подвью АКТИВНОЙ вкладки (живой режим, + сброс оверлея) / мок-фолбэк state.view.
                IconBtn("folder", onClick = { if (sessions != null) { state.clearOverlay(); sessions.setActiveView(SessionView.Sftp) } else state.showView(DesktopView.Sftp) })
                // Tunnels — глобальный раздел, всегда открывается оверлеем.
                IconBtn("lan", onClick = { state.showView(DesktopView.Ports) })
                // Быстрый запуск сниппета в активную сессию без ухода в раздел Snippets.
                SnippetPaletteButton(active)
                IconBtn("info", onClick = state::toggleInfo)
                // Power: рвёт активную сессию (живой путь) — спрашиваем подтверждение (деструктивно, без
                // авто-реконнекта); в мок-режиме — no-op заглушка.
                IconBtn("power_settings_new", onClick = { if (active != null) state.requestCloseSession(active.id) }, tint = D.sunset)
            }
        }
        HLine()
    }
}

// Палитра сниппетов: быстрый запуск сохранённой команды в активный терминал прямо из тулбара.

@Composable
private fun SnippetPaletteButton(active: Session?) {
    val manager = LocalSnippets.current
    val terminal = (active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    // Ключ active: при переключении вкладок палитра не должна оставаться открытой над чужим тулбаром.
    var open by remember(active) { mutableStateOf(false) }
    if (manager == null) return
    Box {
        // Без подключённой сессии бежать некуда — кнопка приглушена и не открывается.
        IconBtn("bolt", onClick = { if (terminal != null) open = !open }, tint = if (terminal != null) D.dim else D.faint)
        if (open && terminal != null) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SnippetPalette(manager) { entry ->
                    manager.run(entry.id) { text -> terminal.send(text) }
                    open = false
                }
            }
        }
    }
}

@Composable
private fun SnippetPalette(manager: SnippetManager, onPick: (SnippetEntry) -> Unit) {
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    val all = manager.snippets
    val filtered = if (query.isBlank()) all else all.filter { it.matches(query) }
    // Автофокус строки поиска при открытии — палитра задумана для запуска с клавиатуры.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Column(
        Modifier.width(320.dp).clip(RoundedCornerShape(9.dp)).background(D.surface2).border(1.dp, D.lineStrong, RoundedCornerShape(9.dp)).padding(6.dp),
    ) {
        val style = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym("search", size = 15.sp, color = D.faint)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Txt("Run a snippet…", color = D.faint, size = 12.5.sp, font = mono)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = style, cursorBrush = SolidColor(D.cyan), modifier = Modifier.fillMaxWidth().focusRequester(searchFocus))
            }
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (filtered.isEmpty()) {
                Txt(if (all.isEmpty()) "No snippets yet" else "No matches", color = D.faint, size = 11.5.sp, font = mono, modifier = Modifier.padding(8.dp))
            } else {
                filtered.forEach { entry -> key(entry.id) { PaletteRow(entry, mono) { onPick(entry) } } }
            }
        }
    }
}

@Composable
private fun PaletteRow(entry: SnippetEntry, mono: FontFamily, onClick: () -> Unit) {
    val s = entry.snippet
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym("code_blocks", size = 14.sp, color = D.dim)
            Txt(s.label.ifBlank { "Untitled" }, color = D.textBright, size = 12.5.sp, weight = FontWeight.Medium)
            if (!s.shortcut.isNullOrBlank()) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(D.bg).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Txt(s.shortcut!!, color = D.faint, size = 10.sp, font = mono)
                }
            }
        }
        Txt(s.command, color = D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
    }
}

// Терминальная панель.

/**
 * Терминальная область: живая ([LocalSessions] подан, за гейтом vault) или мок-демо.
 * Живой путь рендерит реальную сетку активной сессии через готовый [TerminalScreen] (VT-эмулятор
 * + ввод в PTV) или экран-плейсхолдер для прочих состояний соединения.
 */
@Composable
private fun TerminalPane(state: DesktopDesignState, modifier: Modifier = Modifier) {
    val sessions = LocalSessions.current
    if (sessions != null) LiveTerminalPane(sessions, modifier) else MockTerminalPane(state, modifier)
}

/** Живой терминал активной вкладки: рендер по состоянию её [ConnectionUiState]. */
@Composable
private fun LiveTerminalPane(sessions: SessionsController, modifier: Modifier = Modifier) {
    val active = sessions.active
    Box(modifier.fillMaxHeight().fillMaxWidth().background(D.terminalBg)) {
        when (val st = active?.controller?.uiState) {
            null -> TerminalNotice("terminal", "No active session", "Pick a host from the sidebar to connect.")
            // Form у активной вкладки = пустой таб («+»): соединение ещё не запускалось.
            ConnectionUiState.Form -> TerminalNotice("terminal", "Not connected", "Pick a host from the sidebar or start a New connection.")
            ConnectionUiState.Connecting -> TerminalNotice("sync", "Connecting…", active.subtitle)
            is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
            is ConnectionUiState.Error -> TerminalNotice("error", "Connection failed", st.message, color = D.sunset)
            // Обрыв: экран застыл на момент потери ([ConnectionUiState.Disconnected.terminal]) — показываем
            // его под баннером разрыва, чтобы вывод не пропал, а статус (реконнект/сдача) был ясен.
            is ConnectionUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                TerminalScreen(st.terminal, Modifier.fillMaxSize())
                DisconnectedBanner(st, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/**
 * Плашка-индикатор закрытия поверх застывшего терминала. Штатный выход shell (`exit`) — нейтральная
 * «Session closed»; пока идёт авто-реконнект — янтарная «Reconnecting… #N»; когда попытки исчерпаны —
 * закатная «Connection lost».
 */
@Composable
private fun DisconnectedBanner(state: ConnectionUiState.Disconnected, modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    val color = when {
        state.cleanExit -> D.dim
        state.reconnecting -> D.amber
        else -> D.sunset
    }
    val icon = when {
        state.cleanExit -> "power_settings_new"
        state.reconnecting -> "sync"
        else -> "link_off"
    }
    val text = when {
        state.cleanExit -> "Session closed"
        state.reconnecting -> "Reconnecting… #${state.attempt}"
        else -> "Connection lost"
    }
    Row(
        modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC1A0E0E))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Sym(icon, size = 14.sp, color = color)
        Txt(text, color = color, size = 11.5.sp, font = mono)
    }
}

/** Центрированное сообщение на фоне терминала (нет сессии / подключение / ошибка). */
@Composable
private fun TerminalNotice(icon: String, title: String, subtitle: String, color: Color = D.dim) {
    val mono = LocalFonts.current.mono
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(icon, size = 30.sp, color = color)
        Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Txt(subtitle, color = D.faint, size = 12.sp, font = mono)
    }
}

/** Демо-терминал (мок-путь без живых сессий: офскрин-рендер/превью). */
@Composable
private fun MockTerminalPane(state: DesktopDesignState, modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    Column(modifier.fillMaxHeight().background(D.terminalBg)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Txt("Last login: Sat Jun 21 14:22:10 2026 from 10.0.0.15", color = D.faint, size = 13.sp, font = mono, lineHeight = 20.sp)
            Prompt(mono, "df -h")
            TermOut("Filesystem      Size  Used Avail Use% Mounted on", mono)
            Row {
                Txt("/dev/sda1        50G   42G  ", color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
                Txt("5.2G   87%", color = D.sunset, size = 13.sp, font = mono, lineHeight = 20.sp)
                Txt(" /", color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
            }
            TermOut("/dev/sda2       200G  120G   75G  62% /var", mono)
            TermOut("tmpfs           4.0G  1.2M  4.0G   1% /tmp", mono)
            Prompt(mono, "tail -f /var/log/nginx/access.log")
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:01] \"GET /api/v1/status HTTP/1.1\" ", "200", " 154", D.moss)
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:05] \"POST /api/v1/telemetry HTTP/1.1\" ", "201", " 12", D.moss)
            TermOut("127.0.0.1 - - [21/Jun/2026:14:25:12] \"GET /admin/config HTTP/1.1\" 403 89", mono, color = D.sunset)
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:15] \"GET /assets/main.css HTTP/1.1\" ", "200", " 4521", D.moss)
            TermOut("127.0.0.1 - - [21/Jun/2026:14:25:22] \"POST /api/auth HTTP/1.1\" 500 245", mono, color = D.storm)

            // AI-карточка — фича MVP2 за фича-флагом; в MVP1 (дефолт) её в выводе нет.
            if (LocalFeatures.current.ai) AiSuggestionCard()

            state.termLines.forEach { line ->
                if (line.isCmd) Prompt(mono, line.text) else TermOut(line.text, mono, color = line.color)
            }

            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PromptLabel(mono)
                BasicTextField(
                    value = state.cmd,
                    onValueChange = state::onCmd,
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    textStyle = TextStyle(color = D.white, fontSize = 13.sp, fontFamily = mono),
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.runCmd() }),
                    decorationBox = { inner ->
                        if (state.cmd.isEmpty()) {
                            Txt("type a command — try: ls, df -h, help", color = D.faint, size = 13.sp, font = mono)
                        }
                        inner()
                    },
                )
            }
        }
    }
}

@Composable
private fun PromptLabel(mono: FontFamily) {
    Row {
        Txt("root@prod-web-01", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
        Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
        Txt("~", color = D.moss, size = 13.sp, font = mono)
        Txt("#", color = D.cyanBright, size = 13.sp, font = mono)
    }
}

@Composable
private fun Prompt(mono: FontFamily, cmd: String) {
    Row {
        PromptLabel(mono)
        Txt(" $cmd", color = D.white, size = 13.sp, font = mono, lineHeight = 20.sp)
    }
}

@Composable
private fun TermOut(text: String, mono: FontFamily, color: Color = D.textMid) {
    Txt(text, color = color, size = 13.sp, font = mono, lineHeight = 20.sp)
}

@Composable
private fun LogLine(mono: FontFamily, head: String, code: String, tail: String, codeColor: Color) {
    Row {
        Txt(head, color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
        Txt(code, color = codeColor, size = 13.sp, font = mono, lineHeight = 20.sp)
        Txt(tail, color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
    }
}

// AI-карточка-подсказка.

@Composable
private fun AiSuggestionCard() {
    val mono = LocalFonts.current.mono
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(D.sunset.copy(alpha = 0.04f))
            .border(1.dp, D.amber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym("auto_awesome", size = 15.sp, color = D.sunset)
            Txt("AI SUGGESTION (UNTRUSTED LOG SOURCE)", color = D.sunset, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
            Txt("Qwen 2.5 Coder · local", color = D.faint, size = 10.sp, font = mono)
        }
        HLine(D.sunset.copy(alpha = 0.2f))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Txt("I see a 500 error on POST /api/auth and disk is at 87%. To investigate the auth error, check the application error log:", color = D.dim, size = 12.sp, lineHeight = 18.sp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x4D000000))
                    .border(1.dp, D.cyan14, RoundedCornerShape(5.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Txt("tail -n 50 /var/log/nginx/error.log | grep -A 5 \"auth\"", color = D.text, size = 12.5.sp, font = mono)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(D.sunset.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym("warning", size = 14.sp, color = D.sunset)
                Txt("This analysis used untrusted log content. Inspect the command carefully — log entries can be crafted to manipulate AI output.", color = D.sunset, size = 11.5.sp, lineHeight = 16.sp)
            }
        }
        HLine(D.amber.copy(alpha = 0.15f))
        Row(
            Modifier.fillMaxWidth().background(Color(0x26000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(D.amber).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Insert into terminal", color = Color(0xFF0A1A26), size = 11.5.sp, weight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, D.cyan14, RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Explain", color = D.text, size = 11.5.sp, weight = FontWeight.Medium)
            }
            Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, D.cyan14, RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Dismiss", color = D.dim, size = 11.5.sp, weight = FontWeight.Medium)
            }
        }
    }
}

// Split-панель.

/**
 * Живая split-панель: вторая НЕЗАВИСИМАЯ сессия активной вкладки
 * ([Session.splitSession], своё соединение/терминал/выделение). Шапка показывает её хост и крестик
 * закрытия ([SessionsController.closeSplit]); пока хост не выбран — пикер каталога ([SplitHostPicker]),
 * выбор подключает новую сессию через [LocalConnectSplit]. Клик по телу фокусирует split-панель
 * (заголовок чипа вкладки следует за фокусом).
 */
@Composable
private fun LiveSplitPane(sessions: SessionsController, state: DesktopDesignState, modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    val parent = sessions.active ?: return
    var pickerOpen by remember { mutableStateOf(false) }
    val split = parent.splitSession
    Column(
        modifier.fillMaxHeight().background(D.terminalBg)
            .focusPaneOnPress(sessions, parent.id, split = true),
    ) {
        Box(Modifier.fillMaxWidth().background(D.surface2)) {
            Row(
                Modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Заголовок-селектор как у основной панели: клик раскрывает пикер каталога — выбрать
                // хост (пусто) или ЗАМЕНИТЬ текущий (connectSplit рвёт прежнюю вторичную сессию).
                // Зазоры/паддинги совпадают с SessionToolbar — панели выглядят одинаково.
                Row(
                    Modifier.weight(1f).clickable { pickerOpen = !pickerOpen },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (split != null) {
                        Txt(split.title, color = D.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                        Txt(split.subtitle, color = D.dim, size = 11.5.sp, font = mono)
                        Dot(sessionDotColor(split.controller.uiState))
                        Spacer(Modifier.weight(1f))
                    } else {
                        Txt("Select a host…", color = D.faint, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
                    }
                    Sym(if (pickerOpen) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
                }
                // Крестик в шапке закрывает split этой вкладки (рвёт вторичное соединение) —
                // подтверждаем только когда есть что рвать (хост уже выбран); пустую панель закрываем сразу.
                IconBtn(
                    "close",
                    onClick = { if (split != null) state.requestCloseSplit(parent.id) else sessions.closeSplit(parent.id) },
                    box = 22,
                )
            }
            if (pickerOpen) {
                Popup(alignment = Alignment.BottomStart, onDismissRequest = { pickerOpen = false }) {
                    SplitHostPicker { pickerOpen = false }
                }
            }
        }
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = split?.controller?.uiState) {
                null -> TerminalNotice("splitscreen_right", "No host selected", "Pick a host to open it side by side.")
                ConnectionUiState.Form -> TerminalNotice("terminal", "Session closed", split.subtitle)
                ConnectionUiState.Connecting -> TerminalNotice("sync", "Connecting…", split.subtitle)
                is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
                is ConnectionUiState.Error -> TerminalNotice("error", "Connection failed", st.message, color = D.sunset)
                is ConnectionUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                    TerminalScreen(st.terminal, Modifier.fillMaxSize())
                    DisconnectedBanner(st, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/**
 * Пикер хостов из каталога ([LocalHosts]) для split-панели: клик по хосту открывает в ней новую
 * независимую сессию через [LocalConnectSplit] (тот же путь резолва секрета, что и у основного
 * подключения). Вне гейта vault (нет живого каталога) — пусто.
 */
@Composable
private fun SplitHostPicker(onPicked: () -> Unit) {
    val mono = LocalFonts.current.mono
    val hosts = LocalHosts.current?.hosts ?: emptyList()
    val connectSplit = LocalConnectSplit.current
    Column(
        Modifier
            .width(240.dp)
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(D.surface2)
            .border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        if (hosts.isEmpty()) {
            Txt("No hosts in catalog", color = D.faint, size = 11.5.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        hosts.forEach { host ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { connectSplit(host); onPicked() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("dns", size = 14.sp, color = D.cyanBright)
                Txt(host.label, color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Перехват нажатия в [PointerEventPass.Initial] (НЕ потребляя событие): фокусирует панель [split]
 * вкладки [parentId], чтобы заголовок чипа следовал за активной панелью. Клавиатуру маршрутизирует
 * сам [TerminalScreen] (свой focusRequester на pointer-down).
 */
private fun Modifier.focusPaneOnPress(sessions: SessionsController, parentId: String, split: Boolean): Modifier =
    this.pointerInput(sessions, parentId, split) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) sessions.focusPane(parentId, split)
            }
        }
    }

@Composable
private fun SplitPane(modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    Column(modifier.fillMaxHeight().background(D.terminalBg)) {
        Box(Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Txt("root@db-master · 192.168.1.50", color = D.dim, size = 11.sp, font = mono)
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row {
                Txt("root@db-master", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
                Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("~", color = D.moss, size = 13.sp, font = mono)
                Txt("# ", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("systemctl status postgresql", color = D.white, size = 13.sp, font = mono)
            }
            TermOut("● postgresql.service - PostgreSQL RDBMS", mono, color = D.moss)
            TermOut("   Loaded: loaded (/lib/systemd/system/postgresql.service)", mono)
            Row {
                Txt("   Active: ", color = D.textMid, size = 13.sp, font = mono)
                Txt("active (running)", color = D.moss, size = 13.sp, font = mono)
                Txt(" since Fri 2026-06-20", color = D.textMid, size = 13.sp, font = mono)
            }
            TermOut("   Memory: 412.0M", mono)
            TermOut("      CPU: 2h 14min 03s", mono)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("root@db-master", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
                Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("~", color = D.moss, size = 13.sp, font = mono)
                Txt("# ", color = D.cyanBright, size = 13.sp, font = mono)
                Box(Modifier.padding(start = 2.dp).size(width = 7.dp, height = 14.dp).background(D.cyan))
            }
        }
    }
}

// Info-панель.

@Composable
private fun InfoPanel() {
    val mono = LocalFonts.current.mono
    // Живой контекст активной сессии (если есть): профиль хоста из каталога + состояние соединения.
    val sessions = LocalSessions.current
    val hosts = LocalHosts.current
    val active = sessions?.active
    val host = active?.hostId?.let { id -> hosts?.find(id) }
    val live = sessions != null
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    // Контроллер live-метрик активной сессии (когда подключена). remember безусловный — ключи
    // (id сессии + флаг connected) пересоздают его при смене сессии/подключения, без условного
    // вызова remember. openMetrics идемпотентен (кэш в ConnectionController).
    val liveMetrics = remember(active, connected) {
        if (connected) active.controller.openMetrics() else null
    }?.metrics
    Column(Modifier.width(268.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt("CONNECTION", color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val dot = if (live) sessionDotColor(active?.controller?.uiState) else D.moss
                val label = if (!live) "LIVE" else if (connected) "LIVE" else "—"
                Dot(dot)
                Txt(label, color = dot, size = 10.sp)
            }
        }
        HLine()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Host/Address/User — из живого профиля активной сессии; cipher — из транспорта,
            // uptime — из live-метрик (до первого опроса «…»); в мок-режиме — статичные значения.
            InfoRow("Host", if (live) (host?.label ?: active?.title ?: "—") else "prod-web-01", mono)
            InfoRow("Address", if (live) (host?.let { "${it.address}:${it.port}" } ?: "—") else "192.168.1.45:22", mono)
            InfoRow("User", if (live) (host?.username ?: "—") else "root", mono)
            InfoRow("Auth", if (live) (host?.identityId?.let { "identity" } ?: "password") else "id_ed25519", mono)
            InfoRow("Cipher", if (live) (shortCipher(active?.controller?.cipher) ?: "…") else "aes256-gcm", mono)
            InfoRow("Uptime", if (live) (liveMetrics?.uptimeSeconds?.let { formatUptime(it) } ?: "…") else "04:12:45", mono)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Txt("LIVE METRICS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 8.dp))
            if (!live) {
                // Мок-путь (превью/офскрин): статичные значения.
                Meter("CPU", "34%", 0.34f, D.cyan, D.textBright, mono)
                Meter("Memory", "2.1 / 4 GB", 0.52f, D.moss, D.textBright, mono)
                Meter("Disk /", "87%", 0.87f, D.sunset, D.sunset, mono)
            } else {
                // Живой опрос ресурсов сессии (контроллер поднят выше). До первого удачного
                // опроса (или на не-Linux хосте) — «…».
                val m = liveMetrics
                val cpu = m?.cpuPercent
                Meter("CPU", cpu?.let { "$it%" } ?: "…", m?.cpuFraction ?: 0f, D.cyan, if ((cpu ?: 0) > 85) D.sunset else D.textBright, mono)
                Meter("Memory", m?.let { "${gb(it.memUsedBytes)} / ${gb(it.memTotalBytes)} GB" } ?: "…", m?.memFraction ?: 0f, D.moss, D.textBright, mono)
                val disk = m?.diskPercent
                Meter("Disk /", disk?.let { "$it%" } ?: "…", m?.diskFraction ?: 0f, if ((disk ?: 0) > 85) D.sunset else D.cyan, if ((disk ?: 0) > 85) D.sunset else D.textBright, mono)
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Txt("SYSTEM", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 8.dp))
            // Живой блок собирается из фактов хоста (ОС / ядро / CPU+load); до первого опроса — «…».
            // В мок-режиме (превью/офскрин) — статичный текст.
            val systemText = if (live) liveSystemSummary(liveMetrics) else MOCK_SYSTEM
            Txt(systemText, color = D.dim, size = 10.5.sp, font = mono, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(label, color = D.faint, size = 11.5.sp)
        Txt(value, color = D.textBright, size = 11.5.sp, font = mono)
    }
}

@Composable
private fun Meter(label: String, value: String, fraction: Float, bar: Color, valueColor: Color, mono: FontFamily) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(label, color = D.dim, size = 11.sp)
            Txt(value, color = valueColor, size = 11.sp, font = mono)
        }
        MeterBar(fraction, bar)
    }
}

/** Байты → строка гигабайт с одним знаком после запятой (десятичные ГБ, как в free -h). */
private fun gb(bytes: Long): String {
    val rounded = (bytes / 1_000_000_000.0 * 10).roundToInt() / 10.0
    return rounded.toString()
}

/** Статичный SYSTEM-блок для мок/офскрин-режима (нет живой сессии). */
private const val MOCK_SYSTEM = "Ubuntu 22.04.4 LTS\nLinux 5.15.0-105 x86_64\n4 vCPU · load 0.42 0.51 0.48"

/**
 * Собирает SYSTEM-блок info-панели из живых фактов хоста: ОС, ядро, строка «N vCPU · load …».
 * Пропускает поля, которых ещё нет (опрос не дошёл / не-Linux). Пусто всё — «…».
 */
private fun liveSystemSummary(m: HostMetrics?): String {
    val cpuLoad = buildString {
        m?.cpuCount?.let { append("$it vCPU") }
        m?.loadAverage?.let {
            if (isNotEmpty()) append(" · ")
            append("load $it")
        }
    }
    val lines = listOfNotNull(
        m?.osName,
        m?.kernel,
        cpuLoad.takeIf { it.isNotEmpty() },
    )
    return if (lines.isEmpty()) "…" else lines.joinToString("\n")
}

// AI-бар.

/**
 * Решает, показывать ли терминальный AI-бар и в каком режиме. Живой контроллер ([LocalAi]) → бар
 * работает под per-host политикой ([Host.aiPolicy]): [app.skerry.shared.ai.AiPolicy.Off] прячет бар
 * целиком. Без контроллера, но с фича-флагом → прежний декоративный мок (для дизайн-превью).
 */
@Composable
private fun TerminalAiBarSlot() {
    val ai = LocalAi.current
    if (ai != null) {
        val active = LocalSessions.current?.active
        val policy = active?.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
        if (AiPolicyDecision.of(policy).aiEnabled) {
            val terminal = (active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
            AiBar(ai, policy, terminal)
        }
    } else if (LocalFeatures.current.ai) {
        AiBarMock()
    }
}

/**
 * Интерактивный AI-бар: запрос на естественном языке → одна shell-команда под [policy]. Команда
 * НИКОГДА не исполняется автоматически — она показывается карточкой, и только «Run» вставляет её в
 * ввод терминала (через [TerminalScreenState.send], БЕЗ перевода строки — Enter жмёт пользователь).
 */
@Composable
private fun AiBar(ai: AiAssistantController, policy: AiPolicy, terminal: TerminalScreenState?) {
    val mono = LocalFonts.current.mono
    val controller = remember(ai, policy) { ai.terminalController(policy) }
    var prompt by remember { mutableStateOf("") }
    val submit = {
        val text = prompt.trim()
        if (text.isNotEmpty()) { controller.ask(text); prompt = "" }
    }
    Column {
        HLine()
        // Предложенная команда — над строкой ввода, с явным подтверждением (Run) или отклонением.
        controller.pending?.let { cmd ->
            Row(
                Modifier.fillMaxWidth().background(D.moss.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("terminal", size = 15.sp, color = D.moss)
                Txt(cmd, color = D.text, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
                AiActionChip("Run", D.moss, enabled = terminal != null) {
                    controller.confirm()?.let { terminal?.send(it) }
                }
                AiActionChip("Dismiss", D.faint) { controller.dismiss() }
            }
        }
        controller.blocked?.let { AiStatusRow("shield_lock", it, D.amber, mono) }
        controller.error?.let { AiStatusRow("error", it, D.sunset, mono) }
        if (controller.busy) AiStatusRow("hourglass_top", controller.streaming?.takeIf { it.isNotEmpty() } ?: "Thinking…", D.dim, mono)
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.amber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym("auto_awesome", size = 16.sp, color = D.amber)
            }
            Box(Modifier.weight(1f)) {
                if (prompt.isEmpty()) Txt("Ask Skerry: 'find files larger than 100MB'", color = D.dim, size = 13.sp)
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    singleLine = true,
                    textStyle = TextStyle(color = D.text, fontSize = 13.sp),
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AiBarTag("verified_user", policy.name.uppercase(), mono)
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.cyan)
                    .clickable(enabled = !controller.busy) { submit() },
                contentAlignment = Alignment.Center,
            ) {
                Sym("arrow_upward", size = 16.sp, color = Color(0xFF0A1A26))
            }
        }
    }
}

/** Строка статуса AI-бара (blocked/error/thinking). */
@Composable
private fun AiStatusRow(icon: String, text: String, color: Color, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 14.sp, color = color)
        Txt(text, color = color, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
    }
}

/** Компактная кнопка-чип в карточке предложения (Run/Dismiss). */
@Composable
private fun AiActionChip(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) color.copy(alpha = 0.16f) else Color(0x0AFFFFFF))
            .border(1.dp, if (enabled) color.copy(alpha = 0.5f) else D.line, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = if (enabled) color else D.faint, size = 12.sp, weight = FontWeight.Medium)
    }
}

/** Прежний декоративный AI-бар для чистого дизайн-превью (без живого контроллера). */
@Composable
private fun AiBarMock() {
    val mono = LocalFonts.current.mono
    Column {
        HLine()
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.amber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym("auto_awesome", size = 16.sp, color = D.amber)
            }
            Txt("Ask Skerry: 'find files larger than 100MB'   ·   Ctrl / to focus", color = D.dim, size = 13.sp, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiBarTag("lock", "Local · Qwen 2.5", mono)
                AiBarTag("verified_user", "STRICT", mono)
            }
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.cyan), contentAlignment = Alignment.Center) {
                Sym("arrow_upward", size = 16.sp, color = Color(0xFF0A1A26))
            }
        }
    }
}

@Composable
private fun AiBarTag(icon: String, text: String, mono: FontFamily) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, D.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Sym(icon, size = 12.sp, color = D.faint)
        Txt(text, color = D.faint, size = 10.5.sp, font = mono)
    }
}
