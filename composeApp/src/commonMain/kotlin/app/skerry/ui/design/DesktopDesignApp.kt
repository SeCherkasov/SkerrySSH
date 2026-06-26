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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.humanRate
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.vault.VaultGate

/**
 * Корень десктопного макета `docs/new/Skerry.html`, воспроизведённого 1:1. Поставляет шрифты
 * через [LocalFonts], держит [DesktopDesignState] и собирает структуру: titlebar (44dp) →
 * rail (62dp) + viewport → statusbar (26dp). Поверх — оверлеи lock / new-connection / settings.
 *
 * Живой слой подключается через [vault]: если передан, весь chrome закрыт гейтом мастер-пароля
 * ([app.skerry.ui.vault.VaultGate]) поверх [app.skerry.ui.vault.VaultGateController] — экраны
 * создания/разблокировки рисуются в стиле макета ([DesktopCreateScreen]/[DesktopUnlockScreen]),
 * а чип «Unlocked» в titlebar реально запирает vault. Без [vault] (путь скриншота/превью) данные
 * остаются мок-статичными ([DesktopMockData]), а блокировка — заглушка ([DesktopDesignState]).
 */
@Composable
fun DesktopDesignApp(
    // Видимость info-панели персистится снаружи (desktop main): стартовое значение + колбэк записи.
    initialInfoPanel: Boolean = true,
    onInfoPanelChange: (Boolean) -> Unit = {},
    // Схлопнутые папки хостов — так же персистятся снаружи (desktop main): стартовый набор + колбэк записи.
    initialCollapsedGroups: Set<String> = emptySet(),
    onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Недавние подключения (секция RECENT) — тоже персистятся снаружи (desktop main): стартовый порядок + колбэк записи.
    initialRecentHostIds: List<String> = emptyList(),
    onRecentHostIdsChange: (List<String>) -> Unit = {},
    state: DesktopDesignState = remember {
        DesktopDesignState(initialInfoPanel, onInfoPanelChange, initialCollapsedGroups, onCollapsedGroupsChange, initialRecentHostIds, onRecentHostIdsChange)
    },
    vault: Vault? = null,
    biometrics: VaultBiometrics? = null,
    hosts: HostManagerController? = null,
    transport: SshTransport? = null,
    // Транспорт для разовой проверки «Test connection»: отдельный от [transport] (живых сессий),
    // потому что проба НЕ должна заносить ключ хоста в known_hosts (read-only verifier). `null` —
    // использовать [transport] (офскрин-рендер/превью, где enroll-побочки нет). См. main.kt.
    testTransport: SshTransport? = null,
    credentials: CredentialManagerController? = null,
    sessions: SessionsController? = null,
    knownHosts: KnownHostsController? = null,
    keyGenerator: SshKeyGenerator? = null,
    certificateInspector: SshCertificateInspector? = null,
    tunnels: TunnelManager? = null,
    features: FeatureFlags = FeatureFlags(),
    // Вызывается один раз после разблокировки vault, до перечитывания списков — точка для миграции
    // данных (схлопывание двухуровневой модели → хост ссылается на keychain-секрет). No-op в мок/превью.
    onVaultUnlocked: () -> Unit = {},
) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    // Менеджер сессий: либо подан снаружи (офскрин-рендер с фейковым транспортом), либо строится
    // из живого транспорта — один shell на вкладку, как в [app.skerry.ui.mobile.MobileApp]. Свой
    // граф закрываем при dispose; внешний — собственность вызывающего, не трогаем.
    val scope = rememberCoroutineScope()
    val liveSessions = sessions ?: remember(transport, scope) {
        transport?.let { t ->
            var counter = 0
            SessionsController(newId = { "sess-${counter++}" }, controllerFactory = { ConnectionController(t, scope) })
        }
    }
    // Владение фиксируем снимком на момент композиции: внешний менеджер сессий — собственность
    // вызывающего (не рвём), локально построенный закрываем при dispose.
    val ownsSessions = sessions == null
    DisposableEffect(liveSessions) {
        onDispose { if (ownsSessions) liveSessions?.disconnectAll() }
    }
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalHosts provides hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides knownHosts,
        LocalSshKeyGenerator provides keyGenerator,
        LocalSshCertificateInspector provides certificateInspector,
        LocalCredentials provides credentials,
        LocalTestTransport provides (testTransport ?: transport),
        LocalTunnels provides tunnels,
        LocalFeatures provides features,
    ) {
        if (vault != null) {
            VaultGate(
                vault = vault,
                biometrics = biometrics,
                createForm = { error, onCreate -> DesktopCreateScreen(error, onCreate) },
                unlockForm = { error, canBio, onUnlock, onBio -> DesktopUnlockScreen(error, canBio, onUnlock, onBio) },
            ) { onLock -> DesktopChrome(state, onLock, liveSessions, credentials, onVaultUnlocked) }
        } else {
            DesktopChrome(state, onLock = null, sessions = liveSessions, credentials = credentials, onVaultUnlocked = onVaultUnlocked)
        }
    }
}

