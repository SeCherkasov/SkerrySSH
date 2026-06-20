package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth
import app.skerry.ui.AppDependencies
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.desktop.BrandLogo
import app.skerry.ui.desktop.SkerryIcon
import app.skerry.ui.desktop.SkerryIconKind
import app.skerry.ui.desktop.statusColor
import app.skerry.ui.forward.ForwardDirection
import app.skerry.ui.forward.ForwardEntry
import app.skerry.ui.forward.ForwardRequest
import app.skerry.ui.forward.ForwardStatus
import app.skerry.ui.forward.PortForwardController
import app.skerry.ui.forward.forwardRouteText
import app.skerry.ui.forward.parseForwardInput
import app.skerry.ui.host.HostDraft
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.IdentityManagerController
import app.skerry.ui.identity.kindLabel
import app.skerry.ui.session.SessionsController
import app.skerry.ui.sftp.RemoteSftpPane
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.rememberJetBrainsMono
import app.skerry.ui.theme.SkerryColors
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.vault.VaultGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Мобильный корень Skerry по `docs/skerry-mobile-prototype.html` — одноэкранная навигация с нижним
 * таб-баром (Hosts / Terminals / Files / Keys / Settings), в отличие от двухпанельного
 * desktop-каркаса [app.skerry.ui.host.HostManagerScreen]. Граф зависимостей тот же [AppDependencies]:
 * экраны связаны с теми же контроллерами ([HostManagerController], [SessionsController] и т.д.).
 * Платформенная точка входа (Android `MainActivity`) вызывает [MobileApp] вместо `App`.
 */
@Composable
fun MobileApp(deps: AppDependencies = AppDependencies()) {
    SkerryTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = SkerryColors.nightSea) {
            val vault = deps.vault
            if (vault != null) {
                VaultGate(vault, deps.biometrics) { onLock -> MobileRoot(deps, onLock) }
            } else {
                MobileRoot(deps, onLock = null)
            }
        }
    }
}

private enum class MobileTab(val label: String, val icon: SkerryIconKind) {
    Hosts("Hosts", SkerryIconKind.Power),
    Terminal("Terminals", SkerryIconKind.Terminal),
    Files("Files", SkerryIconKind.Folder),
    Forwards("Forwards", SkerryIconKind.Forward),
    Keys("Keys", SkerryIconKind.Key),
    Settings("Settings", SkerryIconKind.Tune),
}

@Composable
private fun MobileRoot(deps: AppDependencies, onLock: (() -> Unit)?) {
    LaunchedEffect(deps.identities) { deps.identities?.reload() }

    val transport = deps.transport
    val scope = rememberCoroutineScope()
    val sessions = remember(transport) {
        transport?.let { t ->
            var counter = 0
            SessionsController(
                newId = { "sess-${counter++}" },
                controllerFactory = { ConnectionController(t, scope) },
            )
        }
    }
    DisposableEffect(sessions) { onDispose { sessions?.disconnectAll() } }

    var tab by remember { mutableStateOf(MobileTab.Hosts) }
    var showNew by remember { mutableStateOf(false) }
    var pendingHost by remember { mutableStateOf<Host?>(null) }

    fun openSession(host: Host, auth: SshAuth) {
        sessions?.open(
            hostId = host.id,
            title = host.label,
            subtitle = "${host.username}@${host.address}:${host.port}",
            target = SshTarget(host = host.address, port = host.port, username = host.username),
            auth = auth,
        )
        tab = MobileTab.Terminal
    }

    fun startConnect(host: Host) {
        val identity = deps.identities?.find(host.identityId)
        if (identity != null) openSession(host, identity.toSshAuth()) else pendingHost = host
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    MobileTab.Hosts -> HostsScreen(
                        hosts = deps.hosts,
                        statusOf = { id -> sessions?.statusFor(id) },
                        onConnect = ::startConnect,
                        onNew = { showNew = true },
                        onLock = onLock,
                    )
                    MobileTab.Terminal -> TerminalTab(sessions, onGoHosts = { tab = MobileTab.Hosts })
                    MobileTab.Files -> FilesTab(sessions, scope, onGoHosts = { tab = MobileTab.Hosts })
                    MobileTab.Forwards -> ForwardsTab(sessions, scope, onGoHosts = { tab = MobileTab.Hosts })
                    MobileTab.Keys -> KeysScreen(deps.identities)
                    MobileTab.Settings -> SettingsScreen(deps, onLock)
                }
            }
            MobileTabBar(
                selected = tab,
                sessionCount = sessions?.sessions?.size ?: 0,
                onSelect = { tab = it },
            )
        }

        if (showNew && deps.hosts != null) {
            NewConnectionSheet(
                onDismiss = { showNew = false },
                onSave = { draft -> deps.hosts.save(draft); showNew = false },
            )
        }
        pendingHost?.let { host ->
            PasswordSheet(
                host = host,
                onDismiss = { pendingHost = null },
                onConnect = { pw -> pendingHost = null; openSession(host, SshAuth.Password(pw)) },
            )
        }
    }
}

