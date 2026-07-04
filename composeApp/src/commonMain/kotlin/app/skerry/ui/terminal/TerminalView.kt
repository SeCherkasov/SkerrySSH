package app.skerry.ui.terminal

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalConnectSplit
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.D
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_connecting
import app.skerry.ui.generated.resources.term_connection_failed
import app.skerry.ui.generated.resources.term_connection_lost
import app.skerry.ui.generated.resources.term_no_active_session
import app.skerry.ui.generated.resources.term_no_host_selected
import app.skerry.ui.generated.resources.term_no_hosts_in_catalog
import app.skerry.ui.generated.resources.term_notice_not_connected
import app.skerry.ui.generated.resources.term_notice_pick_host_to_connect
import app.skerry.ui.generated.resources.term_notice_pick_or_new
import app.skerry.ui.generated.resources.term_notice_pick_side_by_side
import app.skerry.ui.generated.resources.term_reconnecting
import app.skerry.ui.generated.resources.term_select_host_placeholder
import app.skerry.ui.generated.resources.term_session_closed
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.sessionDotColor
import org.jetbrains.compose.resources.stringResource

/** Общая высота шапки панели (основной и split) — чтобы заголовки были вровень. */
private val PANE_HEADER_HEIGHT = 40.dp

/**
 * Свёрнутый сайдбар: тонкий рельс слева с единственной кнопкой — вернуть панель хостов.
 * Держит место панели занятым, чтобы контент не «прыгал» на всю ширину при скрытии.
 */
@Composable
private fun CollapsedSidebarRail(state: DesktopDesignState) {
    Column(
        // start=12, top=12, box=34 в точности как у кнопки в шапке развёрнутого сайдбара — кнопка
        // не «прыгает» ни по вертикали, ни по горизонтали при сворачивании/разворачивании.
        Modifier.width(58.dp).fillMaxHeight().background(D.surface2).padding(start = 12.dp, top = 12.dp),
    ) {
        IconBtn("chevron_right", onClick = state::toggleSidebar, box = 34, tint = D.dim)
    }
}

/** Терминальный view: hosts-sidebar + main (toolbar, панели, AI-bar) + info-panel. */
@Composable
fun TerminalView(state: DesktopDesignState) {
    Row(Modifier.fillMaxSize()) {
        if (state.sidebarHidden) CollapsedSidebarRail(state) else HostsSidebar(state)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Общий AI-контроллер живого бара (или null): один экземпляр на оверлей-слой и строку ввода;
            // key() пересоздаёт при смене активного хоста/политики. Off/мок → null (показ ниже прежним слотом).
            val liveAi = LocalAi.current
            val aiSession = LocalSessions.current?.active
            val aiPolicy = aiSession?.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
            val aiTerminal = (aiSession?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
            // liveAi.enabled — в key: глобальный OFF в настройках убирает/возвращает бар
            // без пересоздания экрана (settings — Compose-state, смена рекомпозирует).
            val aiController = key(liveAi, aiPolicy, liveAi?.enabled) {
                remember {
                    if (liveAi != null && liveAi.enabled && AiPolicyDecision.of(aiPolicy).aiEnabled) liveAi.terminalController(aiPolicy) else null
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
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
            }
            // Всё в одной строке бара: команда + инлайн-пояснение/причина риска + кнопки; Thinking/blocked/
            // error там же. Ничего не перекрывает терминал и не меняет его высоту (нет «дёрга»). Off/мок → слот.
            // AI-бар только при активной сессии: на пустом экране «Нет активной сессии» помощник не нужен.
            // В дизайн-превью (LocalSessions == null) бар-мок остаётся.
            if (aiSession != null || LocalSessions.current == null) {
                if (aiController != null) AiBarInput(aiController, aiTerminal, state.aiBarFocusRequests) else TerminalAiBarSlot()
            }
        }
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
                    Txt(stringResource(Res.string.term_no_active_session), color = D.faint, size = 12.sp, font = mono)
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
            null -> TerminalNotice("terminal", stringResource(Res.string.term_no_active_session), stringResource(Res.string.term_notice_pick_host_to_connect))
            // Form у активной вкладки = пустой таб («+»): соединение ещё не запускалось.
            ConnectionUiState.Form -> TerminalNotice("terminal", stringResource(Res.string.term_notice_not_connected), stringResource(Res.string.term_notice_pick_or_new))
            ConnectionUiState.Connecting -> TerminalNotice("sync", stringResource(Res.string.term_connecting), active.subtitle)
            is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
            is ConnectionUiState.Error -> TerminalNotice("error", stringResource(Res.string.term_connection_failed), st.message, color = D.sunset)
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
        state.cleanExit -> stringResource(Res.string.term_session_closed)
        state.reconnecting -> stringResource(Res.string.term_reconnecting, state.attempt)
        else -> stringResource(Res.string.term_connection_lost)
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
                        Txt(stringResource(Res.string.term_select_host_placeholder), color = D.faint, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
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
                null -> TerminalNotice("splitscreen_right", stringResource(Res.string.term_no_host_selected), stringResource(Res.string.term_notice_pick_side_by_side))
                ConnectionUiState.Form -> TerminalNotice("terminal", stringResource(Res.string.term_session_closed), split.subtitle)
                ConnectionUiState.Connecting -> TerminalNotice("sync", stringResource(Res.string.term_connecting), split.subtitle)
                is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
                is ConnectionUiState.Error -> TerminalNotice("error", stringResource(Res.string.term_connection_failed), st.message, color = D.sunset)
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
            Txt(stringResource(Res.string.term_no_hosts_in_catalog), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
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
