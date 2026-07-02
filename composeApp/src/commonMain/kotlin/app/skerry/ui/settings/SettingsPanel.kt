package app.skerry.ui.settings

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
import androidx.compose.runtime.produceState
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
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.SecurityEvent
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.securityMoment
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import app.skerry.ui.vault.VaultGateController
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_default_value
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_recent_count
import app.skerry.ui.generated.resources.appearance_recent_show
import app.skerry.ui.generated.resources.appearance_recent_show_desc
import app.skerry.ui.generated.resources.appearance_section_interface
import app.skerry.ui.generated.resources.appearance_section_terminal
import app.skerry.ui.generated.resources.appearance_letter_spacing
import app.skerry.ui.generated.resources.appearance_line_height
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
import app.skerry.ui.ai.isInsecureAiEndpoint
import app.skerry.ui.generated.resources.settings_ai_field_endpoint
import app.skerry.ui.generated.resources.sync_insecure_url_warning
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
import app.skerry.ui.generated.resources.settings_security_auto_lock
import app.skerry.ui.generated.resources.settings_security_auto_lock_desc
import app.skerry.ui.generated.resources.settings_autolock_1m
import app.skerry.ui.generated.resources.settings_autolock_5m
import app.skerry.ui.generated.resources.settings_autolock_15m
import app.skerry.ui.generated.resources.settings_autolock_30m
import app.skerry.ui.generated.resources.settings_autolock_never
import app.skerry.ui.generated.resources.settings_security_no_events
import app.skerry.ui.generated.resources.settings_security_pw_changed_today
import app.skerry.ui.generated.resources.settings_security_pw_changed_yesterday
import app.skerry.ui.generated.resources.settings_security_pw_changed_days
import app.skerry.ui.generated.resources.settings_event_vault_created
import app.skerry.ui.generated.resources.settings_event_password_changed
import app.skerry.ui.generated.resources.settings_event_biometric_enabled
import app.skerry.ui.generated.resources.settings_event_biometric_disabled
import app.skerry.ui.generated.resources.settings_event_unlocked_biometric
import app.skerry.ui.generated.resources.settings_event_device_paired
import app.skerry.ui.generated.resources.settings_time_today
import app.skerry.ui.generated.resources.settings_time_yesterday
import app.skerry.ui.generated.resources.settings_time_days_ago
import app.skerry.ui.generated.resources.settings_change_pw_title
import app.skerry.ui.generated.resources.settings_change_pw_current
import app.skerry.ui.generated.resources.settings_change_pw_new
import app.skerry.ui.generated.resources.settings_change_pw_confirm
import app.skerry.ui.generated.resources.settings_change_pw_submit
import app.skerry.ui.generated.resources.settings_change_pw_err_wrong
import app.skerry.ui.generated.resources.vtail_error_password_mismatch
import app.skerry.ui.generated.resources.vtail_error_password_too_short
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
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.Badge
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.D
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.sync.FieldLabel
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.design.Sym
import app.skerry.ui.sync.SyncField
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine

/** Панель настроек (модалка 760×560): nav 200dp + контент с 8 секциями (AI/Appearance/…/About). */
@Composable
fun SettingsPanel(state: DesktopDesignState) {
    val noop = remember { MutableInteractionSource() }
    // Живой контроллер безопасности (поверх общих vault/биометрии/журнала): смена пароля, тумблер
    // биометрии, чтение событий. null — мок/превью без vault. reload перечитывает журнал/метку после
    // действий. changePwOpen поднят на уровень оверлея: диалог рисуется поверх всей карточки настроек.
    val securityController = rememberSecurityController()
    var securityReload by remember { mutableStateOf(0) }
    var changePwOpen by remember { mutableStateOf(false) }
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
                    SettingsTab.Security -> SecuritySection(
                        state = state,
                        controller = securityController,
                        reload = securityReload,
                        onChangeMasterPassword = { changePwOpen = true },
                        onBiometricToggled = { securityReload++ },
                    )
                    SettingsTab.Keyboard -> KeyboardSection()
                    SettingsTab.About -> AboutSection()
                }
            }
        }
        // Диалог смены мастер-пароля — оверлей поверх всей карточки настроек (не внутри скролла).
        if (changePwOpen && securityController != null) {
            ChangeMasterPasswordDialog(
                controller = securityController,
                onClose = { changePwOpen = false },
                onChanged = { securityReload++ },
            )
        }
    }
}

