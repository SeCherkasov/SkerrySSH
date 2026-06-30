package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiRole
import app.skerry.ui.sync.AccountCardModel
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.accountCardModel
import app.skerry.ui.terminal.TERMINAL_FONT_SIZES
import app.skerry.ui.terminal.TerminalFont
import kotlinx.coroutines.launch

/** Панель настроек (модалка 760×560): nav 200dp + контент с 8 секциями (AI/Appearance/…/About). */
@Composable
fun SettingsPanel(state: DesktopDesignState) {
    val noop = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0xA6060E16)).clickable(interactionSource = noop, indication = null, onClick = state::closeSettings),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .width(760.dp)
                .height(560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {}),
        ) {
            // AI-таб виден, когда либо включён флаг незавершённых AI-поверхностей, либо подключён
            // живой контроллер ассистента (реальный BYOK-провайдер за гейтом vault). Иначе таб скрыт,
            // а дефолтный выбор (state.settingsTab = AI, как в прототипе) проецируется на Account.
            val features = LocalFeatures.current
            val aiVisible = features.ai || LocalAi.current != null
            val effectiveTab = if (state.settingsTab == SettingsTab.AI && !aiVisible) SettingsTab.Account else state.settingsTab
            Column(Modifier.width(200.dp).fillMaxHeight().background(Color(0x33000000)).padding(horizontal = 8.dp, vertical = 16.dp)) {
                Txt("SETTINGS", color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                SETTINGS_NAV.filter { aiVisible || it.tab != SettingsTab.AI }.forEach { item ->
                    NavRow(item, active = effectiveTab == item.tab, onClick = { state.showSettingsTab(item.tab) })
                }
            }
            VLine(D.line)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 22.dp)) {
                when (effectiveTab) {
                    SettingsTab.AI -> AiSection(state)
                    SettingsTab.Appearance -> AppearanceSection(state)
                    SettingsTab.Terminal -> TerminalSection()
                    SettingsTab.Account -> AccountSection(state)
                    SettingsTab.Sync -> SyncSection(state)
                    SettingsTab.Security -> SecuritySection()
                    SettingsTab.Keyboard -> KeyboardSection()
                    SettingsTab.About -> AboutSection()
                }
            }
        }
    }
}