private fun Identity.toSshAuth(): SshAuth = when (val a = auth) {
    is IdentityAuth.Password -> SshAuth.Password(a.password)
    is IdentityAuth.PrivateKey -> SshAuth.PublicKey(a.privateKeyPem, a.passphrase)
}

// ===================== TAB BAR =====================

@Composable
private fun MobileTabBar(selected: MobileTab, sessionCount: Int, onSelect: (MobileTab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))
        Row(
            Modifier.fillMaxWidth().background(SkerryColors.nightSea).padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MobileTab.entries.forEach { item ->
                val active = item == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(item) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        SkerryIcon(item.icon, tint = if (active) SkerryColors.cyan else SkerryColors.textFaint, size = 22.dp)
                        if (item == MobileTab.Terminal && sessionCount > 0) {
                            Box(Modifier.padding(start = 14.dp).clip(RoundedCornerShape(8.dp)).background(SkerryColors.moss).padding(horizontal = 4.dp)) {
                                Text("$sessionCount", color = SkerryColors.deep2, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(item.label, color = if (active) SkerryColors.cyan else SkerryColors.textFaint, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ===================== HOSTS =====================

@Composable
private fun HostsScreen(
    hosts: HostManagerController?,
    statusOf: (String) -> ConnectionUiState?,
    onConnect: (Host) -> Unit,
    onNew: () -> Unit,
    onLock: (() -> Unit)?,
) {
    var query by remember { mutableStateOf("") }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val all = hosts?.hosts ?: emptyList()
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter {
            it.label.lowercase().contains(q) || it.address.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrandLogo(size = 24.dp)
                Text("Hosts", color = SkerryColors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (onLock != null) AppBarButton(SkerryIconKind.Lock, onLock)
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                SearchField(query, { query = it }, "Поиск хостов, адресов…")
                Spacer(Modifier.height(12.dp))
                if (all.isEmpty()) {
                    EmptyHint("Пока нет хостов", "Нажмите + чтобы добавить соединение")
                } else if (filtered.isEmpty()) {
                    EmptyHint("Ничего не найдено", "Измените запрос поиска")
                } else {
                    val groups = filtered.groupBy { it.group }
                    groups.keys.sortedWith(compareBy(nullsLast()) { it }).forEach { group ->
                        val name = group ?: "Без группы"
                        val isCollapsed = collapsed[name] == true
                        HostGroup(name, groups.getValue(group).size, isCollapsed, { collapsed[name] = !isCollapsed }, groups.getValue(group), statusOf, onConnect)
                        Spacer(Modifier.height(10.dp))
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }

        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).size(56.dp).clip(RoundedCornerShape(18.dp)).background(SkerryColors.cyan).clickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) { SkerryIcon(SkerryIconKind.Add, tint = SkerryColors.deep2, size = 26.dp) }
    }
}

@Composable
private fun HostGroup(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    hosts: List<Host>,
    statusOf: (String) -> ConnectionUiState?,
    onConnect: (Host) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SkerryColors.nightSeaSoft).border(1.dp, SkerryColors.line, RoundedCornerShape(14.dp))) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textFaint, size = 18.dp, modifier = Modifier.rotate(if (collapsed) -90f else 0f))
            Text(name, color = SkerryColors.textDim, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 8.dp, vertical = 1.dp)) {
                Text("$count", color = SkerryColors.textFaint, fontSize = 10.5.sp)
            }
        }
        if (!collapsed) {
            hosts.forEach { host ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))
                HostRow(host, statusOf(host.id)) { onConnect(host) }
            }
        }
    }
}