/**
 * Построить живой [VaultGateController] поверх общих vault/биометрии/журнала (из CompositionLocal) —
 * для раздела Безопасность (смена пароля, тумблер биометрии, чтение журнала). Отдельный инстанс от
 * гейта: события пишутся в ОБЩИЙ [SecurityLog] (консистентны на уровне файла), навигация гейта здесь
 * не нужна. `null` — мок/превью без vault: секция рисует нейтральный вид.
 */
@Composable
private fun rememberSecurityController(): VaultGateController? {
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val log = LocalSecurityLog.current
    return remember(vault, biometrics, log) {
        vault?.let { VaultGateController(it, biometrics, securityLog = log) }
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
    // http:// шлёт API-ключ и промпт (при Permissive — с секретами) открытым текстом — как в sync-паринге,
    // предупреждаем (кроме localhost, где cleartext осознан для локального прокси).
    if (isInsecureAiEndpoint(baseUrl)) {
        Txt(stringResource(Res.string.sync_insecure_url_warning), color = D.sunset, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }

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
    // Раскладка по секциям, по одной настройке в полноширинной строке: слева подпись + подсказка дефолта
    // (с быстрым сбросом), справа у края — контрол. Кегль/высота/интервал — точный числовой ввод (степпер).
    SectionLabel(stringResource(Res.string.appearance_section_terminal))
    SettingRow(label = stringResource(Res.string.appearance_font)) {
        Box(Modifier.width(180.dp)) { FontPicker(state.terminalFont, onPick = state::chooseTerminalFont) }
    }
    SettingRow(
        label = stringResource(Res.string.appearance_font_size),
        hasHint = true,
        isDefault = state.terminalFontSize == DEFAULT_TERMINAL_FONT_SIZE,
        defaultText = "$DEFAULT_TERMINAL_FONT_SIZE px",
        onReset = { state.chooseTerminalFontSize(DEFAULT_TERMINAL_FONT_SIZE) },
    ) {
        NumberStepper(
            value = state.terminalFontSize.toFloat(),
            onValueChange = { state.chooseTerminalFontSize(it.roundToInt().coerceIn(TERMINAL_FONT_SIZE_MIN, TERMINAL_FONT_SIZE_MAX)) },
            step = 1f,
            format = { it.roundToInt().toString() },
            parse = { it.trim().toIntOrNull()?.toFloat() },
            suffix = "px",
        )
    }
    SettingRow(
        label = stringResource(Res.string.appearance_line_height),
        hasHint = true,
        isDefault = formatDecimal(state.terminalLineHeight, 2) == formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
        defaultText = formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
        onReset = { state.chooseTerminalLineHeight(DEFAULT_TERMINAL_LINE_HEIGHT) },
    ) {
        NumberStepper(
            value = state.terminalLineHeight,
            onValueChange = state::chooseTerminalLineHeight,
            step = 0.05f,
            format = { formatDecimal(it, 2) },
            parse = { it.trim().replace(',', '.').toFloatOrNull() },
            fieldWidth = 52.dp,
        )
    }
    SettingRow(
        label = stringResource(Res.string.appearance_letter_spacing),
        hasHint = true,
        isDefault = formatDecimal(state.terminalLetterSpacing, 1) == formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1),
        defaultText = "${formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1)} px",
        onReset = { state.chooseTerminalLetterSpacing(DEFAULT_TERMINAL_LETTER_SPACING) },
    ) {
        NumberStepper(
            value = state.terminalLetterSpacing,
            onValueChange = state::chooseTerminalLetterSpacing,
            step = 0.1f,
            format = { formatDecimal(it, 1) },
            parse = { it.trim().replace(',', '.').toFloatOrNull() },
            suffix = "px",
            fieldWidth = 52.dp,
        )
    }
    SectionLabel(stringResource(Res.string.appearance_section_interface))
    SettingRow(label = stringResource(Res.string.appearance_language)) {
        Box(Modifier.width(180.dp)) { LanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage) }
    }
    // Секция RECENT в сайдбаре: показывать ли её и сколько хостов (степпер виден только когда включено).
    SettingToggleRow(
        stringResource(Res.string.appearance_recent_show),
        stringResource(Res.string.appearance_recent_show_desc),
        state.showRecent,
        { state.setRecentVisible(!state.showRecent) },
    )
    if (state.showRecent) {
        SettingRow(
            label = stringResource(Res.string.appearance_recent_count),
            hasHint = true,
            isDefault = state.recentLimit == DesktopDesignState.MAX_RECENT_HOSTS,
            defaultText = DesktopDesignState.MAX_RECENT_HOSTS.toString(),
            onReset = { state.chooseRecentLimit(DesktopDesignState.MAX_RECENT_HOSTS) },
        ) {
            NumberStepper(
                value = state.recentLimit.toFloat(),
                onValueChange = { state.chooseRecentLimit(it.roundToInt()) },
                step = 1f,
                format = { it.roundToInt().toString() },
                parse = { it.trim().toIntOrNull()?.toFloat() },
                fieldWidth = 52.dp,
            )
        }
    }
}

