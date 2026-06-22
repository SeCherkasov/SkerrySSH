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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.humanRate
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.IdentityManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionsController
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
    state: DesktopDesignState = remember { DesktopDesignState() },
    vault: Vault? = null,
    biometrics: VaultBiometrics? = null,
    hosts: HostManagerController? = null,
    transport: SshTransport? = null,
    identities: IdentityManagerController? = null,
    sessions: SessionsController? = null,
    knownHosts: KnownHostsController? = null,
    features: FeatureFlags = FeatureFlags(),
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
        LocalFeatures provides features,
    ) {
        if (vault != null) {
            VaultGate(
                vault = vault,
                biometrics = biometrics,
                createForm = { error, onCreate -> DesktopCreateScreen(error, onCreate) },
                unlockForm = { error, canBio, onUnlock, onBio -> DesktopUnlockScreen(error, canBio, onUnlock, onBio) },
            ) { onLock -> DesktopChrome(state, onLock, liveSessions, identities) }
        } else {
            DesktopChrome(state, onLock = null, sessions = liveSessions, identities = identities)
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
    identities: IdentityManagerController?,
) {
    // Identity-список живёт в открытом vault — перечитываем за гейтом мастер-пароля (как MobileRoot).
    LaunchedEffect(identities) { identities?.reload() }

    // Хост, для которого нет привязанной identity → спрашиваем пароль перед подключением.
    var pendingHost by remember { mutableStateOf<Host?>(null) }

    // Стабильная лямбда коннекта: без remember она пересоздавалась бы на каждой рекомпозиции и,
    // уходя в staticCompositionLocalOf, инвалидировала бы всех потребителей [LocalConnectHost].
    val connectHost = remember(sessions, identities, state) {
        { host: Host ->
            val identity = identities?.find(host.identityId)
            if (identity != null) openHostSession(sessions, state, host, identity.toSshAuth()) else pendingHost = host
        }
    }

    CompositionLocalProvider(LocalConnectHost provides connectHost) {
        Box(Modifier.fillMaxSize().background(D.bg)) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(state, onLock)
                HLine()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    IconRail(state)
                    VLine(D.line)
                    Box(Modifier.weight(1f).fillMaxHeight()) { Viewport(state) }
                }
                HLine()
                StatusBar()
            }
            if (state.modalOpen) NewConnectionModal(state)
            if (state.settingsOpen) SettingsPanel(state)
            if (onLock == null && state.locked) LockScreen(state)
            pendingHost?.let { host ->
                DesktopPasswordDialog(
                    host = host,
                    onDismiss = { pendingHost = null },
                    onConnect = { pw -> pendingHost = null; openHostSession(sessions, state, host, SshAuth.Password(pw)) },
                )
            }
        }
    }
}

/** Открыть вкладку-сессию к [host] с [auth] и переключиться на терминал. */
private fun openHostSession(sessions: SessionsController?, state: DesktopDesignState, host: Host, auth: SshAuth) {
    sessions?.open(
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(),
        auth = auth,
    )
    state.showView(DesktopView.Terminal)
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
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Живые вкладки из менеджера сессий (за гейтом vault); иначе — мок-вкладки макета.
            val sessions = LocalSessions.current
            if (sessions != null) {
                sessions.sessions.forEach { s ->
                    SessionTabChip(
                        name = s.displayTitle,
                        dot = sessionDotColor(s.controller.uiState),
                        active = s.id == sessions.activeId,
                        onClick = { sessions.activate(s.id) },
                        onClose = { sessions.close(s.id) },
                    )
                }
            } else {
                state.tabs.forEachIndexed { i, tab ->
                    SessionTabChip(tab.name, tab.dot, active = i == state.activeTab, onClick = { state.setTab(i) }, onClose = { state.closeTab(i) })
                }
            }
            IconBtn("add", onClick = state::openModal, box = 26, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
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

@Composable
private fun SessionTabChip(name: String, dot: Color, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(if (active) D.bg else Color.Transparent)
            .border(
                1.dp,
                if (active) D.cyan14 else Color.Transparent,
                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        Txt(
            name,
            color = if (active) D.text else D.dim,
            size = 12.sp,
            weight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp),
        )
        IconBtn("close", onClick = onClose, box = 16, icon = 14.sp, tint = D.dim)
    }
}

@Composable
private fun IconRail(state: DesktopDesignState) {
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
            RailButton(
                icon = item.icon,
                label = item.label,
                active = state.view == item.view,
                onClick = { state.showView(item.view) },
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
            // RTT-пинг — живой источник в отдельном слайсе; пока «—» в живом режиме.
            StatusItem("network_ping", if (live) "—" else "42 ms", mono = mono)
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