@Composable
private fun HostRow(host: Host, state: ConnectionUiState?, onClick: () -> Unit) {
    val mono = rememberJetBrainsMono()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(SkerryColors.cyan.copy(alpha = 0.08f)).border(1.dp, SkerryColors.lineStrong, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            SkerryIcon(SkerryIconKind.Terminal, tint = SkerryColors.cyan, size = 18.dp)
            Box(Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(statusColor(state)))
        }
        Column(Modifier.weight(1f)) {
            Text(host.label, color = SkerryColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${host.username}@${host.address}:${host.port}", color = SkerryColors.textFaint, fontSize = 11.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textFaint, size = 18.dp, modifier = Modifier.rotate(-90f))
    }
}

// ===================== TERMINAL =====================

@Composable
private fun TerminalTab(sessions: SessionsController?, onGoHosts: () -> Unit) {
    val active = sessions?.active
    if (sessions == null || active == null) {
        EmptyCenter(SkerryIconKind.Terminal, "Нет активных сессий", "Выберите хост на вкладке Hosts")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onGoHosts), contentAlignment = Alignment.Center) {
                SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textDim, size = 18.dp, modifier = Modifier.rotate(90f))
            }
            val mono = rememberJetBrainsMono()
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor(active.controller.uiState)))
                    Text(active.title.ifBlank { "сессия" }, color = SkerryColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(active.subtitle, color = SkerryColors.textFaint, fontSize = 10.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).clickable { sessions.close(active.id) }, contentAlignment = Alignment.Center) {
                SkerryIcon(SkerryIconKind.Close, tint = SkerryColors.textDim, size = 16.dp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))

        when (val st = active.controller.uiState) {
            is ConnectionUiState.Connecting -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = SkerryColors.cyan)
                    Text("Подключение…", color = SkerryColors.textDim)
                }
            }
            is ConnectionUiState.Connected -> {
                TerminalScreen(st.terminal, Modifier.weight(1f), imeInput = true)
                Keybar(st.terminal)
            }
            is ConnectionUiState.Error -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Не удалось подключиться", color = SkerryColors.storm, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(st.message, color = SkerryColors.textDim, fontSize = 13.sp)
                    Button(onClick = { sessions.close(active.id) }) { Text("Закрыть") }
                }
            }
            is ConnectionUiState.Form -> Box(Modifier.weight(1f))
        }
    }
}