@Composable
private fun NavRow(item: SettingsNavItem, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 1.dp).clip(RoundedCornerShape(6.dp)).background(if (active) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(item.icon, size = 16.sp, color = if (active) D.cyanBright else D.dim)
        Txt(item.name, color = if (active) D.cyanBright else D.dim, size = 12.5.sp)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Txt(title, color = D.text, size = 16.sp, weight = FontWeight.SemiBold)
    Txt(subtitle, color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
}

@Composable
private fun SettingToggleRow(title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            if (desc.isNotEmpty()) Txt(desc, color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Toggle(on, onToggle, Modifier.padding(top = 2.dp))
    }
}

// AI.

@Composable
private fun AiSection(state: DesktopDesignState) {
    val ai = LocalAi.current
    if (ai != null) LiveAiSection(ai) else AiSectionMock(state)
}

/**
 * Живой AI-таб: BYOK-настройки внешнего OpenAI-совместимого провайдера (ключ шифруется в vault) и
 * быстрый чат для проверки соединения. Полноценный ассистент в терминале (per-host политики,
 * подтверждение команд) — отдельный слайс; здесь вывод модели только показывается, не исполняется.
 */
@Composable
private fun LiveAiSection(ai: app.skerry.ui.ai.AiAssistantController) {
    SectionTitle("AI", "Connect your own OpenAI-compatible provider (BYOK). Your key is stored encrypted in the vault and only ever sent to the provider you configure.")

    var key by remember(ai.settings) { mutableStateOf(ai.settings.apiKey) }
    var model by remember(ai.settings) { mutableStateOf(ai.settings.model) }
    var baseUrl by remember(ai.settings) { mutableStateOf(ai.settings.baseUrl) }

    FieldLabel("OPENAI API KEY", top = 4.dp)
    SyncField(placeholder = "sk-…", value = key, icon = "key", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { key = it }
    FieldLabel("MODEL")
    SyncField(placeholder = "gpt-4o-mini", value = model, icon = "auto_awesome", keyboardType = KeyboardType.Text, imeAction = ImeAction.Next) { model = it }
    FieldLabel("ENDPOINT")
    SyncField(placeholder = "https://api.openai.com/v1", value = baseUrl, icon = "cloud", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done) { baseUrl = it }

    Box(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RevokeChip("Save", fg = D.cyan) { ai.save(key, model, baseUrl) }
        if (ai.isConfigured) Txt("Key saved", color = D.moss, size = 11.5.sp)
        else Txt("Not configured yet", color = D.faint, size = 11.5.sp)
    }

    Box(Modifier.padding(top = 18.dp)); HLine(); Box(Modifier.height(12.dp))
    Txt("Quick chat", color = D.text, size = 13.sp, weight = FontWeight.Medium)
    Txt("Ask the assistant a question to verify the connection.", color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

    ai.turns.forEach { turn -> ChatBubble(turn.role, turn.text) }
    ai.streaming?.let { ChatBubble(AiRole.ASSISTANT, if (it.isEmpty()) "…" else it) }
    ai.error?.let { Txt(it, color = D.storm, size = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }

    Box(Modifier.height(8.dp))
    var prompt by remember { mutableStateOf("") }
    val send = { if (prompt.isNotBlank() && !ai.busy) { ai.ask(prompt); prompt = "" } }
    SyncField(
        placeholder = if (ai.isConfigured) "Ask about ssh, scp, tunnels…" else "Save an API key first",
        value = prompt,
        icon = "chat",
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Send,
        onSubmit = send,
    ) { prompt = it }
    Box(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RevokeChip(if (ai.busy) "Sending…" else "Ask", fg = if (ai.isConfigured && !ai.busy) D.cyan else D.faint) { send() }
        if (ai.turns.isNotEmpty()) RevokeChip("Clear", fg = D.dim) { ai.clearConversation() }
    }
}

@Composable
private fun ChatBubble(role: AiRole, text: String) {
    val mine = role == AiRole.USER
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (mine) D.cyan10 else Color(0x0DFFFFFF))
                .border(1.dp, if (mine) D.cyan14 else D.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Txt(text, color = if (mine) D.text else D.dim, size = 12.5.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun AiSectionMock(state: DesktopDesignState) {
    SectionTitle("AI", "Configure where AI runs. The default is on this device — your commands never leave Skerry. Each connection can override these settings.")
    Txt("Default AI provider", color = D.text, size = 13.sp, weight = FontWeight.Medium)
    Txt("Used for connections without a specific override.", color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
    ProviderCard("lock", "On this device", "Qwen 2.5 Coder 1.5B · 982 MB · ready. No data leaves your machine.", selected = true, badge = "PRIVATE")
    Box(Modifier.height(8.dp))
    ProviderCard("cloud", "Custom endpoint", "Connect to your own Ollama or OpenAI-compatible server.", selected = false)
    Box(Modifier.height(8.dp))
    ProviderCard("key", "My API key (BYOK)", "Use your OpenAI, Anthropic, or OpenRouter key. You pay the provider directly.", selected = false)
    Box(Modifier.padding(top = 18.dp)); HLine(); Box(Modifier.height(6.dp))
    SettingToggleRow("Sanitize secrets before sending", "Strip obvious passwords, tokens and keys from prompts before they reach external providers.", state.sanitize, state::toggleSanitize)
    SettingToggleRow("Preview prompt before sending", "Show the exact text sent externally, with redactions highlighted. Edit or cancel before transmission.", state.preview, state::togglePreview)
    SettingToggleRow("Confirm before running AI suggestions", "Every AI-suggested command requires a deliberate click. Mitigates prompt injection from compromised servers.", state.confirm, state::toggleConfirm)
}

@Composable
private fun ProviderCard(icon: String, title: String, desc: String, selected: Boolean, badge: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) D.cyan else D.cyan08, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) D.cyan.copy(alpha = 0.2f) else Color(0x0DFFFFFF)), contentAlignment = Alignment.Center) {
            Sym(icon, size = 18.sp, color = if (selected) D.cyan else D.dim)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
                if (badge != null) Badge(badge, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, radius = 3, size = 9.5.sp)
            }
            Txt(desc, color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(
            Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape).background(if (selected) D.cyan else Color.Transparent).border(1.5.dp, if (selected) D.cyan else D.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Sym("check", size = 12.sp, color = Color(0xFF0A1A26))
        }
    }
}

// Appearance.

@Composable
private fun AppearanceSection(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    SectionTitle("Appearance", "Pick a terminal theme. Themes apply per-connection or globally.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeCard("Night Sea", active = true, mono = mono, modifier = Modifier.weight(1f))
        ThemeCard("Tokyo Night", active = false, mono = mono, modifier = Modifier.weight(1f))
    }
    Box(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeCard("Gruvbox Dark", active = false, mono = mono, modifier = Modifier.weight(1f))
        ThemeCard("Solarized Light", active = false, mono = mono, modifier = Modifier.weight(1f))
    }
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Txt("Font", color = D.text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
            FontPicker(state.terminalFont, onPick = state::chooseTerminalFont)
        }
        Column(Modifier.weight(1f)) {
            Txt("Font size", color = D.text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
            FontSizePicker(state.terminalFontSize, onPick = state::chooseTerminalFontSize)
        }
    }
}

/** Выпадающий список шрифта терминала (Hack / JetBrains Mono) — оба без лигатур. */
@Composable
private fun FontPicker(current: TerminalFont, onPick: (TerminalFont) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            SelectTrigger(current.displayName, onClick = { open = !open })
        },
        menu = { width ->
            DropdownMenuColumn(width) {
                TerminalFont.entries.forEach { option ->
                    DropdownOption(option.displayName, selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Выпадающий список кегля шрифта терминала ([TERMINAL_FONT_SIZES], px). */
@Composable
private fun FontSizePicker(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            SelectTrigger("$current px", onClick = { open = !open })
        },
        menu = { width ->
            DropdownMenuColumn(width) {
                TERMINAL_FONT_SIZES.forEach { size ->
                    DropdownOption("$size px", selected = size == current) { onPick(size); open = false }
                }
            }
        },
    )
}

/** Триггер селекта макета: значение слева, шеврон справа (как статичный [SettingsSelect], но кликабельный). */
@Composable
private fun SelectTrigger(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).clickable(onClick = onClick).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 12.5.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}

/** Колонка-меню выпадающего списка (поверхность + обводка макета). */
@Composable
private fun DropdownMenuColumn(width: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(8.dp)).background(D.surface2).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)),
    ) { content() }
}

