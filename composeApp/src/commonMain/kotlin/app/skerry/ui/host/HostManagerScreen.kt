package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.desktop.BrandLogo
import app.skerry.ui.desktop.ChromeIconButton
import app.skerry.ui.desktop.DesktopSessionBar
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.DesktopStatusBar
import app.skerry.ui.desktop.LockIndicator
import app.skerry.ui.desktop.SessionTab
import app.skerry.ui.desktop.SkerryIcon
import app.skerry.ui.desktop.SkerryIconKind
import app.skerry.ui.desktop.statusColor
import app.skerry.ui.identity.IdentityManagerController
import app.skerry.ui.identity.IdentityManagerPanel
import app.skerry.ui.identity.kindLabel
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionsController
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.rememberJetBrainsMono
import app.skerry.ui.theme.SkerryColors

/**
 * Desktop-экран Skerry по `docs/skerry-prototype-desktop.html`. Каркас [DesktopShell] из четырёх
 * зон:
 *  - **titlebar** — бренд, вкладки открытых сессий ([SessionsController]), индикатор замка;
 *  - **sidebar** — каталог хостов с поиском и сворачиваемыми группами;
 *  - **main** — терминал активной сессии либо каталожные панели (редактор/подключение/ключи);
 *  - **statusbar** — состояние активной сессии.
 *
 * Мультисессионность держит [SessionsController]: каждая вкладка — отдельный
 * [ConnectionController] (один shell). [showCatalog] разводит основную область: показывать каталог
 * (выбор/правка хоста) или активную сессию. Сайдбар не блокируется при живых сессиях — браузить
 * каталог можно параллельно.
 */
@Composable
fun HostManagerScreen(
    transport: SshTransport,
    hosts: HostManagerController,
    modifier: Modifier = Modifier,
    identities: IdentityManagerController? = null,
    onLock: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val sessions = remember(transport) {
        var counter = 0
        SessionsController(
            newId = { "sess-${counter++}" },
            controllerFactory = { ConnectionController(transport, scope) },
        )
    }
    // Уход с экрана не рвёт сокеты сам по себе — закрываем все сессии явно.
    DisposableEffect(sessions) { onDispose { sessions.disconnectAll() } }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<HostDraft?>(null) }
    var managingIdentities by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    val mono = rememberJetBrainsMono()
    val activeSession = sessions.active
    val inSession = !showCatalog && activeSession != null

    DesktopShell(
        titlebar = {
            Titlebar(
                sessions = sessions,
                inSession = inSession,
                onActivate = { id ->
                    sessions.activate(id)
                    showCatalog = false
                    managingIdentities = false
                },
                onCloseTab = { id ->
                    sessions.close(id)
                    if (sessions.sessions.isEmpty()) showCatalog = true
                },
                onNewTab = {
                    selectedId = null
                    editing = HostDraft(label = "", address = "", username = "")
                    showCatalog = true
                    managingIdentities = false
                },
                onLock = onLock,
            )
        },
        sidebar = {
            HostSidebar(
                hosts = hosts.hosts,
                query = query,
                onQuery = { query = it },
                selectedId = selectedId.takeIf { showCatalog && !managingIdentities },
                statusOf = { hostId -> sessions.statusFor(hostId) },
                onSelect = { id ->
                    selectedId = id
                    editing = null
                    managingIdentities = false
                    showCatalog = true
                },
                onNew = {
                    selectedId = null
                    editing = HostDraft(label = "", address = "", username = "")
                    managingIdentities = false
                    showCatalog = true
                },
                onManageIdentities = identities?.let { { managingIdentities = true; showCatalog = true } },
            )
        },
        statusbar = {
            val state = activeSession?.controller?.uiState
            DesktopStatusBar(
                left = statusLineFor(activeSession?.subtitle, state),
                right = "UTF-8 · LF",
                ok = state is ConnectionUiState.Connected,
                mono = mono,
            )
        },
        main = {
            MainArea(
                inSession = inSession,
                activeSession = activeSession,
                managingIdentities = managingIdentities,
                identities = identities,
                editing = editing,
                selectedHost = selectedId?.let(hosts::find),
                mono = mono,
                onCloseActive = {
                    // То же поведение, что и закрытие вкладки крестиком: уходим к соседней сессии,
                    // а если открытых не осталось — показываем каталог.
                    activeSession?.let { sessions.close(it.id) }
                    if (sessions.sessions.isEmpty()) showCatalog = true
                },
                onCloseIdentities = { managingIdentities = false },
                onSaveDraft = { saved -> selectedId = hosts.save(saved); editing = null },
                onCancelEdit = { editing = null },
                onDeleteDraft = { id ->
                    hosts.delete(id)
                    editing = null
                    if (selectedId == id) selectedId = null
                },
                onConnect = { host, auth ->
                    sessions.open(
                        hostId = host.id,
                        title = host.label,
                        subtitle = "${host.username}@${host.address}:${host.port}",
                        target = host.toTarget(),
                        auth = auth,
                    )
                    showCatalog = false
                    managingIdentities = false
                },
                onEditHost = { host -> editing = host.toDraft() },
                onDeleteHost = { host -> hosts.delete(host.id); selectedId = null },
            )
        },
    )
}