/**
 * Основной chrome макета (titlebar → rail+viewport → statusbar) и оверлеи. [onLock] != null —
 * живой путь за гейтом: чип «Unlocked» запирает vault. null — мок-путь: блокировку рисует
 * заглушечный [LockScreen] по [DesktopDesignState.locked].
 */
@Composable
private fun DesktopChrome(
    state: DesktopDesignState,
    onLock: (() -> Unit)?,
    sessions: SessionsController?,
    credentials: CredentialManagerController?,
    onVaultUnlocked: () -> Unit,
) {
    // Keychain-секреты живут в открытом vault — за гейтом мастер-пароля сперва прогоняем
    // миграцию данных ([onVaultUnlocked]), затем перечитываем (как MobileRoot).
    LaunchedEffect(credentials) {
        onVaultUnlocked()
        credentials?.reload()
    }

    // Хост, для которого нет привязанного секрета → спрашиваем пароль перед подключением.
    var pendingHost by remember { mutableStateOf<Host?>(null) }
    // То же, но подключение пойдёт в split-панель целевой вкладки (а не новой вкладкой). Целевую
    // вкладку фиксируем в момент выбора хоста, а не submit — иначе при переключении вкладок во время
    // ввода пароля split откроется не там, где его запросили.
    var pendingSplitHost by remember { mutableStateOf<Host?>(null) }
    var pendingSplitParent by remember { mutableStateOf<String?>(null) }

    // Стабильная лямбда коннекта: без remember она пересоздавалась бы на каждой рекомпозиции и,
    // уходя в staticCompositionLocalOf, инвалидировала бы всех потребителей [LocalConnectHost].
    // Резолв одноуровневый: хост → keychain-секрет по credentialId → SshAuth; нет привязки → пароль.
    val connectHost = remember(sessions, credentials, state) {
        { host: Host ->
            val credential = credentials?.find(host.credentialId)
            if (credential != null) {
                openHostSession(sessions, state, host, credential.toSshAuth())
            } else {
                pendingHost = host
            }
        }
    }

    // Тот же резолв, но в split-панель активной вкладки (новая независимая вторичная сессия).
    val connectSplitHost = remember(sessions, credentials, state) {
        { host: Host ->
            val parentId = sessions?.activeId
            val credential = credentials?.find(host.credentialId)
            if (credential != null) {
                openSplitSession(sessions, state, parentId, host, credential.toSshAuth())
            } else {
                pendingSplitHost = host
                pendingSplitParent = parentId
            }
        }
    }

    // Лок vault должен снять все активные туннели: их соединения держат расшифрованный секрет, и после
    // запирания висеть им нельзя (zero-knowledge). closeAll идемпотентен; для мок-пути (onLock==null)
    // оборачивать нечего.
    val tunnels = LocalTunnels.current
    // Лок снимает активные туннели (их соединения держат расшифрованный секрет — zero-knowledge), но
    // СЕССИИ ВКЛАДОК (включая split) намеренно ПЕРЕЖИВАЮТ lock: после разблокировки терминалы на месте.
    // Висящие диалоги запроса пароля сбрасываем (не оставлять ввод пароля под lock-экраном).
    val onLockWithTunnels = onLock?.let { lock ->
        {
            pendingHost = null
            pendingSplitHost = null
            pendingSplitParent = null
            tunnels?.closeAll()
            // Сессии переживают lock (открытый сокет жить остаётся), но авто-реконнект после lock
            // переаутентифицировался бы устаревшим секретом на запертом vault — запрещаем его,
            // сбрасывая сохранённые учётки у всех сессий и их split-панелей (zero-knowledge).
            sessions?.sessions?.forEach { s ->
                s.controller.clearReconnectCredentials()
                s.splitSession?.controller?.clearReconnectCredentials()
            }
            lock()
        }
    }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
        LocalConnectSplit provides connectSplitHost,
        LocalCredentials provides credentials,
    ) {
        Box(Modifier.fillMaxSize().background(D.bg)) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(state, onLockWithTunnels)
                HLine()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    IconRail(state)
                    VLine(D.line)
                    Box(Modifier.weight(1f).fillMaxHeight()) { Viewport(state) }
                }
                HLine()
                StatusBar()
            }
            if (state.modalOpen) NewConnectionModal(state, editHost = state.editingHost)
            if (state.settingsOpen) SettingsPanel(state)
            if (onLock == null && state.locked) LockScreen(state)
            pendingHost?.let { host ->
                DesktopPasswordDialog(
                    host = host,
                    onDismiss = { pendingHost = null },
                    onConnect = { pw -> pendingHost = null; openHostSession(sessions, state, host, SshAuth.Password(pw)) },
                )
            }
            pendingSplitHost?.let { host ->
                DesktopPasswordDialog(
                    host = host,
                    onDismiss = { pendingSplitHost = null; pendingSplitParent = null },
                    onConnect = { pw ->
                        val parentId = pendingSplitParent
                        pendingSplitHost = null; pendingSplitParent = null
                        openSplitSession(sessions, state, parentId, host, SshAuth.Password(pw))
                    },
                )
            }
            // Подтверждение удаления профиля хоста (вызывается из контекстного меню сайдбара).
            // Сам keychain-секрет остаётся в vault (переиспользуемый, управляется во вкладке Vault).
            val hosts = LocalHosts.current
            state.pendingDeleteHost?.let { host ->
                DesktopDeleteHostDialog(
                    host = host,
                    onDismiss = state::dismissDeleteHost,
                    onConfirm = { hosts?.delete(host.id); state.dismissDeleteHost() },
                )
            }
        }
    }
}