/** Панель спецклавиш — сердце мобильного SSH UX: посылает управляющие последовательности в PTY. */
@Composable
private fun Keybar(terminal: TerminalScreenState) {
    // TerminalScreenState.send принимает String (кодируется в UTF-8 внутри).
    fun raw(vararg codes: Int) = terminal.send(codes.map { it.toChar() }.joinToString(""))
    fun text(s: String) = terminal.send(s)
    // Стрелки/Home/End — CSI с префиксом ESC (\u001B).
    fun esc(seq: String) = terminal.send("\u001b" + seq)

    Column(Modifier.fillMaxWidth().background(Color(0xFF0A1722)).padding(8.dp)) {
        KeyRow {
            KeyBtn("Esc", Modifier.weight(1f)) { raw(0x1b) }
            KeyBtn("Tab", Modifier.weight(1f)) { raw(0x09) }
            KeyBtn("↑", Modifier.weight(1f)) { esc("[A") }
            KeyBtn("↓", Modifier.weight(1f)) { esc("[B") }
            KeyBtn("←", Modifier.weight(1f)) { esc("[D") }
            KeyBtn("→", Modifier.weight(1f)) { esc("[C") }
        }
        Spacer(Modifier.height(6.dp))
        KeyRow {
            KeyBtn("~", Modifier.weight(1f)) { text("~") }
            KeyBtn("/", Modifier.weight(1f)) { text("/") }
            KeyBtn("|", Modifier.weight(1f)) { text("|") }
            KeyBtn("-", Modifier.weight(1f)) { text("-") }
            KeyBtn("^C", Modifier.weight(1f)) { raw(0x03) }
            KeyBtn("Home", Modifier.weight(1f)) { esc("[H") }
            KeyBtn("End", Modifier.weight(1f)) { esc("[F") }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
}

@Composable
private fun KeyBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val mono = rememberJetBrainsMono()
    Box(
        modifier.height(38.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = SkerryColors.textDim, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = mono) }
}

// ===================== FILES / KEYS / SETTINGS =====================

/**
 * Вкладка Files: живая SFTP-панель активной сессии. Канал поднимает [RemoteSftpPane] через
 * `ConnectionController.openSftp()` (как desktop) — отдельный от терминального. [scope] — долгоживущий
 * scope [MobileRoot]: закрытие канала в `onDispose` панели идёт под `NonCancellable` и должно пережить
 * уход с вкладки. Без активной/подключённой сессии — пустое состояние.
 */
@Composable
private fun FilesTab(sessions: SessionsController?, scope: CoroutineScope, onGoHosts: () -> Unit) {
    val active = sessions?.active
    if (sessions == null || active == null) {
        EmptyCenter(SkerryIconKind.Folder, "Нет активных сессий", "Подключитесь к хосту, чтобы открыть файлы")
        return
    }
    val mono = rememberJetBrainsMono()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onGoHosts), contentAlignment = Alignment.Center) {
                SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textDim, size = 18.dp, modifier = Modifier.rotate(90f))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor(active.controller.uiState)))
                    Text(active.title.ifBlank { "сессия" }, color = SkerryColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(active.subtitle, color = SkerryColors.textFaint, fontSize = 10.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))

        when (val st = active.controller.uiState) {
            is ConnectionUiState.Connecting -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = SkerryColors.cyan)
                    Text("Подключение…", color = SkerryColors.textDim)
                }
            }
            is ConnectionUiState.Connected -> {
                // Стабилизируем bound-reference: иначе новая лямбда на каждой рекомпозиции меняла бы
                // ключ produceState в RemoteSftpPane и переоткрывала бы SFTP-канал.
                val openSftp = remember(active.controller) { active.controller::openSftp }
                RemoteSftpPane(
                    openSftp = openSftp,
                    scope = scope,
                    mono = mono,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            is ConnectionUiState.Error -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Не удалось подключиться", color = SkerryColors.storm, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(st.message, color = SkerryColors.textDim, fontSize = 13.sp)
                    Button(onClick = onGoHosts) { Text("К хостам") }
                }
            }
            is ConnectionUiState.Form -> Box(Modifier.weight(1f))
        }
    }
}

/**
 * Вкладка Forwards: проброс портов (`-L`/`-R`) активной сессии. Туннели поднимает общий с desktop
 * [PortForwardController] через `ConnectionController.openPortForwards()`. [scope] — долгоживущий
 * scope [MobileRoot]: `closeAll` в `onDispose` снимает все туннели при уходе сессии из композиции.
 * В отличие от desktop (горизонтальная форма-`Row`), на телефоне параметры вводятся в нижнем листе
 * [AddForwardSheet], а список занимает весь экран.
 */