/** Пункт выпадающего списка; выбранный подсвечен cyan. */
@Composable
private fun DropdownOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Txt(
        label,
        color = if (selected) D.cyanBright else D.text,
        size = 12.5.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun ThemeCard(name: String, active: Boolean, mono: FontFamily, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, if (active) D.cyan else D.cyan08, RoundedCornerShape(8.dp))) {
        Column(Modifier.fillMaxWidth().background(D.terminalBg).padding(10.dp)) {
            Row { Txt("~ ", color = D.moss, size = 10.sp, font = mono); Txt("ls -la", color = D.white, size = 10.sp, font = mono) }
            Row { Txt("drwxr-xr-x ", color = D.cyanBright, size = 10.sp, font = mono); Txt("src", color = D.textMid, size = 10.sp, font = mono) }
            Row { Txt("-rw-r--r-- ", color = D.dim, size = 10.sp, font = mono); Txt(".env", color = D.amber, size = 10.sp, font = mono) }
        }
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(name, color = D.text, size = 11.5.sp, weight = FontWeight.Medium)
            if (active) Badge("ACTIVE", bg = D.cyan14, fg = D.cyanBright, radius = 3, size = 9.sp)
        }
    }
}

// Terminal.

@Composable
private fun TerminalSection() {
    SectionTitle("Terminal", "Shell behaviour, scrollback and bell.")
    SettingValueRow("Scrollback buffer", "Lines kept per session.", "10 000")
    HLine()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt("Cursor style", color = D.text, size = 13.sp, weight = FontWeight.Medium) }
        Box(Modifier.width(160.dp)) { SettingsSelect("Block (blink)") }
    }
    HLine()
    SettingValueRow("Copy on select", "", "On")
    HLine()
    // ЗАГЛУШКА (техдолг, см. память tab-title-host-label): сейчас вкладка всегда показывает лейбл
    // хоста, а живой OSC 0/2-заголовок терминала (vim, htop, root@host…) игнорируется. Когда дойдут
    // руки — этот тоггл должен включать ветку effectiveTabTitle в Session.displayTitle. Пока он
    // визуально present, но выключен и без обработчика (как и прочие мок-настройки этой секции).
    SettingToggleRow(
        "Show terminal title on tabs",
        "Let the connected shell rename a tab (e.g. the running program). Off — tabs always show the saved host name. Not implemented yet.",
        on = false,
        onToggle = {},
    )
}

