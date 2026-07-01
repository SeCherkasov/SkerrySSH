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
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_subtitle
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.generated.resources.settings_about_documentation
import app.skerry.ui.generated.resources.settings_about_footer
import app.skerry.ui.generated.resources.settings_about_licenses
import app.skerry.ui.generated.resources.settings_about_tagline
import app.skerry.ui.generated.resources.settings_about_whats_new
import app.skerry.ui.generated.resources.settings_account_subtitle
import app.skerry.ui.generated.resources.settings_account_title
import app.skerry.ui.generated.resources.settings_ai_ask
import app.skerry.ui.generated.resources.settings_ai_badge_private
import app.skerry.ui.generated.resources.settings_ai_confirm
import app.skerry.ui.generated.resources.settings_ai_confirm_desc
import app.skerry.ui.generated.resources.settings_ai_default_provider
import app.skerry.ui.generated.resources.settings_ai_default_provider_desc
import app.skerry.ui.generated.resources.settings_ai_field_api_key
import app.skerry.ui.generated.resources.settings_ai_field_endpoint
import app.skerry.ui.generated.resources.settings_ai_field_model
import app.skerry.ui.generated.resources.settings_ai_key_saved
import app.skerry.ui.generated.resources.settings_ai_live_subtitle
import app.skerry.ui.generated.resources.settings_ai_mock_subtitle
import app.skerry.ui.generated.resources.settings_ai_not_configured
import app.skerry.ui.generated.resources.settings_ai_preview
import app.skerry.ui.generated.resources.settings_ai_preview_desc
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_needs_key
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_ready
import app.skerry.ui.generated.resources.settings_ai_provider_byok
import app.skerry.ui.generated.resources.settings_ai_provider_byok_desc
import app.skerry.ui.generated.resources.settings_ai_provider_custom
import app.skerry.ui.generated.resources.settings_ai_provider_custom_desc
import app.skerry.ui.generated.resources.settings_ai_provider_device
import app.skerry.ui.generated.resources.settings_ai_provider_device_desc
import app.skerry.ui.generated.resources.settings_ai_quick_chat
import app.skerry.ui.generated.resources.settings_ai_quick_chat_desc
import app.skerry.ui.generated.resources.settings_ai_sanitize
import app.skerry.ui.generated.resources.settings_ai_sanitize_desc
import app.skerry.ui.generated.resources.settings_ai_sending
import app.skerry.ui.generated.resources.settings_ai_title
import app.skerry.ui.generated.resources.settings_badge_soon
import app.skerry.ui.generated.resources.settings_cancel
import app.skerry.ui.generated.resources.settings_change
import app.skerry.ui.generated.resources.settings_clear
import app.skerry.ui.generated.resources.settings_confirm
import app.skerry.ui.generated.resources.settings_device_sub_current
import app.skerry.ui.generated.resources.settings_device_sub_other
import app.skerry.ui.generated.resources.settings_devices_load_failed
import app.skerry.ui.generated.resources.settings_disconnect
import app.skerry.ui.generated.resources.settings_enabled
import app.skerry.ui.generated.resources.settings_hosts_groups
import app.skerry.ui.generated.resources.settings_kb_accept_autocomplete
import app.skerry.ui.generated.resources.settings_kb_command_palette
import app.skerry.ui.generated.resources.settings_kb_copy_selection
import app.skerry.ui.generated.resources.settings_kb_cycle_suggestions
import app.skerry.ui.generated.resources.settings_kb_focus_ai
import app.skerry.ui.generated.resources.settings_kb_global
import app.skerry.ui.generated.resources.settings_kb_lock
import app.skerry.ui.generated.resources.settings_kb_new_connection
import app.skerry.ui.generated.resources.settings_kb_next_prev_tab
import app.skerry.ui.generated.resources.settings_kb_open_sftp
import app.skerry.ui.generated.resources.settings_kb_paste
import app.skerry.ui.generated.resources.settings_kb_search_history
import app.skerry.ui.generated.resources.settings_kb_select_tab_number
import app.skerry.ui.generated.resources.settings_kb_split_terminal
import app.skerry.ui.generated.resources.settings_kb_terminal_group
import app.skerry.ui.generated.resources.settings_keyboard_subtitle
import app.skerry.ui.generated.resources.settings_keyboard_title
import app.skerry.ui.generated.resources.settings_link_device
import app.skerry.ui.generated.resources.settings_linked_devices
import app.skerry.ui.generated.resources.settings_loading_devices
import app.skerry.ui.generated.resources.settings_manage
import app.skerry.ui.generated.resources.settings_nav_header
import app.skerry.ui.generated.resources.settings_only_this_device
import app.skerry.ui.generated.resources.settings_open_account
import app.skerry.ui.generated.resources.settings_reconnect
import app.skerry.ui.generated.resources.settings_recent_security_events
import app.skerry.ui.generated.resources.settings_revoke
import app.skerry.ui.generated.resources.settings_save
import app.skerry.ui.generated.resources.settings_security_2fa
import app.skerry.ui.generated.resources.settings_security_2fa_desc
import app.skerry.ui.generated.resources.settings_security_after_5_min
import app.skerry.ui.generated.resources.settings_security_auto_lock
import app.skerry.ui.generated.resources.settings_security_auto_lock_desc
import app.skerry.ui.generated.resources.settings_security_event_1
import app.skerry.ui.generated.resources.settings_security_event_2
import app.skerry.ui.generated.resources.settings_security_event_3
import app.skerry.ui.generated.resources.settings_security_master_password
import app.skerry.ui.generated.resources.settings_security_master_password_desc
import app.skerry.ui.generated.resources.settings_security_subtitle
import app.skerry.ui.generated.resources.settings_security_title
import app.skerry.ui.generated.resources.settings_security_touch_id
import app.skerry.ui.generated.resources.settings_security_touch_id_desc
import app.skerry.ui.generated.resources.settings_set_up_sync
import app.skerry.ui.generated.resources.settings_snippets
import app.skerry.ui.generated.resources.settings_sync_connected
import app.skerry.ui.generated.resources.settings_sync_error
import app.skerry.ui.generated.resources.settings_sync_linked
import app.skerry.ui.generated.resources.settings_sync_linked_desc
import app.skerry.ui.generated.resources.settings_sync_not_connected
import app.skerry.ui.generated.resources.settings_sync_not_connected_desc
import app.skerry.ui.generated.resources.settings_sync_now
import app.skerry.ui.generated.resources.settings_sync_pushed_pulled
import app.skerry.ui.generated.resources.settings_sync_subtitle
import app.skerry.ui.generated.resources.settings_sync_summary_mock
import app.skerry.ui.generated.resources.settings_sync_synced_ago
import app.skerry.ui.generated.resources.settings_sync_syncing
import app.skerry.ui.generated.resources.settings_sync_syncing_desc
import app.skerry.ui.generated.resources.settings_sync_title
import app.skerry.ui.generated.resources.settings_terminal_cursor_bar_blink
import app.skerry.ui.generated.resources.settings_terminal_cursor_bar_steady
import app.skerry.ui.generated.resources.settings_terminal_cursor_block_blink
import app.skerry.ui.generated.resources.settings_terminal_cursor_block_steady
import app.skerry.ui.generated.resources.settings_terminal_cursor_underline_blink
import app.skerry.ui.generated.resources.settings_terminal_cursor_underline_steady
import app.skerry.ui.generated.resources.settings_terminal_cursor_style
import app.skerry.ui.generated.resources.settings_terminal_scrollback
import app.skerry.ui.generated.resources.settings_terminal_scrollback_desc
import app.skerry.ui.generated.resources.settings_terminal_show_title
import app.skerry.ui.generated.resources.settings_terminal_show_title_desc
import app.skerry.ui.generated.resources.settings_terminal_subtitle
import app.skerry.ui.generated.resources.settings_terminal_title
import app.skerry.ui.generated.resources.settings_this_device
import app.skerry.ui.generated.resources.settings_what_syncs
import app.skerry.ui.generated.resources.shtail_nav_about
import app.skerry.ui.generated.resources.shtail_nav_account
import app.skerry.ui.generated.resources.shtail_nav_ai
import app.skerry.ui.generated.resources.shtail_nav_appearance
import app.skerry.ui.generated.resources.shtail_nav_keyboard
import app.skerry.ui.generated.resources.shtail_nav_security
import app.skerry.ui.generated.resources.shtail_nav_sync
import app.skerry.ui.generated.resources.shtail_nav_terminal
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
import app.skerry.ui.sync.AccountCardModel
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.accountCardModelLocalized
import app.skerry.ui.terminal.TERMINAL_FONT_SIZES
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
                Txt(stringResource(Res.string.settings_nav_header), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                SETTINGS_NAV.filter { aiVisible || it.tab != SettingsTab.AI }.forEach { item ->
                    NavRow(item, active = effectiveTab == item.tab, onClick = { state.showSettingsTab(item.tab) })
                }
            }
            VLine(D.line)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 22.dp)) {
                when (effectiveTab) {
                    SettingsTab.AI -> AiSection(state)
                    SettingsTab.Appearance -> AppearanceSection(state)
                    SettingsTab.Terminal -> TerminalSection(state)
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
        Txt(item.tab.navLabel(), color = if (active) D.cyanBright else D.dim, size = 12.5.sp)
    }
}