/**
 * Подключиться к [host] с [auth]: если активна пустая вкладка («+») — коннект в неё, иначе новая
 * вкладка ([SessionsController.connect]). Затем переключаемся на терминал (сбрасывая app-оверлей).
 */
private fun openHostSession(sessions: SessionsController?, state: DesktopDesignState, host: Host, auth: SshAuth) {
    // Отмечаем хост в секции RECENT сайдбара (новейший — первым, переживает перезапуск).
    state.recordRecentHost(host.id)
    sessions?.connect(
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(),
        auth = auth,
    )
    // Живой режим: подвью держит сама вкладка — лишь снимаем оверлей, чтобы показать её терминал.
    // Мок/превью (нет сессий): фолбэк на Terminal через showView.
    if (sessions != null) state.clearOverlay() else state.showView(DesktopView.Terminal)
}

/**
 * Подключить [host] с [auth] в split-панель активной вкладки (новая независимая вторичная сессия,
 * модель Termius). Без активной вкладки — no-op. См. [SessionsController.connectSplit].
 */
private fun openSplitSession(sessions: SessionsController?, state: DesktopDesignState, parentId: String?, host: Host, auth: SshAuth) {
    if (sessions == null || parentId == null) return
    // Подключение во вторичную панель — тоже реальный коннект к хосту: отмечаем его в RECENT.
    state.recordRecentHost(host.id)
    sessions.connectSplit(
        parentId = parentId,
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(),
        auth = auth,
    )
}