// Account.

@Composable
private fun AccountSection(state: DesktopDesignState) {
    SectionTitle("Account", "Your Skerry account and the devices linked to it.")
    // Реальная модель — self-hosted zero-knowledge sync (без биллинга/PRO): карточка отражает живое
    // состояние из координатора. Превью/офскрин (нет бэкенда) — локальный vault с «Set up sync».
    val sync = LocalSync.current
    if (sync == null) {
        AccountCard(accountCardModel(null), sync = null, state = state)
    } else {
        LiveAccountSection(sync, state)
    }
}

/** Живая карточка аккаунта: безусловный collectAsState внутри своего composable. */
@Composable
private fun LiveAccountSection(sync: app.skerry.ui.sync.SyncCoordinator, state: DesktopDesignState) {
    val status = sync.status.collectAsState().value
    val model = accountCardModel(status, sync.savedConfig?.serverUrl)
    AccountCard(model, sync, state)
    // Список устройств серверу известен только при активной сессии (Online) — иначе нечем спрашивать.
    if (model.connected) LinkedDevices(sync, onLink = state::openPairing)
}

/** Карточка профиля: аватар + заголовок/подпись + действия по состоянию (set up / reconnect / sync·disconnect). */
@Composable
private fun AccountCard(model: AccountCardModel, sync: app.skerry.ui.sync.SyncCoordinator?, state: DesktopDesignState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(D.cyan), contentAlignment = Alignment.Center) {
            Txt(model.initials, color = Color(0xFF0A1A26), size = 14.sp, weight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Txt(model.title, color = D.text, size = 13.5.sp, weight = FontWeight.Medium)
            Txt(model.subtitle, color = D.faint, size = 11.5.sp)
        }
        // Account владеет жизненным циклом ПОДКЛЮЧЕНИЯ (set up / reconnect / disconnect). Действие
        // «Sync now» здесь НЕ дублируем — оно про движок синка и живёт во вкладке Sync.
        when {
            model.connected && sync != null -> GhostButton("Disconnect", onClick = { sync.disconnect() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
            model.linked -> PrimaryButton("Reconnect", onClick = state::openSyncSetup, icon = "cloud_sync")
            else -> PrimaryButton("Set up sync", onClick = state::openSyncSetup, icon = "cloud_sync")
        }
    }
}

/** Реальные устройства аккаунта ([SyncCoordinator.listDevices]); Revoke отзывает чужое и перечитывает список. */
@Composable
private fun LinkedDevices(sync: app.skerry.ui.sync.SyncCoordinator, onLink: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<app.skerry.shared.sync.RemoteDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // reload++ заставляет LaunchedEffect перечитать список после отзыва устройства.
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(sync, reload) {
        loading = true
        // Отозванные устройства больше не привязаны — не показываем (сервер хранит строку с revoked=true).
        // Текущее устройство всегда первым (sortedByDescending стабилен — порядок прочих сохраняется).
        devices = sync.listDevices().filter { !it.revoked }.sortedByDescending { it.current }
        loading = false
    }

    Txt("LINKED DEVICES", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
    when {
        loading -> Txt("Loading devices…", color = D.faint, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        // На активной сессии сервер всегда возвращает хотя бы текущее устройство; пустой список =
        // listDevices проглотил ошибку (нет связи/протух токен) — честно говорим, а не «только вы».
        devices.isEmpty() -> Txt("Couldn't load devices. Try Sync now.", color = D.amber, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.size == 1 && devices.first().current -> Txt("Only this device so far.", color = D.faint, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        else -> devices.forEach { d ->
            DeviceRow(
                icon = "devices",
                name = d.name,
                sub = if (d.current) "linked · this device" else "linked device",
                thisDevice = d.current,
                onRevoke = if (d.current || d.revoked) null else {
                    { scope.launch { if (sync.revokeDevice(d.id)) reload++ } }
                },
            )
        }
    }
    // Быстрый паринг: показать новому устройству QR/код, чтобы привязать его без мастер-пароля аккаунта.
    GhostButton("Link a device", onClick = onLink, icon = "qr_code", modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun DeviceRow(icon: String, name: String, sub: String, trailing: String? = null, onRevoke: (() -> Unit)? = null, thisDevice: Boolean = false) {
    // Отзыв необратим из UI (устройство переподключается мастер-паролем) — требуем подтверждение
    // вторым кликом, чтобы случайный промах по списку не разлогинил рабочее устройство.
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, size = 18.sp, color = D.dim)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(name, color = D.text, size = 13.sp, weight = FontWeight.Medium)
                if (thisDevice) Txt("● this device", color = D.moss, size = 10.sp)
            }
            Txt(sub, color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Txt(trailing, color = D.faint, size = 11.sp)
        if (onRevoke != null) {
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RevokeChip("Confirm", D.sunset) { confirming = false; onRevoke() }
                    RevokeChip("Cancel", D.dim) { confirming = false }
                }
            } else {
                RevokeChip("Revoke", D.dim) { confirming = true }
            }
        }
    }
}

/** Маленькая обведённая кнопка-чип в строке устройства (Revoke/Confirm/Cancel). */
@Composable
private fun RevokeChip(label: String, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Txt(label, color = fg, size = 11.5.sp)
    }
}

// Sync.

@Composable
private fun SyncSection(state: DesktopDesignState) {
    SectionTitle("Sync", "End-to-end encrypted sync across your devices. Skerry never sees your data in plaintext.")
    // Мок-путь и живой путь — разные composable (а не условный remember/collectAsState в одном теле):
    // rememberCoroutineScope/collectAsState должны вызываться безусловно в своём composable (правило
    // слотовой таблицы Compose). LocalSync.current стабилен (staticCompositionLocalOf), но строгий
    // паттерн — ветвление на отдельные функции, каждая со своими remember-вызовами.
    val sync = LocalSync.current
    if (sync == null) {
        // Мок-путь/превью без бэкенда: статичная карточка макета (подключённое состояние).
        SyncStatusCard("cloud_done", D.moss, "Synced 2 minutes ago", "9 hosts · 4 snippets · 3 keys · 2 vaults") {
            GhostButton("Sync now", onClick = {})
        }
    } else {
        LiveSyncStatus(sync, state)
    }
    Txt("WHAT SYNCS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    if (sync == null) {
        // Превью без бэкенда: статичные тумблеры (как в макете).
        SettingToggleRow("Hosts & groups", "", on = true, onToggle = {})
        SettingToggleRow("Snippets", "", on = true, onToggle = {})
    } else {
        WhatSyncsToggles(sync)
    }
}

/**
 * Живые тумблеры «что синхронизировать» (уровень аккаунта): пишут [SyncSettings] в vault через
 * координатор, изменение уезжает тем же live-push. «SSH keys» и «Terminal history» из макета убраны
 * сознательно: ключи нужны для аутентификации хостов и синкаются всегда вместе с «Hosts & groups»
 * (отдельный выключатель сломал бы связки host→credential), а истории терминала как фичи ещё нет.
 */
@Composable
private fun WhatSyncsToggles(sync: app.skerry.ui.sync.SyncCoordinator) {
    val settings = sync.syncSettings.collectAsState().value
    LaunchedEffect(Unit) { sync.refreshSyncSettings() } // vault уже открыт на экране настроек
    // В onToggle читаем АКТУАЛЬНОЕ значение из flow, не снимок композиции: иначе быстрый второй тап
    // (по другому тумблеру) до перерисовки откатил бы первый (stale-closure write-write).
    SettingToggleRow("Hosts & groups", "", on = settings.syncHosts, onToggle = {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncHosts = !current.syncHosts))
    })
    SettingToggleRow("Snippets", "", on = settings.syncSnippets, onToggle = {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncSnippets = !current.syncSnippets))
    })
}