/** Локализованная подпись пункта навигации настроек (данные [SettingsNavItem.name] — только fallback). */
@Composable
private fun SettingsTab.navLabel(): String = when (this) {
    SettingsTab.Account -> stringResource(Res.string.shtail_nav_account)
    SettingsTab.AI -> stringResource(Res.string.shtail_nav_ai)
    SettingsTab.Sync -> stringResource(Res.string.shtail_nav_sync)
    SettingsTab.Security -> stringResource(Res.string.shtail_nav_security)
    SettingsTab.Appearance -> stringResource(Res.string.shtail_nav_appearance)
    SettingsTab.Terminal -> stringResource(Res.string.shtail_nav_terminal)
    SettingsTab.Keyboard -> stringResource(Res.string.shtail_nav_keyboard)
    SettingsTab.About -> stringResource(Res.string.shtail_nav_about)
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
    SectionTitle(stringResource(Res.string.settings_ai_title), stringResource(Res.string.settings_ai_live_subtitle))

    var key by remember(ai.settings) { mutableStateOf(ai.settings.apiKey) }
    var model by remember(ai.settings) { mutableStateOf(ai.settings.model) }
    var baseUrl by remember(ai.settings) { mutableStateOf(ai.settings.baseUrl) }

    FieldLabel(stringResource(Res.string.settings_ai_field_api_key), top = 4.dp)
    SyncField(placeholder = "sk-…", value = key, icon = "key", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { key = it }
    FieldLabel(stringResource(Res.string.settings_ai_field_model))
    SyncField(placeholder = "gpt-4o-mini", value = model, icon = "auto_awesome", keyboardType = KeyboardType.Text, imeAction = ImeAction.Next) { model = it }
    FieldLabel(stringResource(Res.string.settings_ai_field_endpoint))
    SyncField(placeholder = "https://api.openai.com/v1", value = baseUrl, icon = "cloud", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done) { baseUrl = it }

    Box(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RevokeChip(stringResource(Res.string.settings_save), fg = D.cyan) { ai.save(key, model, baseUrl) }
        if (ai.isConfigured) Txt(stringResource(Res.string.settings_ai_key_saved), color = D.moss, size = 11.5.sp)
        else Txt(stringResource(Res.string.settings_ai_not_configured), color = D.faint, size = 11.5.sp)
    }

    Box(Modifier.padding(top = 18.dp)); HLine(); Box(Modifier.height(12.dp))
    Txt(stringResource(Res.string.settings_ai_quick_chat), color = D.text, size = 13.sp, weight = FontWeight.Medium)
    Txt(stringResource(Res.string.settings_ai_quick_chat_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

    ai.turns.forEach { turn -> ChatBubble(turn.role, turn.text) }
    ai.streaming?.let { ChatBubble(AiRole.ASSISTANT, if (it.isEmpty()) "…" else it) }
    ai.error?.let { Txt(it, color = D.storm, size = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }

    Box(Modifier.height(8.dp))
    var prompt by remember { mutableStateOf("") }
    val send = { if (prompt.isNotBlank() && !ai.busy) { ai.ask(prompt); prompt = "" } }
    SyncField(
        placeholder = if (ai.isConfigured) stringResource(Res.string.settings_ai_prompt_placeholder_ready) else stringResource(Res.string.settings_ai_prompt_placeholder_needs_key),
        value = prompt,
        icon = "chat",
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Send,
        onSubmit = send,
    ) { prompt = it }
    Box(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RevokeChip(if (ai.busy) stringResource(Res.string.settings_ai_sending) else stringResource(Res.string.settings_ai_ask), fg = if (ai.isConfigured && !ai.busy) D.cyan else D.faint) { send() }
        if (ai.turns.isNotEmpty()) RevokeChip(stringResource(Res.string.settings_clear), fg = D.dim) { ai.clearConversation() }
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
    SectionTitle(stringResource(Res.string.settings_ai_title), stringResource(Res.string.settings_ai_mock_subtitle))
    Txt(stringResource(Res.string.settings_ai_default_provider), color = D.text, size = 13.sp, weight = FontWeight.Medium)
    Txt(stringResource(Res.string.settings_ai_default_provider_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
    ProviderCard("lock", stringResource(Res.string.settings_ai_provider_device), stringResource(Res.string.settings_ai_provider_device_desc), selected = true, badge = stringResource(Res.string.settings_ai_badge_private))
    Box(Modifier.height(8.dp))
    ProviderCard("cloud", stringResource(Res.string.settings_ai_provider_custom), stringResource(Res.string.settings_ai_provider_custom_desc), selected = false)
    Box(Modifier.height(8.dp))
    ProviderCard("key", stringResource(Res.string.settings_ai_provider_byok), stringResource(Res.string.settings_ai_provider_byok_desc), selected = false)
    Box(Modifier.padding(top = 18.dp)); HLine(); Box(Modifier.height(6.dp))
    SettingToggleRow(stringResource(Res.string.settings_ai_sanitize), stringResource(Res.string.settings_ai_sanitize_desc), state.sanitize, state::toggleSanitize)
    SettingToggleRow(stringResource(Res.string.settings_ai_preview), stringResource(Res.string.settings_ai_preview_desc), state.preview, state::togglePreview)
    SettingToggleRow(stringResource(Res.string.settings_ai_confirm), stringResource(Res.string.settings_ai_confirm_desc), state.confirm, state::toggleConfirm)
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
    SectionTitle(stringResource(Res.string.appearance_title), stringResource(Res.string.appearance_subtitle))
    // Карточки тем сеткой 2×N из каталога [TerminalThemes]; выбор проводится в терминал на лету.
    TerminalThemes.all.chunked(2).forEachIndexed { rowIndex, rowThemes ->
        if (rowIndex > 0) Box(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (theme in rowThemes) {
                ThemeCard(
                    theme = theme,
                    active = theme.id == state.terminalTheme.id,
                    mono = mono,
                    onClick = { state.chooseTerminalTheme(theme) },
                    modifier = Modifier.weight(1f),
                )
            }
            // Нечётный хвост — добиваем пустой ячейкой, чтобы карточка не растянулась на всю ширину.
            if (rowThemes.size == 1) Box(Modifier.weight(1f))
        }
    }
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.appearance_font), color = D.text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
            FontPicker(state.terminalFont, onPick = state::chooseTerminalFont)
        }
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.appearance_font_size), color = D.text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
            FontSizePicker(state.terminalFontSize, onPick = state::chooseTerminalFontSize)
        }
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.appearance_language), color = D.text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
            LanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage)
        }
    }
}