/**
 * Полноширинная строка настройки: слева подпись, под ней (при [hasHint]) подсказка дефолта с быстрым
 * сбросом; справа на той же линии — контрол ([NumberStepper]/дропдаун).
 */
@Composable
private fun SettingRow(
    label: String,
    hasHint: Boolean = false,
    isDefault: Boolean = true,
    defaultText: String = "",
    onReset: () -> Unit = {},
    control: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Txt(label, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            if (hasHint) DefaultValueHint(isDefault, defaultText, onReset)
        }
        control()
    }
}

/** Заголовок группы настроек: мелкие капсы в приглушённом цвете, с отступом сверху для отделения секций. */
@Composable
private fun SectionLabel(text: String) {
    Txt(
        text,
        color = D.faint,
        size = 11.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

/**
 * Подсказка значения по умолчанию: серый статичный текст, когда значение уже дефолтное; cyan-кликабельная
 * строка со значком сброса, когда изменено (клик возвращает к [defaultText]-значению через [onReset]).
 */
@Composable
private fun DefaultValueHint(isDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    val text = stringResource(Res.string.appearance_default_value, defaultText)
    if (isDefault) {
        Txt(text, color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
    } else {
        Row(
            Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Sym("restart_alt", size = 13.sp, color = D.cyan)
            Txt(text, color = D.cyan, size = 11.sp)
        }
    }
}

/**
 * Формат дробного значения с фиксированным числом знаков после точки (KMP-common без String.format).
 * Корректно показывает знак для отрицательных дробей с нулевой целой частью (−0.5).
 */
internal fun formatDecimal(value: Float, decimals: Int): String {
    val factor = if (decimals <= 1) 10 else 100
    val scaled = (value * factor).roundToInt()
    val whole = scaled / factor
    val frac = abs(scaled % factor).toString().padStart(decimals, '0')
    val sign = if (value < 0 && whole == 0) "-" else ""
    return "$sign$whole.$frac"
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

/** Триггер селекта макета: значение слева, шеврон справа (кликабельный: открывает выпадающий список). */
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

/** Промпт включения биометрии из раздела Безопасность (язык интерфейса — англ., как в остальном UI). */
private val SECURITY_ENABLE_BIOMETRIC_PROMPT = BiometricPrompt(
    title = "Enable biometric unlock",
    cancelLabel = "Cancel",
    subtitle = "Confirm your biometrics to unlock Skerry without typing the master password.",
)

/**
 * Живой раздел «Безопасность». Смена мастер-пароля ([VaultGateController.changePassword] через диалог),
 * реальный тумблер разблокировки биометрией (скрыт, когда железа/фактора нет), выбор порога
 * автоблокировки (проводится в idle-таймер гейта), журнал событий и подпись «последняя смена пароля»
 * из реального [SecurityLog]. Двухфакторная аутентификация помечена SOON (ещё не реализована).
 * [controller] == null — мок/превью без vault: показываем нейтральный вид без живых действий.
 */
@Composable
private fun SecuritySection(
    state: DesktopDesignState,
    controller: VaultGateController?,
    reload: Int,
    onChangeMasterPassword: () -> Unit,
    onBiometricToggled: () -> Unit,
) {
    SectionTitle(stringResource(Res.string.settings_security_title), stringResource(Res.string.settings_security_subtitle))

    // Мастер-пароль: подпись = реальная «последняя смена» из журнала (или нейтральный текст). Чтение
    // журнала — файловый I/O + JSON-разбор, поэтому уводим его с потока композиции на фоновый диспетчер.
    val lastChange by produceState<String?>(null, controller, reload) {
        value = withContext(Dispatchers.Default) { controller?.lastPasswordChangeAt() }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.settings_security_master_password), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(masterPasswordSubtitle(lastChange), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        // Смена доступна только за живым vault; в мок/превью кнопка неактивна (нечего менять).
        if (controller != null) GhostButton(stringResource(Res.string.settings_change), onClick = onChangeMasterPassword)
        else GhostButton(stringResource(Res.string.settings_change), onClick = {}, fg = D.faint, border = D.line)
    }
    HLine()

    // Разблокировка биометрией: строка только когда на устройстве есть доступная биометрия (desktop
    // Linux/офскрин — нет, строка скрыта: настраивать нечего).
    val scope = rememberCoroutineScope()
    if (controller != null && controller.canEnableBiometric()) {
        SettingToggleRow(
            stringResource(Res.string.settings_security_touch_id),
            stringResource(Res.string.settings_security_touch_id_desc),
            on = controller.biometricEnabled,
            onToggle = {
                if (controller.biometricInFlight) return@SettingToggleRow
                scope.launch {
                    if (controller.biometricEnabled) controller.disableBiometric()
                    else controller.enableBiometric(SECURITY_ENABLE_BIOMETRIC_PROMPT)
                    onBiometricToggled()
                }
            },
        )
        HLine()
    }

    // Автоблокировка: реальный порог простоя, проводится в idle-таймер VaultGate через state.autoLock.
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.settings_security_auto_lock), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.settings_security_auto_lock_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(170.dp)) { AutoLockPicker(state.autoLock, onPick = state::chooseAutoLock) }
    }
    HLine()

    // Двухфакторная аутентификация — ещё не реализована: честная метка SOON вместо фейкового «включена».
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(stringResource(Res.string.settings_security_2fa), color = D.dim, size = 13.sp, weight = FontWeight.Medium)
                Badge(stringResource(Res.string.settings_badge_soon), bg = Color(0x1AF2A65A), fg = D.amber, radius = 3, size = 9.sp)
            }
            Txt(stringResource(Res.string.settings_security_2fa_desc), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        // Неактивная кнопка (приглушённая) — фича ещё не готова.
        GhostButton(stringResource(Res.string.settings_manage), onClick = {}, fg = D.faint, border = D.line)
    }

    // Недавние события безопасности — из реального журнала (или «пока событий нет»).
    Txt(stringResource(Res.string.settings_recent_security_events), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    val events by produceState(emptyList<SecurityEvent>(), controller, reload) {
        value = withContext(Dispatchers.Default) { controller?.recentSecurityEvents(8) ?: emptyList() }
    }
    if (events.isEmpty()) {
        Txt(stringResource(Res.string.settings_security_no_events), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
    } else {
        events.forEach { event ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("●", color = D.moss, size = 9.sp)
                Txt(securityEventLine(event), color = D.dim, size = 12.sp)
            }
        }
    }
}

/** Подпись под мастер-паролем: реальная «последняя смена» из журнала или нейтральный текст. */
@Composable
internal fun masterPasswordSubtitle(lastChangeAt: String?): String {
    val moment = lastChangeAt?.let { securityMoment(it) }
        ?: return stringResource(Res.string.settings_security_master_password_desc)
    return when (moment.daysAgo) {
        0 -> stringResource(Res.string.settings_security_pw_changed_today)
        1 -> stringResource(Res.string.settings_security_pw_changed_yesterday)
        else -> stringResource(Res.string.settings_security_pw_changed_days, moment.daysAgo)
    }
}

/** Строка события журнала: «Метка[: деталь] · относительное время». */
@Composable
internal fun securityEventLine(event: SecurityEvent): String {
    val label = event.type.eventLabel()
    val head = event.detail?.let { "$label: $it" } ?: label
    return "$head · ${securityEventTime(event.at)}"
}

/** Локализованная подпись типа события. */
@Composable
private fun SecurityEventType.eventLabel(): String = stringResource(
    when (this) {
        SecurityEventType.VaultCreated -> Res.string.settings_event_vault_created
        SecurityEventType.MasterPasswordChanged -> Res.string.settings_event_password_changed
        SecurityEventType.BiometricEnabled -> Res.string.settings_event_biometric_enabled
        SecurityEventType.BiometricDisabled -> Res.string.settings_event_biometric_disabled
        SecurityEventType.UnlockedBiometric -> Res.string.settings_event_unlocked_biometric
        SecurityEventType.DevicePaired -> Res.string.settings_event_device_paired
    },
)

/** Относительное время события («сегодня 09:02» / «вчера …» / «N дн. назад»); при непарсе — сырой штамп. */
@Composable
private fun securityEventTime(at: String): String {
    val moment = securityMoment(at) ?: return at
    return when (moment.daysAgo) {
        0 -> stringResource(Res.string.settings_time_today, moment.timeOfDay)
        1 -> stringResource(Res.string.settings_time_yesterday, moment.timeOfDay)
        else -> stringResource(Res.string.settings_time_days_ago, moment.daysAgo)
    }
}

/** Выпадающий список порога автоблокировки ([AutoLockDuration.entries]). */
@Composable
private fun AutoLockPicker(current: AutoLockDuration, onPick: (AutoLockDuration) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { SelectTrigger(current.autoLockLabel(), onClick = { open = !open }) },
        menu = { width ->
            DropdownMenuColumn(width) {
                AutoLockDuration.entries.forEach { option ->
                    DropdownOption(option.autoLockLabel(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Локализованная подпись порога автоблокировки. */
@Composable
internal fun AutoLockDuration.autoLockLabel(): String = stringResource(
    when (this) {
        AutoLockDuration.OneMinute -> Res.string.settings_autolock_1m
        AutoLockDuration.FiveMinutes -> Res.string.settings_autolock_5m
        AutoLockDuration.FifteenMinutes -> Res.string.settings_autolock_15m
        AutoLockDuration.ThirtyMinutes -> Res.string.settings_autolock_30m
        AutoLockDuration.Never -> Res.string.settings_autolock_never
    },
)

/**
 * Диалог смены мастер-пароля: текущий + новый + подтверждение. Кнопка активна лишь при валидном вводе
 * (новый ≥ [MIN_MASTER_PASSWORD_LENGTH] и совпадает с подтверждением). Единственный отказ на сабмите —
 * неверный текущий пароль. Успех закрывает диалог и обновляет журнал ([onChanged]). Известное
 * ограничение: строковые буферы полей живут в slot-table до рекомпозиции (как в форме входа); в vault
 * пароль уходит как [CharArray] и затирается контроллером.
 */
@Composable
internal fun ChangeMasterPasswordDialog(
    controller: VaultGateController,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var wrongCurrent by remember { mutableStateOf(false) }
    // Argon2id-деривация ключа (дважды) + перезапись vault — намеренно дорогие; гоняем их на фоновом
    // диспетчере, а не на потоке композиции, иначе UI замирает (на Android — вплоть до ANR). [busy]
    // держит кнопку неактивной, пока смена идёт, чтобы не запустить её повторно.
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tooShort = fresh.isNotEmpty() && fresh.length < MIN_MASTER_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && fresh != confirm
    val canSubmit = current.isNotEmpty() && fresh.length >= MIN_MASTER_PASSWORD_LENGTH && fresh == confirm && !busy

    val submit = {
        if (canSubmit) {
            wrongCurrent = false
            busy = true
            // Снимаем буферы до запуска корутины (contro­ller их затирает); строки в slot-table живут
            // до рекомпозиции — их гигиена вне контракта смены, как и в форме входа.
            val old = current.toCharArray()
            val next = fresh.toCharArray()
            scope.launch {
                val ok = withContext(Dispatchers.Default) { controller.changePassword(old, next) }
                busy = false
                if (ok) { onChanged(); onClose() } else wrongCurrent = true
            }
            Unit
        }
    }

    val error: String? = when {
        wrongCurrent -> stringResource(Res.string.settings_change_pw_err_wrong)
        tooShort -> stringResource(Res.string.vtail_error_password_too_short, MIN_MASTER_PASSWORD_LENGTH)
        mismatch -> stringResource(Res.string.vtail_error_password_mismatch)
        else -> null
    }

    val noop = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3060E16)).clickable(interactionSource = noop, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.settings_change_pw_title), color = D.text, size = 16.sp, weight = FontWeight.SemiBold)
            Box(Modifier.height(16.dp))
            FieldLabel(stringResource(Res.string.settings_change_pw_current), top = 0.dp)
            SyncField(placeholder = "••••••••", value = current, icon = "lock", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { current = it; wrongCurrent = false }
            FieldLabel(stringResource(Res.string.settings_change_pw_new))
            SyncField(placeholder = "••••••••", value = fresh, icon = "lock_reset", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { fresh = it }
            FieldLabel(stringResource(Res.string.settings_change_pw_confirm))
            SyncField(placeholder = "••••••••", value = confirm, icon = "lock_reset", keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, secret = true, onSubmit = submit) { confirm = it }
            if (error != null) Txt(error, color = D.storm, size = 11.5.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.settings_cancel), color = D.dim, size = 12.5.sp)
                }
                PrimaryButton(
                    stringResource(Res.string.settings_change_pw_submit),
                    onClick = submit,
                    enabled = canSubmit,
                    bg = if (canSubmit) D.cyan else D.cyan10,
                    fg = if (canSubmit) Color(0xFF0A1A26) else D.faint,
                )
            }
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