private fun statusLineFor(subtitle: String?, state: ConnectionUiState?): String = when {
    subtitle == null -> "Нет активной сессии"
    state is ConnectionUiState.Connected -> "Подключено · $subtitle"
    state is ConnectionUiState.Connecting -> "Подключение · $subtitle"
    state is ConnectionUiState.Error -> "Ошибка · $subtitle"
    else -> subtitle
}

private fun Host.toTarget() = SshTarget(host = address, port = port, username = username)

private fun Host.toDraft() = HostDraft(
    id = id,
    label = label,
    address = address,
    port = port,
    username = username,
    group = group,
    identityId = identityId,
)

// ===================== TITLEBAR =====================

@Composable
private fun Titlebar(
    sessions: SessionsController,
    inSession: Boolean,
    onActivate: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onLock: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxSize()
            .background(SkerryColors.deep2)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandLogo()
        Box(Modifier.width(10.dp))
        Text("Skerry", color = SkerryColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        Row(
            Modifier
                .weight(1f)
                .padding(start = 24.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sessions.sessions.forEach { session ->
                SessionTab(
                    title = session.title.ifBlank { "сессия" },
                    state = session.controller.uiState,
                    active = inSession && session.id == sessions.activeId,
                    onClick = { onActivate(session.id) },
                    onClose = { onCloseTab(session.id) },
                )
            }
            ChromeIconButton(SkerryIconKind.Add, onClick = onNewTab, modifier = Modifier.size(26.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onLock != null) LockIndicator(onLock = onLock)
            // Настройки появятся позже (AI/Sync/Security из прототипа) — слот виден, но отключён.
            ChromeIconButton(SkerryIconKind.Tune, onClick = {}, enabled = false)
        }
    }
}

// ===================== SIDEBAR =====================

@Composable
private fun HostSidebar(
    hosts: List<Host>,
    query: String,
    onQuery: (String) -> Unit,
    selectedId: String?,
    statusOf: (String) -> ConnectionUiState?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onManageIdentities: (() -> Unit)?,
) {
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val filtered = remember(hosts, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            hosts
        } else {
            hosts.filter {
                it.label.lowercase().contains(q) ||
                    it.address.lowercase().contains(q) ||
                    it.username.lowercase().contains(q)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(SkerryColors.nightSeaSoft)) {
        SidebarSearch(query, onQuery)
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            SectionLabel("Соединения")
            if (filtered.isEmpty()) {
                Text(
                    if (hosts.isEmpty()) "Пока нет хостов" else "Ничего не найдено",
                    color = SkerryColors.textFaint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val groups = filtered.groupBy { it.group }
            val ordered = groups.keys.sortedWith(compareBy(nullsLast()) { it })
            ordered.forEach { group ->
                val name = group ?: "Без группы"
                val isCollapsed = collapsed[name] == true
                GroupHeader(
                    name = name,
                    count = groups.getValue(group).size,
                    collapsed = isCollapsed,
                    onToggle = { collapsed[name] = !isCollapsed },
                )
                if (!isCollapsed) {
                    groups.getValue(group).forEach { host ->
                        ConnRow(
                            host = host,
                            selected = host.id == selectedId,
                            state = statusOf(host.id),
                            onClick = { onSelect(host.id) },
                        )
                    }
                }
            }
        }

        SidebarFooter(onManageIdentities = onManageIdentities, onNew = onNew)
    }
}

@Composable
private fun SidebarSearch(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.padding(12.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, SkerryColors.line, RoundedCornerShape(7.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkerryIcon(SkerryIconKind.Search, tint = SkerryColors.textFaint, size = 16.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Поиск соединений…", color = SkerryColors.textFaint, fontSize = 12.5.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = SkerryColors.text, fontSize = 12.5.sp),
                    cursorBrush = SolidColor(SkerryColors.cyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = SkerryColors.textFaint,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupHeader(name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SkerryIcon(
            SkerryIconKind.Chevron,
            tint = SkerryColors.textFaint,
            size = 16.dp,
            modifier = Modifier.rotate(if (collapsed) -90f else 0f),
        )
        SkerryIcon(SkerryIconKind.Folder, tint = SkerryColors.textDim, size = 15.dp)
        Text(
            name,
            color = SkerryColors.textDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text("$count", color = SkerryColors.textFaint, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ConnRow(host: Host, selected: Boolean, state: ConnectionUiState?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 6.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) SkerryColors.cyanSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor(state)))
        Text(
            host.label,
            color = if (selected) SkerryColors.cyanBright else SkerryColors.textDim,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SidebarFooter(onManageIdentities: (() -> Unit)?, onNew: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onManageIdentities != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onManageIdentities)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkerryIcon(SkerryIconKind.Key, tint = SkerryColors.textDim, size = 16.dp)
                    Text("Ключи и пароли", color = SkerryColors.textDim, fontSize = 12.sp)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(SkerryColors.cyan)
                    .clickable(onClick = onNew)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                SkerryIcon(SkerryIconKind.Add, tint = SkerryColors.deep2, size = 16.dp)
                Box(Modifier.width(6.dp))
                Text("Новое соединение", color = SkerryColors.deep2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ===================== MAIN AREA =====================

@Composable
private fun MainArea(
    inSession: Boolean,
    activeSession: Session?,
    managingIdentities: Boolean,
    identities: IdentityManagerController?,
    editing: HostDraft?,
    selectedHost: Host?,
    mono: FontFamily,
    onCloseActive: () -> Unit,
    onCloseIdentities: () -> Unit,
    onSaveDraft: (HostDraft) -> Unit,
    onCancelEdit: () -> Unit,
    onDeleteDraft: (String) -> Unit,
    onConnect: (Host, SshAuth) -> Unit,
    onEditHost: (Host) -> Unit,
    onDeleteHost: (Host) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            inSession && activeSession != null -> {
                when (val state = activeSession.controller.uiState) {
                    is ConnectionUiState.Connecting -> ConnectingIndicator()
                    is ConnectionUiState.Connected -> Column(Modifier.fillMaxSize()) {
                        DesktopSessionBar(
                            title = activeSession.subtitle,
                            meta = "SSH · интерактивный shell",
                            onDisconnect = onCloseActive,
                            mono = mono,
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))
                        TerminalScreen(state.terminal, Modifier.weight(1f))
                    }

                    is ConnectionUiState.Error -> ConnectionError(message = state.message, onBack = onCloseActive)
                    is ConnectionUiState.Form -> EmptyState()
                }
            }

            managingIdentities && identities != null ->
                IdentityManagerPanel(identities, onClose = onCloseIdentities)

            editing != null -> HostEditor(
                draft = editing,
                identities = identities?.identities ?: emptyList(),
                onSave = onSaveDraft,
                onCancel = onCancelEdit,
                onDelete = editing.id?.let { id -> { onDeleteDraft(id) } },
            )

            selectedHost != null -> HostConnectPanel(
                host = selectedHost,
                identity = identities?.find(selectedHost.identityId),
                onConnect = { auth -> onConnect(selectedHost, auth) },
                onEdit = { onEditHost(selectedHost) },
                onDelete = { onDeleteHost(selectedHost) },
            )

            else -> EmptyState()
        }
    }
}

@Composable
private fun HostConnectPanel(
    host: Host,
    identity: Identity?,
    onConnect: (SshAuth) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var password by remember(host.id) { mutableStateOf("") }
    Column(
        modifier = Modifier.widthIn(max = 380.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(host.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "${host.username}@${host.address}:${host.port}",
            style = MaterialTheme.typography.bodyMedium,
            color = SkerryColors.textDim,
        )
        if (identity != null) {
            Text(
                "Секрет: ${identity.label} · ${identity.auth.kindLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = SkerryColors.textDim,
            )
            Button(onClick = { onConnect(identity.toSshAuth()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Подключиться")
            }
        } else {
            if (host.identityId != null) {
                Text(
                    "Привязанный секрет не найден — удалён из хранилища. Введите пароль или " +
                        "переназначьте секрет в редакторе хоста.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConnect(SshAuth.Password(password)) },
                enabled = password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Подключиться")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Изменить") }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Удалить") }
        }
    }
}

private fun Identity.toSshAuth(): SshAuth = when (val a = auth) {
    is IdentityAuth.Password -> SshAuth.Password(a.password)
    is IdentityAuth.PrivateKey -> SshAuth.PublicKey(a.privateKeyPem, a.passphrase)
}

@Composable
private fun HostEditor(
    draft: HostDraft,
    identities: List<Identity>,
    onSave: (HostDraft) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember(draft) { mutableStateOf(draft.label) }
    var address by remember(draft) { mutableStateOf(draft.address) }
    var port by remember(draft) { mutableStateOf(draft.port.toString()) }
    var username by remember(draft) { mutableStateOf(draft.username) }
    var group by remember(draft) { mutableStateOf(draft.group ?: "") }
    var identityId by remember(draft) { mutableStateOf(draft.identityId) }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val canSave = label.isNotBlank() && address.isNotBlank() && username.isNotBlank() && portNumber != null

    Column(
        modifier = Modifier.widthIn(max = 380.dp).padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (draft.id == null) "Новый хост" else "Изменить хост",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Хост") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Порт") },
            singleLine = true,
            isError = portNumber == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Пользователь") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = group, onValueChange = { group = it }, label = { Text("Группа (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        IdentityPicker(identities = identities, selectedId = identityId, onPick = { identityId = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (canSave) {
                        onSave(
                            draft.copy(
                                label = label.trim(),
                                address = address.trim(),
                                port = portNumber,
                                username = username.trim(),
                                group = group.trim().ifBlank { null },
                                identityId = identityId,
                            ),
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("Сохранить")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun IdentityPicker(identities: List<Identity>, selectedId: String?, onPick: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = identities.firstOrNull { it.id == selectedId }?.label?.ifBlank { "(без имени)" }
        ?: "Пароль при подключении"
    Column(Modifier.fillMaxWidth()) {
        Text("Секрет", style = MaterialTheme.typography.labelSmall, color = SkerryColors.textFaint)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, color = SkerryColors.text)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Пароль при подключении") },
                    onClick = { onPick(null); expanded = false },
                )
                identities.forEach { identity ->
                    DropdownMenuItem(
                        text = { Text(identity.label.ifBlank { "(без имени)" }) },
                        onClick = { onPick(identity.id); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandLogo(size = 40.dp)
        Text("Выберите хост или создайте новое соединение", color = SkerryColors.textDim, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConnectingIndicator() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text("Подключение…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionError(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = 380.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Не удалось подключиться", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.onSurface)
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Закрыть вкладку") }
    }
}