/** Выпадающий список языка интерфейса (System / English / Русский). */
@Composable
private fun LanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            SelectTrigger(current.label(), onClick = { open = !open })
        },
        menu = { width ->
            DropdownMenuColumn(width) {
                UiLanguage.entries.forEach { option ->
                    DropdownOption(option.label(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
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

/**
 * Карточка выбора темы терминала: мини-превью `ls -la` в РЕАЛЬНЫХ цветах [theme] (фон/текст/ANSI) —
 * так пользователь видит палитру до применения. Клик выбирает тему; активная — cyan-рамка + бейдж.
 */
@Composable
private fun ThemeCard(
    theme: TerminalTheme,
    active: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) D.cyan else D.cyan08, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(theme.background).padding(10.dp)) {
            Row { Txt("~ ", color = theme.ansi[2], size = 10.sp, font = mono); Txt("ls -la", color = theme.foreground, size = 10.sp, font = mono) }
            Row { Txt("drwxr-xr-x ", color = theme.ansi[6], size = 10.sp, font = mono); Txt("src", color = theme.ansi[4], size = 10.sp, font = mono) }
            Row { Txt("-rw-r--r-- ", color = theme.ansi[8], size = 10.sp, font = mono); Txt(".env", color = theme.ansi[3], size = 10.sp, font = mono) }
        }
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(theme.displayName, color = D.text, size = 11.5.sp, weight = FontWeight.Medium)
            if (active) Badge("ACTIVE", bg = D.cyan14, fg = D.cyanBright, radius = 3, size = 9.sp)
        }
    }
}

// Terminal.

@Composable
private fun TerminalSection(state: DesktopDesignState) {
    SectionTitle(stringResource(Res.string.settings_terminal_title), stringResource(Res.string.settings_terminal_subtitle))
    // Буфер прокрутки: глубина scrollback новой сессии (селект пресетов справа от подписи).
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.settings_terminal_scrollback), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.settings_terminal_scrollback_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(160.dp)) { ScrollbackPicker(state.terminalScrollback, onPick = state::chooseTerminalScrollback) }
    }
    HLine()
    // Стиль курсора: форма × мигание по умолчанию для новой сессии.
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt(stringResource(Res.string.settings_terminal_cursor_style), color = D.text, size = 13.sp, weight = FontWeight.Medium) }
        Box(Modifier.width(200.dp)) { CursorStylePicker(state.terminalCursorStyle, onPick = state::chooseTerminalCursorStyle) }
    }
    HLine()
    // Живой OSC-заголовок терминала на вкладках: включает ветку effectiveTabTitle в Session.tabTitle.
    SettingToggleRow(
        stringResource(Res.string.settings_terminal_show_title),
        stringResource(Res.string.settings_terminal_show_title_desc),
        on = state.showTerminalTitleOnTabs,
        onToggle = state::toggleShowTerminalTitleOnTabs,
    )
}