@Composable
private fun TitleBar(state: DesktopDesignState, onLock: (() -> Unit)?) {
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Живые вкладки из менеджера сессий (за гейтом vault); иначе — мок-вкладки макета.
            val sessions = LocalSessions.current
            if (sessions != null) {
                // Состояние drag-reorder вкладок (модель Termius): перетаскивание чипов местами.
                val tabDrag = remember { TabDragState() }
                // rememberUpdatedState: pointerInput пересоздаётся лишь по ключу tabId, поэтому лямбда
                // ids() должна читать свежий список через .value, иначе onDragEnd взял бы устаревший
                // порядок (как сделано для drag хостов).
                val tabIds = rememberUpdatedState(sessions.sessions.map { it.id })
                sessions.sessions.forEachIndexed { index, s ->
                    // Линия вставки перед чипом, над которым сейчас зависла перетаскиваемая вкладка.
                    if (tabDrag.insertLineIndex == index) TabInsertLine()
                    // При сплите чип показывает сфокусированную панель (модель Termius): имя меняется
                    // при переключении фокуса между основной и split-панелью.
                    val focused = if (s.splitOpen && s.focusedSplit) s.splitSession ?: s else s
                    SessionTabChip(
                        name = focused.displayTitle,
                        dot = sessionDotColor(focused.controller.uiState),
                        split = s.splitOpen,
                        active = s.id == sessions.activeId,
                        onClick = { sessions.activate(s.id) },
                        onClose = { tabDrag.clearBounds(s.id); sessions.close(s.id) },
                        dragging = tabDrag.draggingTabId == s.id,
                        modifier = Modifier
                            .tabBoundsAnchor(tabDrag, s.id)
                            .draggableTab(tabDrag, s.id, ids = { tabIds.value }) { from, to -> sessions.moveTab(from, to) },
                    )
                }
                // Линия вставки в самом конце ряда (перенос вкладки в хвост).
                if (tabDrag.insertLineIndex == sessions.sessions.size) TabInsertLine()
            } else {
                state.tabs.forEachIndexed { i, tab ->
                    SessionTabChip(tab.name, tab.dot, active = i == state.activeTab, onClick = { state.setTab(i) }, onClose = { state.closeTab(i) })
                }
            }
            // «+» создаёт ПУСТУЮ вкладку без сессии (живой режим) и переключает на её терминал-
            // плейсхолдер; первое подключение из сайдбара заполнит её ([SessionsController.connect]).
            // В мок/превью (нет живых сессий) сохраняем прежнее поведение — открыть модалку.
            IconBtn(
                "add",
                onClick = {
                    if (sessions != null) {
                        // Новая пустая вкладка стартует с подвью Terminal (дефолт Session.view);
                        // снимаем оверлей, чтобы показать её терминал-плейсхолдер.
                        sessions.openBlank()
                        state.clearOverlay()
                    } else {
                        state.openModal()
                    }
                },
                box = 26,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(D.cyan08)
                    .border(1.dp, D.cyan20, RoundedCornerShape(6.dp))
                    .clickable(onClick = onLock ?: state::lock)
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

/**
 * Вкладка-сессия как сегментная пилюля (стиль референса в палитре night-sea): активная — залитый
 * cyan-фон с обводкой и ярким текстом, неактивная — приглушённая полупрозрачная пилюля с тусклым
 * текстом. Слева статус-точка соединения, справа крестик закрытия. Полностью скруглённая (в отличие
 * от прежней «вкладки-папки» со скруглением только сверху).
 */
@Composable
private fun SessionTabChip(
    name: String,
    dot: Color,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    split: Boolean = false,
    dragging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            // Перетаскиваемый чип приглушаем (alpha), чтобы было видно, что он «оторван» от ряда.
            .alpha(if (dragging) 0.5f else 1f)
            .height(28.dp)
            .clip(shape)
            .background(if (active) D.cyan10 else Color(0x08FFFFFF))
            .border(1.dp, if (active) D.cyan20 else D.line, shape)
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        // Значок split: вкладка держит две панели (модель Termius).
        if (split) Sym("splitscreen_right", size = 13.sp, color = if (active) D.cyan else D.faint)
        Txt(
            name,
            color = if (active) D.text else D.dim,
            size = 12.sp,
            weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        IconBtn("close", onClick = onClose, box = 16, icon = 14.sp, tint = if (active) D.dim else D.faint)
    }
}

/** Вертикальная линия-индикатор позиции вставки при drag-reorder вкладок (cyan-акцент макета). */
@Composable
private fun TabInsertLine() {
    Box(Modifier.width(2.dp).height(22.dp).clip(RoundedCornerShape(1.dp)).background(D.cyan))
}

@Composable
private fun IconRail(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    // Текущий session-level пункт для подсветки: подвью активной вкладки (живой режим) либо
    // мок-фолбэк [state.view]. Под открытым app-оверлеем session-пункты не подсвечены.
    val currentSessionView = sessions?.active?.view?.asDesktopView() ?: state.view
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
            val active = if (state.appOverlay != null) item.view == state.appOverlay
            else item.view == currentSessionView
            RailButton(
                icon = item.icon,
                label = item.label,
                active = active,
                onClick = {
                    // App-level (Vault/Known/Teams/Snippets) → оверлей. Session-level: в живом режиме
                    // правит ТОЛЬКО подвью активной вкладки (источник правды) + снимает оверлей, не
                    // трогая мок-фолбэк state.view; без сессий — мок-путь через showView.
                    when {
                        item.view.isAppLevel -> state.showView(item.view)
                        sessions != null -> { state.clearOverlay(); sessions.setActiveView(item.view.asSessionView()) }
                        else -> state.showView(item.view)
                    }
                },
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
    // В живом режиме статус слева и throughput отражают активную сессию; RTT-пинг пока плейсхолдер
    // макета (отдельный слайс), но не противоречит состоянию связи.
    val sessions = LocalSessions.current
    val active = sessions?.active
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    val live = sessions != null
    val statusText = if (!live || connected) "Connected" else "Disconnected"
    val statusColor = if (!live || connected) D.moss else D.faint
    // Поллер скорости канала активной сессии (когда подключена). remember безусловный — ключи
    // (сессия + флаг connected) пересоздают его при смене сессии/подключения; openThroughput
    // идемпотентен (кэш в ConnectionController).
    val throughput = remember(active, connected) {
        if (connected && active != null) active.controller.openThroughput() else null
    }
    val upRate = throughput?.upRate
    val downRate = throughput?.downRate
    // RTT-пинг активной сессии (тот же приём, что throughput); до первого замера/при сбое — null.
    val ping = remember(active, connected) {
        if (connected && active != null) active.controller.openPing() else null
    }
    val rttMs = ping?.rttMs
    // Размер сетки — живой cols×rows активного терминала; вне коннекта остаётся мок-метка макета.
    val gridLabel = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)
        ?.terminal?.let { "${it.cols} × ${it.rows}" } ?: "80 × 24"
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
            StatusItem("circle", statusText, color = statusColor, iconSize = 11.sp, mono = mono)
            // RTT-пинг активной сессии live (до первого замера — «—»); в мок-режиме — метка шаблона.
            StatusItem("network_ping", if (live) (rttMs?.let { "$it ms" } ?: "—") else "42 ms", mono = mono)
            // Throughput канала live (до коннекта — «—»); в мок-режиме (офскрин) — метки шаблона.
            StatusItem("arrow_upward", if (live) (upRate?.let { humanRate(it) } ?: "—") else "1.2 KB/s", mono = mono)
            StatusItem("arrow_downward", if (live) (downRate?.let { humanRate(it) } ?: "—") else "8.4 KB/s", mono = mono)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Версия сервера — live ident активной сессии (до коннекта/если транспорт молчит — «—»).
            StatusItem("memory", if (live) (sessions?.active?.controller?.serverVersion ?: "—") else "SSH-2.0-OpenSSH_8.9p1", mono = mono)
            Txt("UTF-8 · LF", color = D.faint, size = 10.5.sp, font = mono)
            Txt(gridLabel, color = D.faint, size = 10.5.sp, font = mono)
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
