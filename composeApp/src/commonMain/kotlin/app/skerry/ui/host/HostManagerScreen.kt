package app.skerry.ui.host

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.identity.IdentityManagerController
import app.skerry.ui.identity.IdentityManagerPanel
import app.skerry.ui.identity.kindLabel
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.theme.SkerryColors

/**
 * Менеджер хостов: слева — список сохранённых профилей с группами и кнопкой создания,
 * справа — контекстная область (плейсхолдер / редактор / панель подключения / терминал).
 *
 * Экран владеет одним [ConnectionController] (одна сессия за раз; вкладки придут позже).
 * Пока идёт подключение или открыт терминал, выбор в сайдбаре заблокирован — иначе можно
 * было бы «увести» область из-под живой сессии. [activeHostId] помечает подключённый хост
 * точкой в списке. CRUD идёт через [HostManagerController]; правка/создание — через
 * локальный [editing]-черновик поверх области.
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
    val connection = remember(transport) { ConnectionController(transport, scope) }
    // Уход с экрана (или смена transport) не отменяет SshConnection сам по себе — рвём явно,
    // иначе живой сокет утечёт. disconnect идёмпотентен и безопасен из Form-состояния.
    DisposableEffect(connection) {
        onDispose { connection.disconnect() }
    }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<HostDraft?>(null) }
    var activeHostId by remember { mutableStateOf<String?>(null) }
    var managingIdentities by remember { mutableStateOf(false) }

    val state = connection.uiState
    val sessionBusy = state is ConnectionUiState.Connecting || state is ConnectionUiState.Connected

    Row(modifier.fillMaxSize()) {
        HostSidebar(
            hosts = hosts.hosts,
            selectedId = selectedId,
            activeHostId = activeHostId,
            enabled = !sessionBusy,
            onSelect = { id -> selectedId = id; editing = null; managingIdentities = false },
            onNew = { selectedId = null; editing = HostDraft(label = "", address = "", username = ""); managingIdentities = false },
            onManageIdentities = identities?.let { { managingIdentities = true } },
            onLock = onLock,
        )
        Box(Modifier.fillMaxHeight().width(1.dp).background(SkerryColors.lineStrong))

        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (managingIdentities && identities != null) {
                IdentityManagerPanel(identities, onClose = { managingIdentities = false })
                return@Box
            }
            when (state) {
                is ConnectionUiState.Connecting -> ConnectingIndicator()

                is ConnectionUiState.Connected -> Column(Modifier.fillMaxSize()) {
                    val label = activeHostId?.let { hosts.find(it)?.label } ?: "Сессия"
                    SessionBar(label = label, onDisconnect = {
                        connection.disconnect()
                        activeHostId = null
                    })
                    TerminalScreen(state.terminal, Modifier.weight(1f))
                }

                is ConnectionUiState.Error -> ConnectionError(
                    message = state.message,
                    onBack = {
                        connection.dismissError()
                        activeHostId = null
                    },
                )

                is ConnectionUiState.Form -> {
                    val draft = editing
                    val selected = selectedId?.let(hosts::find)
                    when {
                        draft != null -> HostEditor(
                            draft = draft,
                            identities = identities?.identities ?: emptyList(),
                            onSave = { saved ->
                                selectedId = hosts.save(saved)
                                editing = null
                            },
                            onCancel = { editing = null },
                            onDelete = draft.id?.let { id ->
                                {
                                    hosts.delete(id)
                                    editing = null
                                    if (selectedId == id) selectedId = null
                                }
                            },
                        )

                        selected != null -> HostConnectPanel(
                            host = selected,
                            identity = identities?.find(selected.identityId),
                            onConnect = { auth ->
                                activeHostId = selected.id
                                connection.connect(selected.toTarget(), auth)
                            },
                            onEdit = { editing = selected.toDraft() },
                            onDelete = {
                                hosts.delete(selected.id)
                                selectedId = null
                            },
                        )

                        else -> EmptyState()
                    }
                }
            }
        }
    }
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

@Composable
private fun HostSidebar(
    hosts: List<Host>,
    selectedId: String?,
    activeHostId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onManageIdentities: (() -> Unit)?,
    onLock: (() -> Unit)?,
) {
    Column(Modifier.width(248.dp).fillMaxHeight().background(SkerryColors.deep2)) {
        Text(
            "Хосты",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(16.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (hosts.isEmpty()) {
                Text(
                    "Пока нет хостов",
                    color = SkerryColors.textFaint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // Группировка по полю group; профили без группы — в конце под «Без группы».
            val groups = hosts.groupBy { it.group }
            val ordered = groups.keys.sortedWith(compareBy(nullsLast()) { it })
            ordered.forEach { group ->
                Text(
                    group ?: "Без группы",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkerryColors.textFaint,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                groups.getValue(group).forEach { host ->
                    HostRow(
                        host = host,
                        selected = host.id == selectedId,
                        active = host.id == activeHostId,
                        enabled = enabled,
                        onClick = { onSelect(host.id) },
                    )
                }
            }
        }
        TextButton(
            onClick = onNew,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("+ Новый хост")
        }
        // Управление переиспользуемыми секретами доступно, только когда vault подключён.
        if (onManageIdentities != null) {
            TextButton(
                onClick = onManageIdentities,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("Ключи и пароли", color = SkerryColors.textDim)
            }
        }
        // Кнопка ручной блокировки vault видна только когда гейт активен (onLock != null).
        // Заблокировать можно и при живой сессии — teardown сделает DisposableEffect content.
        if (onLock != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(SkerryColors.line))
            TextButton(
                onClick = onLock,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("Заблокировать хранилище", color = SkerryColors.textDim)
            }
        }
    }
}

@Composable
private fun HostRow(
    host: Host,
    selected: Boolean,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) SkerryColors.cyanSoft else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (active) SkerryColors.moss else SkerryColors.textFaint),
        )
        Text(
            host.label,
            color = if (selected) MaterialTheme.colorScheme.primary else SkerryColors.text,
            style = MaterialTheme.typography.bodyMedium,
        )
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
            // Секрет привязан в редакторе хоста — подключаемся без ввода.
            Text(
                "Секрет: ${identity.label} · ${identity.auth.kindLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = SkerryColors.textDim,
            )
            Button(onClick = { onConnect(identity.toSshAuth()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Подключиться")
            }
        } else {
            // identityId был привязан, но секрет в vault не найден (удалён) — предупреждаем,
            // не выдавая молча форму пароля как будто секрет и не настраивали.
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
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Имя") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Хост") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Порт") },
            singleLine = true,
            isError = portNumber == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Пользователь") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = group,
            onValueChange = { group = it },
            label = { Text("Группа (необязательно)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
    Text(
        "Выберите хост или создайте новый",
        color = SkerryColors.textDim,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ConnectingIndicator() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text("Подключение…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionBar(label: String, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        TextButton(onClick = onDisconnect) { Text("Отключиться") }
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
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
}