/** Локализованная подпись стиля курсора (форма + мигание) для дропдауна и триггера. */
@Composable
private fun TerminalCursorStyle.label(): String = stringResource(
    when (this) {
        TerminalCursorStyle.BlockBlink -> Res.string.settings_terminal_cursor_block_blink
        TerminalCursorStyle.BlockSteady -> Res.string.settings_terminal_cursor_block_steady
        TerminalCursorStyle.UnderlineBlink -> Res.string.settings_terminal_cursor_underline_blink
        TerminalCursorStyle.UnderlineSteady -> Res.string.settings_terminal_cursor_underline_steady
        TerminalCursorStyle.BarBlink -> Res.string.settings_terminal_cursor_bar_blink
        TerminalCursorStyle.BarSteady -> Res.string.settings_terminal_cursor_bar_steady
    },
)

/** Выпадающий список глубины scrollback ([TERMINAL_SCROLLBACK_OPTIONS], строк; формат «10 000»). */
@Composable
private fun ScrollbackPicker(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { SelectTrigger(formatScrollback(current), onClick = { open = !open }) },
        menu = { width ->
            DropdownMenuColumn(width) {
                TERMINAL_SCROLLBACK_OPTIONS.forEach { lines ->
                    DropdownOption(formatScrollback(lines), selected = lines == current) { onPick(lines); open = false }
                }
            }
        },
    )
}