/** Живой статус sync: безусловный collectAsState внутри своего composable (операции — на scope координатора). */
@Composable
private fun LiveSyncStatus(sync: app.skerry.ui.sync.SyncCoordinator, state: DesktopDesignState) {
    // Sync владеет ДВИЖКОМ синхронизации: статус + «Sync now». Подключение/отвязка/устройства живут
    // во вкладке Account — здесь их НЕ дублируем; в несоединённых состояниях ведём в Account.
    val toAccount = { state.showSettingsTab(SettingsTab.Account) }
    when (val status = sync.status.collectAsState().value) {
        is SyncStatus.Online -> SyncStatusCard(
            "cloud_done", D.moss,
            "Connected · ${status.accountId}",
            "Pushed ${status.lastPushed} · pulled ${status.lastPulled} this session",
        ) {
            GhostButton("Sync now", onClick = { sync.syncNow() })
        }
        SyncStatus.Busy -> SyncStatusCard("sync", D.cyanBright, "Syncing…", "Talking to your sync server.") {}
        is SyncStatus.Configured -> SyncStatusCard("cloud_off", D.amber, "Linked · ${status.accountId}", "Reconnect from Account to resume syncing.") {
            GhostButton("Open Account", onClick = toAccount)
        }
        is SyncStatus.Failed -> SyncStatusCard("cloud_off", D.sunset, "Sync error", status.message) {
            GhostButton("Open Account", onClick = toAccount)
        }
        SyncStatus.Disabled -> SyncStatusCard("cloud_off", D.faint, "Not connected", "Connect your account to sync across devices.") {
            GhostButton("Open Account", onClick = toAccount)
        }
    }
}