@Composable
private fun ForwardsTab(sessions: SessionsController?, scope: CoroutineScope, onGoHosts: () -> Unit) {
    val active = sessions?.active
    if (sessions == null || active == null) {
        EmptyCenter(SkerryIconKind.Forward, "Нет активных сессий", "Подключитесь к хосту, чтобы настроить проброс")
        return
    }
    val mono = rememberJetBrainsMono()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onGoHosts), contentAlignment = Alignment.Center) {
                SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textDim, size = 18.dp, modifier = Modifier.rotate(90f))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor(active.controller.uiState)))
                    Text(active.title.ifBlank { "сессия" }, color = SkerryColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Проброс портов", color = SkerryColors.textFaint, fontSize = 10.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))

        when (val st = active.controller.uiState) {
            is ConnectionUiState.Connecting -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = SkerryColors.cyan)
                    Text("Подключение…", color = SkerryColors.textDim)
                }
            }
            is ConnectionUiState.Connected -> {
                // Контроллер пробросов живёт на самой сессии (ConnectionController владеет им и
                // снимает все туннели в disconnect), поэтому пробросы переживают переключение
                // вкладок и не рвутся при уходе с этой вкладки.
                val forwards = remember(active.controller) { active.controller.openPortForwards(scope) }
                MobileForwardList(forwards, mono, Modifier.weight(1f).fillMaxWidth())
            }
            is ConnectionUiState.Error -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Не удалось подключиться", color = SkerryColors.storm, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(st.message, color = SkerryColors.textDim, fontSize = 13.sp)
                    Button(onClick = onGoHosts) { Text("К хостам") }
                }
            }
            is ConnectionUiState.Form -> Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MobileForwardList(controller: PortForwardController, mono: FontFamily, modifier: Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    Box(modifier) {
        val forwards = controller.forwards
        if (forwards.isEmpty()) {
            EmptyCenter(SkerryIconKind.Forward, "Пробросов пока нет", "Нажмите +, чтобы поднять -L или -R туннель")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(forwards, key = { it.id }) { entry ->
                    MobileForwardRow(entry, mono, onRemove = { controller.remove(entry) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).size(56.dp).clip(RoundedCornerShape(18.dp)).background(SkerryColors.cyan).clickable { showAdd = true },
            contentAlignment = Alignment.Center,
        ) { SkerryIcon(SkerryIconKind.Add, tint = SkerryColors.deep2, size = 26.dp) }
    }
    if (showAdd) {
        AddForwardSheet(
            mono = mono,
            onDismiss = { showAdd = false },
            onAdd = { direction, req ->
                when (direction) {
                    ForwardDirection.Local -> controller.addLocal(req.bindPort, req.destHost, req.destPort)
                    ForwardDirection.Remote -> controller.addRemote(req.bindPort, req.destHost, req.destPort)
                }
                showAdd = false
            },
        )
    }
}

@Composable
private fun MobileForwardRow(entry: ForwardEntry, mono: FontFamily, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SkerryColors.nightSeaSoft).border(1.dp, SkerryColors.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(SkerryColors.cyan.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                if (entry.direction == ForwardDirection.Local) "-L" else "-R",
                color = SkerryColors.cyan, fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(forwardRouteText(entry), color = SkerryColors.text, fontFamily = mono, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (statusText, statusColor) = when (val s = entry.status) {
                ForwardStatus.Starting -> "поднимается…" to SkerryColors.amber
                is ForwardStatus.Active -> "активен · порт ${s.boundPort}" to SkerryColors.moss
                is ForwardStatus.Failed -> s.message to SkerryColors.storm
            }
            Text(statusText, color = statusColor, fontFamily = mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            SkerryIcon(SkerryIconKind.Close, tint = SkerryColors.textDim, size = 16.dp)
        }
    }
}

@Composable
private fun AddForwardSheet(
    mono: FontFamily,
    onDismiss: () -> Unit,
    onAdd: (ForwardDirection, ForwardRequest) -> Unit,
) {
    var direction by remember { mutableStateOf(ForwardDirection.Local) }
    var bindPort by remember { mutableStateOf("") }
    var destHost by remember { mutableStateOf("") }
    var destPort by remember { mutableStateOf("") }
    val request = parseForwardInput(bindPort, destHost, destPort)

    SheetScaffold(onDismiss) {
        Text("Новый проброс", color = SkerryColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            if (direction == ForwardDirection.Local) {
                "-L · локальный порт на телефоне открывает доступ к адресу, видимому с сервера."
            } else {
                "-R · порт на сервере открывает доступ к адресу, видимому с телефона."
            },
            color = SkerryColors.textDim, fontSize = 11.5.sp,
        )
        DirectionPicker(direction, mono) { direction = it }
        SheetField("Порт слушателя", bindPort, "напр. 8080 (0 — выберет система)", KeyboardType.Number) { bindPort = it.filter(Char::isDigit) }
        SheetField("Хост назначения", destHost, "напр. localhost или 10.0.0.5") { destHost = it }
        SheetField("Порт назначения", destPort, "напр. 5432", KeyboardType.Number) { destPort = it.filter(Char::isDigit) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { request?.let { onAdd(direction, it) } },
            enabled = request != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Поднять туннель") }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

/** Полноширинный сегментированный переключатель -L / -R для нижнего листа. */
@Composable
private fun DirectionPicker(selected: ForwardDirection, mono: FontFamily, onSelect: (ForwardDirection) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SkerryColors.nightSea).border(1.dp, SkerryColors.lineStrong, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (dir in ForwardDirection.entries) {
            val active = dir == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (active) SkerryColors.cyanSoft else Color.Transparent).clickable { onSelect(dir) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (dir == ForwardDirection.Local) "-L  локальный" else "-R  обратный",
                    color = if (active) SkerryColors.cyan else SkerryColors.textDim,
                    fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun KeysScreen(identities: IdentityManagerController?) {
    Column(Modifier.fillMaxSize()) {
        SimpleAppBar("Keychain")
        val list = identities?.identities ?: emptyList()
        if (list.isEmpty()) {
            EmptyCenter(SkerryIconKind.Key, "Нет ключей и паролей", "Секреты появятся здесь после добавления")
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            SectionLabel("Секреты")
            list.forEach { identity -> KeyCard(identity) }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun KeyCard(identity: Identity) {
    val mono = rememberJetBrainsMono()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.5.dp).clip(RoundedCornerShape(14.dp)).background(SkerryColors.nightSeaSoft).border(1.dp, SkerryColors.line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(SkerryColors.cyan.copy(alpha = 0.08f)).border(1.dp, SkerryColors.lineStrong, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            SkerryIcon(SkerryIconKind.Key, tint = SkerryColors.cyan, size = 19.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(identity.label.ifBlank { "(без имени)" }, color = SkerryColors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = mono)
            Text(identity.auth.kindLabel(), color = SkerryColors.textFaint, fontSize = 10.5.sp, fontFamily = mono)
        }
    }
}

@Composable
private fun SettingsScreen(deps: AppDependencies, onLock: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        SimpleAppBar("Settings")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            SectionLabel("Безопасность")
            SettingsGroup {
                val biometrics = deps.biometrics
                if (biometrics != null) {
                    var enabled by remember { mutableStateOf(biometrics.isEnabled()) }
                    var busy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    SettingsRow(SkerryIconKind.Lock, "Разблокировка биометрией", "Отпечаток / лицо вместо мастер-пароля") {
                        Switch(
                            checked = enabled,
                            enabled = !busy,
                            onCheckedChange = { wantOn ->
                                if (busy) return@Switch
                                busy = true
                                scope.launch {
                                    try {
                                        if (wantOn) {
                                            val ok = biometrics.enable(
                                                BiometricPrompt(
                                                    title = "Включить биометрию",
                                                    cancelLabel = "Отмена",
                                                    subtitle = "Подтвердите биометрию, чтобы привязать разблокировку",
                                                ),
                                            ) == BiometricEnableResult.Enabled
                                            if (ok) enabled = true
                                        } else {
                                            biometrics.disable(); enabled = false
                                        }
                                    } finally { busy = false }
                                }
                            },
                        )
                    }
                }
                SettingsRow(SkerryIconKind.Lock, "Мастер-пароль", "Сменить пароль (скоро)") {
                    SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textFaint, size = 17.dp, modifier = Modifier.rotate(-90f))
                }
            }

            SectionLabel("О приложении")
            SettingsGroup {
                SettingsRow(SkerryIconKind.Info, "Skerry 0.1.0", "GPL-3.0 · open source SSH-клиент · единое KMP-ядро") {}
            }

            if (onLock != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.05f)).border(1.dp, SkerryColors.lineStrong, RoundedCornerShape(13.dp)).clickable(onClick = onLock).padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Заблокировать хранилище", color = SkerryColors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SkerryColors.nightSeaSoft).border(1.dp, SkerryColors.line, RoundedCornerShape(14.dp))) { content() }
}

@Composable
private fun SettingsRow(icon: SkerryIconKind, label: String, desc: String?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(SkerryColors.cyan.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            SkerryIcon(icon, tint = SkerryColors.cyan, size = 17.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = SkerryColors.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, color = SkerryColors.textFaint, fontSize = 10.5.sp)
        }
        trailing()
    }
}

// ===================== SHEETS =====================

@Composable
private fun NewConnectionSheet(onDismiss: () -> Unit, onSave: (HostDraft) -> Unit) {
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val canSave = label.isNotBlank() && address.isNotBlank() && username.isNotBlank() && portNum != null

    SheetScaffold(onDismiss) {
        Text("Новое соединение", color = SkerryColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Учётные данные шифруются мастер-паролем (zero-knowledge).", color = SkerryColors.textDim, fontSize = 11.5.sp)
        SheetField("Имя", label, "напр. prod-web-01") { label = it }
        SheetField("Адрес хоста", address, "192.168.1.45 или example.com") { address = it }
        SheetField("Порт", port, "22", KeyboardType.Number) { port = it }
        SheetField("Пользователь", username, "root или имя") { username = it }
        SheetField("Группа (необязательно)", group, "Production") { group = it }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (canSave) onSave(HostDraft(label = label.trim(), address = address.trim(), port = portNum, username = username.trim(), group = group.trim().ifBlank { null }))
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить") }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

@Composable
private fun PasswordSheet(host: Host, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    SheetScaffold(onDismiss) {
        Text(host.label, color = SkerryColors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("${host.username}@${host.address}:${host.port}", color = SkerryColors.textDim, fontSize = 12.sp)
        SheetField("Пароль", password, "", KeyboardType.Password, isPassword = true) { password = it }
        Button(onClick = { onConnect(password) }, enabled = password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Подключиться") }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

@Composable
private fun SheetScaffold(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA040A10)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(SkerryColors.deep)
                .border(1.dp, SkerryColors.lineStrong, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f)))
            content()
        }
    }
}

@Composable
private fun SheetField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), color = SkerryColors.textFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(SkerryColors.nightSea).border(1.dp, SkerryColors.lineStrong, RoundedCornerShape(11.dp)).padding(horizontal = 13.dp, vertical = 12.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = SkerryColors.textFaint, fontSize = 13.5.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = SkerryColors.text, fontSize = 13.5.sp),
                cursorBrush = SolidColor(SkerryColors.cyan),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ===================== SHARED BITS =====================

@Composable
private fun SimpleAppBar(title: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = SkerryColors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AppBarButton(icon: SkerryIconKind, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.04f)).border(1.dp, SkerryColors.line, RoundedCornerShape(9.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SkerryIcon(icon, tint = SkerryColors.textDim, size = 19.dp) }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)).border(1.dp, SkerryColors.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkerryIcon(SkerryIconKind.Search, tint = SkerryColors.textFaint, size = 18.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, color = SkerryColors.textFaint, fontSize = 14.sp)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = SkerryColors.text, fontSize = 14.sp),
                cursorBrush = SolidColor(SkerryColors.cyan),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = SkerryColors.textFaint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp))
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = SkerryColors.textDim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = SkerryColors.textFaint, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyCenter(icon: SkerryIconKind, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkerryIcon(icon, tint = SkerryColors.cyan, size = 40.dp)
            Text(title, color = SkerryColors.textDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = SkerryColors.textFaint, fontSize = 12.sp)
        }
    }
}