/** Выпадающий список стиля курсора ([TerminalCursorStyle.entries]). */
@Composable
private fun CursorStylePicker(current: TerminalCursorStyle, onPick: (TerminalCursorStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { SelectTrigger(current.label(), onClick = { open = !open }) },
        menu = { width ->
            DropdownMenuColumn(width) {
                TerminalCursorStyle.entries.forEach { style ->
                    DropdownOption(style.label(), selected = style == current) { onPick(style); open = false }
                }
            }
        },
    )
}

/** «10000» → «10 000» (неразрывный пробел между тысячами) для читаемости счётчика строк. */
private fun formatScrollback(lines: Int): String =
    lines.toString().reversed().chunked(3).joinToString(" ").reversed()

// Account.

@Composable
private fun AccountSection(state: DesktopDesignState) {
    SectionTitle(stringResource(Res.string.settings_account_title), stringResource(Res.string.settings_account_subtitle))
    // Реальная модель — self-hosted zero-knowledge sync (без биллинга/PRO): карточка отражает живое
    // состояние из координатора. Превью/офскрин (нет бэкенда) — локальный vault с «Set up sync».
    val sync = LocalSync.current
    if (sync == null) {
        AccountCard(accountCardModelLocalized(null), sync = null, state = state)
    } else {
        LiveAccountSection(sync, state)
    }
}