/** Карточка статуса sync: иконка + заголовок/подпись + правый слот (кнопки действий). */
@Composable
private fun SyncStatusCard(icon: String, iconColor: Color, title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, size = 20.sp, color = iconColor)
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        action()
    }
}

// Security.

@Composable
private fun SecuritySection() {
    SectionTitle("Security", "Master password, auto-lock and two-factor authentication protect your vault.")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt("Master password", color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt("Last changed 84 days ago.", color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        GhostButton("Change", onClick = {})
    }
    HLine()
    SettingToggleRow("Unlock with Touch ID", "Use biometrics instead of typing the master password.", on = true, onToggle = {})
    HLine()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt("Auto-lock", color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt("Lock Skerry after inactivity.", color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(170.dp)) { SettingsSelect("After 5 minutes") }
    }
    HLine()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Two-factor authentication", color = D.text, size = 13.sp, weight = FontWeight.Medium)
                Txt("● enabled", color = D.moss, size = 10.sp)
            }
            Txt("TOTP via authenticator app.", color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        GhostButton("Manage", onClick = {})
    }
    Txt("RECENT SECURITY EVENTS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    listOf(
        "Unlocked with Touch ID · today 09:02",
        "Key deploy_ci accessed · Jun 20 18:44",
        "New device paired: iPhone 16 Pro · Jun 18",
    ).forEach {
        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("●", color = D.moss, size = 9.sp)
            Txt(it, color = D.dim, size = 12.sp)
        }
    }
}

// Keyboard.

@Composable
private fun KeyboardSection() {
    SectionTitle("Keyboard", "Shortcuts. Click any binding to rebind it.")
    val shortcuts = listOf(
        "New connection" to "⌘N",
        "Command palette / search" to "⌘K",
        "Split terminal" to "⌘D",
        "Next / previous tab" to "⌃Tab",
        "Focus AI bar" to "⌃/",
        "Open SFTP" to "⌘⇧F",
        "Lock Skerry" to "⌘L",
    )
    val mono = LocalFonts.current.mono
    shortcuts.forEach { (label, key) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(label, color = D.textBright, size = 12.5.sp)
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x0AFFFFFF)).border(1.dp, D.cyan14, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Txt(key, color = D.dim, size = 11.sp, font = mono)
            }
        }
        HLine()
    }
}

// About.

@Composable
private fun AboutSection() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 20.dp).size(72.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A141B)), contentAlignment = Alignment.Center) {
            BrandMark(size = 72.dp)
        }
        Txt("Skerry", color = D.text, size = 20.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Txt("Version 2.4.0 · build 2026.06.21", color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Txt("A private-first SSH client. Local AI, end-to-end encrypted sync, and a terminal that respects your secrets.", color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp))
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("What's new", onClick = {})
            GhostButton("Documentation", onClick = {})
            GhostButton("Open-source licenses", onClick = {})
        }
        Txt("Built on the open seas · © 2026 Skerry", color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 20.dp))
    }
}

// Хелперы.

@Composable
private fun SettingValueRow(title: String, desc: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            if (desc.isNotEmpty()) Txt(desc, color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Txt(value, color = D.textBright, size = 12.5.sp)
    }
}

@Composable
private fun SettingsSelect(value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 12.5.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}