/** Живая карточка аккаунта: безусловный collectAsState внутри своего composable. */
@Composable
private fun LiveAccountSection(sync: app.skerry.ui.sync.SyncCoordinator, state: DesktopDesignState) {
    val status = sync.status.collectAsState().value
    val model = accountCardModelLocalized(status, sync.savedConfig?.serverUrl)
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
            model.connected && sync != null -> GhostButton(stringResource(Res.string.settings_disconnect), onClick = { sync.disconnect() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
            model.linked -> PrimaryButton(stringResource(Res.string.settings_reconnect), onClick = state::openSyncSetup, icon = "cloud_sync")
            else -> PrimaryButton(stringResource(Res.string.settings_set_up_sync), onClick = state::openSyncSetup, icon = "cloud_sync")
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

    Txt(stringResource(Res.string.settings_linked_devices), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
    when {
        loading -> Txt(stringResource(Res.string.settings_loading_devices), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        // На активной сессии сервер всегда возвращает хотя бы текущее устройство; пустой список =
        // listDevices проглотил ошибку (нет связи/протух токен) — честно говорим, а не «только вы».
        devices.isEmpty() -> Txt(stringResource(Res.string.settings_devices_load_failed), color = D.amber, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.size == 1 && devices.first().current -> Txt(stringResource(Res.string.settings_only_this_device), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        else -> devices.forEach { d ->
            DeviceRow(
                icon = "devices",
                name = d.name,
                sub = if (d.current) stringResource(Res.string.settings_device_sub_current) else stringResource(Res.string.settings_device_sub_other),
                thisDevice = d.current,
                onRevoke = if (d.current || d.revoked) null else {
                    { scope.launch { if (sync.revokeDevice(d.id)) reload++ } }
                },
            )
        }
    }
    // Быстрый паринг: показать новому устройству QR/код, чтобы привязать его без мастер-пароля аккаунта.
    GhostButton(stringResource(Res.string.settings_link_device), onClick = onLink, icon = "qr_code", modifier = Modifier.padding(top = 12.dp))
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
                if (thisDevice) Txt(stringResource(Res.string.settings_this_device), color = D.moss, size = 10.sp)
            }
            Txt(sub, color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Txt(trailing, color = D.faint, size = 11.sp)
        if (onRevoke != null) {
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RevokeChip(stringResource(Res.string.settings_confirm), D.sunset) { confirming = false; onRevoke() }
                    RevokeChip(stringResource(Res.string.settings_cancel), D.dim) { confirming = false }
                }
            } else {
                RevokeChip(stringResource(Res.string.settings_revoke), D.dim) { confirming = true }
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
    SectionTitle(stringResource(Res.string.settings_sync_title), stringResource(Res.string.settings_sync_subtitle))
    // Мок-путь и живой путь — разные composable (а не условный remember/collectAsState в одном теле):
    // rememberCoroutineScope/collectAsState должны вызываться безусловно в своём composable (правило
    // слотовой таблицы Compose). LocalSync.current стабилен (staticCompositionLocalOf), но строгий
    // паттерн — ветвление на отдельные функции, каждая со своими remember-вызовами.
    val sync = LocalSync.current
    if (sync == null) {
        // Мок-путь/превью без бэкенда: статичная карточка макета (подключённое состояние).
        SyncStatusCard("cloud_done", D.moss, stringResource(Res.string.settings_sync_synced_ago), stringResource(Res.string.settings_sync_summary_mock)) {
            GhostButton(stringResource(Res.string.settings_sync_now), onClick = {})
        }
    } else {
        LiveSyncStatus(sync, state)
    }
    Txt(stringResource(Res.string.settings_what_syncs), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    if (sync == null) {
        // Превью без бэкенда: статичные тумблеры (как в макете).
        SettingToggleRow(stringResource(Res.string.settings_hosts_groups), "", on = true, onToggle = {})
        SettingToggleRow(stringResource(Res.string.settings_snippets), "", on = true, onToggle = {})
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
    SettingToggleRow(stringResource(Res.string.settings_hosts_groups), "", on = settings.syncHosts, onToggle = {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncHosts = !current.syncHosts))
    })
    SettingToggleRow(stringResource(Res.string.settings_snippets), "", on = settings.syncSnippets, onToggle = {
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
            stringResource(Res.string.settings_sync_connected, status.accountId),
            stringResource(Res.string.settings_sync_pushed_pulled, status.lastPushed, status.lastPulled),
        ) {
            GhostButton(stringResource(Res.string.settings_sync_now), onClick = { sync.syncNow() })
        }
        SyncStatus.Busy -> SyncStatusCard("sync", D.cyanBright, stringResource(Res.string.settings_sync_syncing), stringResource(Res.string.settings_sync_syncing_desc)) {}
        is SyncStatus.Configured -> SyncStatusCard("cloud_off", D.amber, stringResource(Res.string.settings_sync_linked, status.accountId), stringResource(Res.string.settings_sync_linked_desc)) {
            GhostButton(stringResource(Res.string.settings_open_account), onClick = toAccount)
        }
        is SyncStatus.Failed -> SyncStatusCard("cloud_off", D.sunset, stringResource(Res.string.settings_sync_error), status.message) {
            GhostButton(stringResource(Res.string.settings_open_account), onClick = toAccount)
        }
        SyncStatus.Disabled -> SyncStatusCard("cloud_off", D.faint, stringResource(Res.string.settings_sync_not_connected), stringResource(Res.string.settings_sync_not_connected_desc)) {
            GhostButton(stringResource(Res.string.settings_open_account), onClick = toAccount)
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
    SectionTitle(stringResource(Res.string.settings_security_title), stringResource(Res.string.settings_security_subtitle))
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.settings_security_master_password), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.settings_security_master_password_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        GhostButton(stringResource(Res.string.settings_change), onClick = {})
    }
    HLine()
    SettingToggleRow(stringResource(Res.string.settings_security_touch_id), stringResource(Res.string.settings_security_touch_id_desc), on = true, onToggle = {})
    HLine()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.settings_security_auto_lock), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.settings_security_auto_lock_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(170.dp)) { SettingsSelect(stringResource(Res.string.settings_security_after_5_min)) }
    }
    HLine()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(stringResource(Res.string.settings_security_2fa), color = D.text, size = 13.sp, weight = FontWeight.Medium)
                Txt(stringResource(Res.string.settings_enabled), color = D.moss, size = 10.sp)
            }
            Txt(stringResource(Res.string.settings_security_2fa_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        GhostButton(stringResource(Res.string.settings_manage), onClick = {})
    }
    Txt(stringResource(Res.string.settings_recent_security_events), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    listOf(
        stringResource(Res.string.settings_security_event_1),
        stringResource(Res.string.settings_security_event_2),
        stringResource(Res.string.settings_security_event_3),
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
    SectionTitle(stringResource(Res.string.settings_keyboard_title), stringResource(Res.string.settings_keyboard_subtitle))
    // Подпись под платформу: ⌘/⌥ на macOS, Ctrl+Shift/Alt на Linux/Windows — ровно то, что распознаёт
    // matchDesktopShortcut. На Ctrl-пути требуется Shift, поэтому чистый Ctrl+буква (Ctrl+L очистка,
    // Ctrl+D EOF, Ctrl+C сигнал) остаётся терминалу.
    val mac = isApplePlatform()
    val mod: (String) -> String = { k -> if (mac) "⌘$k" else "Ctrl+Shift+$k" }
    // Терминальные аккорды — литеральные Ctrl/Shift/Tab (не app-модификатор): на macOS показываем
    // символами ⌃/⇧, иначе словами.
    val ctrl: (String) -> String = { k -> if (mac) "⌃$k" else "Ctrl+$k" }
    val ctrlShift: (String) -> String = { k -> if (mac) "⌃⇧$k" else "Ctrl+Shift+$k" }
    val shift: (String) -> String = { k -> if (mac) "⇧$k" else "Shift+$k" }

    val global = listOf(
        KeyboardBinding(stringResource(Res.string.settings_kb_new_connection), mod("N"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_command_palette), mod("K"), live = false),
        KeyboardBinding(stringResource(Res.string.settings_kb_split_terminal), mod("D"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_next_prev_tab), "${ctrl("Tab")} / ${ctrlShift("Tab")}", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_select_tab_number), if (mac) "⌥1–9" else "Alt+1–9", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_focus_ai), mod("/"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_open_sftp), mod("F"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_lock), mod("L"), live = true),
    )
    // Хоткеи внутри терминала (обрабатывает TerminalScreen): автодополнение fish-стиля и reverse-search
    // истории (Ctrl-R) + копипаст. Работают, пока сфокусирован терминал сессии.
    val terminal = listOf(
        KeyboardBinding(stringResource(Res.string.settings_kb_accept_autocomplete), "Tab", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_cycle_suggestions), shift("Tab"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_search_history), ctrl("R"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_copy_selection), ctrlShift("C"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_paste), ctrlShift("V"), live = true),
    )

    val mono = LocalFonts.current.mono
    KeyboardGroupLabel(stringResource(Res.string.settings_kb_global), top = 4.dp)
    global.forEach { KeyboardRow(it, mono) }
    KeyboardGroupLabel(stringResource(Res.string.settings_kb_terminal_group), top = 18.dp)
    terminal.forEach { KeyboardRow(it, mono) }
}

@Composable
private fun KeyboardGroupLabel(text: String, top: Dp) {
    Txt(text, color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = top, bottom = 8.dp))
}

@Composable
private fun KeyboardRow(b: KeyboardBinding, mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(b.label, color = if (b.live) D.textBright else D.dim, size = 12.5.sp)
            // «Command palette» ещё нет как фичи — честно помечаем биндинг как будущий, а не молча
            // показываем нерабочий аккорд наравне с живыми.
            if (!b.live) Badge(stringResource(Res.string.settings_badge_soon), bg = Color(0x1AF2A65A), fg = D.amber, radius = 3, size = 9.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x0AFFFFFF)).border(1.dp, D.cyan14, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Txt(b.binding, color = D.dim, size = 11.sp, font = mono)
        }
    }
    HLine()
}

/** Строка страницы Keyboard: подпись, аккорд и признак «уже работает» (иначе — метка SOON). */
private data class KeyboardBinding(val label: String, val binding: String, val live: Boolean)

// About.

@Composable
private fun AboutSection() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 20.dp).size(72.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A141B)), contentAlignment = Alignment.Center) {
            BrandMark(size = 72.dp)
        }
        Txt("Skerry", color = D.text, size = 20.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Txt("Version 2.4.0 · build 2026.06.21", color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Txt(stringResource(Res.string.settings_about_tagline), color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp))
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(stringResource(Res.string.settings_about_whats_new), onClick = {})
            GhostButton(stringResource(Res.string.settings_about_documentation), onClick = {})
            GhostButton(stringResource(Res.string.settings_about_licenses), onClick = {})
        }
        Txt(stringResource(Res.string.settings_about_footer), color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 20.dp))
    }
}

// Хелперы.

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
