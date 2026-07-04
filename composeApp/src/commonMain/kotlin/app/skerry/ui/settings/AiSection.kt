package app.skerry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.ui.ai.AiChatBubble
import app.skerry.ui.ai.AiQuickChatHeader
import app.skerry.ui.ai.isInsecureAiEndpoint
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalAi
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
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
import app.skerry.ui.generated.resources.settings_ai_off_note
import app.skerry.ui.generated.resources.settings_ai_placeholder_api_key
import app.skerry.ui.generated.resources.settings_ai_placeholder_endpoint
import app.skerry.ui.generated.resources.settings_ai_placeholder_model
import app.skerry.ui.generated.resources.settings_ai_preview
import app.skerry.ui.generated.resources.settings_ai_preview_desc
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_needs_key
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_needs_model
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_ready
import app.skerry.ui.generated.resources.settings_ai_provider_byok
import app.skerry.ui.generated.resources.settings_ai_provider_byok_desc
import app.skerry.ui.generated.resources.settings_ai_provider_device
import app.skerry.ui.generated.resources.settings_ai_provider_device_desc
import app.skerry.ui.generated.resources.settings_ai_provider_off
import app.skerry.ui.generated.resources.settings_ai_provider_off_desc
import app.skerry.ui.generated.resources.settings_ai_quick_chat
import app.skerry.ui.generated.resources.settings_ai_quick_chat_desc
import app.skerry.ui.generated.resources.settings_ai_sanitize
import app.skerry.ui.generated.resources.settings_ai_sanitize_desc
import app.skerry.ui.generated.resources.settings_ai_sending
import app.skerry.ui.generated.resources.settings_ai_title
import app.skerry.ui.generated.resources.settings_clear
import app.skerry.ui.generated.resources.settings_save
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.sync.SyncField
import org.jetbrains.compose.resources.stringResource

// Секция AI настроек: живой BYOK-таб (LocalAi) или мок-превью.

@Composable
internal fun AiSection(state: DesktopDesignState) {
    val ai = LocalAi.current
    if (ai != null) LiveAiSection(ai) else AiSectionMock(state)
}

/** Горизонтальный разделитель секций формы: воздух сверху/снизу единый по всему табу. */
@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(18.dp))
    HLine()
    Spacer(Modifier.height(12.dp))
}

/**
 * Живой AI-таб: выбор провайдера по умолчанию (локальная модель / BYOK / выключен); начинка —
 * внутри выбранной карточки (каталог моделей / BYOK-поля, ключ шифруется в vault). Быстрый чат
 * для проверки соединения — сворачиваемый блок, по умолчанию скрыт. Полноценный ассистент в
 * терминале (per-host политики, подтверждение команд) — отдельный слайс; здесь вывод модели
 * только показывается, не исполняется.
 */
@Composable
private fun LiveAiSection(ai: app.skerry.ui.ai.AiAssistantController) {
    SectionTitle(stringResource(Res.string.settings_ai_title), stringResource(Res.string.settings_ai_live_subtitle))

    AiProviderCards(ai, byokContent = { DesktopByokFields(ai) })

    // AI выключен: quick-chat скрываем — конфиг сохранён и вернётся с провайдером.
    if (!ai.enabled) {
        Txt(
            stringResource(Res.string.settings_ai_off_note),
            color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp),
        )
        return
    }
    SectionDivider()

    var chatOpen by remember { mutableStateOf(false) }
    AiQuickChatHeader(
        stringResource(Res.string.settings_ai_quick_chat),
        stringResource(Res.string.settings_ai_quick_chat_desc),
        open = chatOpen,
        onToggle = { chatOpen = !chatOpen },
    )
    if (!chatOpen) return
    Spacer(Modifier.height(8.dp))

    ai.turns.forEach { turn -> AiChatBubble(turn.role, turn.text) }
    ai.streaming?.let { AiChatBubble(AiRole.ASSISTANT, if (it.isEmpty()) "…" else it) }
    ai.error?.let { Txt(it, color = D.storm, size = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }

    var prompt by remember { mutableStateOf("") }
    val send = { if (prompt.isNotBlank() && !ai.busy) { ai.ask(prompt); prompt = "" } }
    SyncField(
        placeholder = when {
            ai.ready -> stringResource(Res.string.settings_ai_prompt_placeholder_ready)
            ai.settings.provider == AiProviderKind.DEVICE -> stringResource(Res.string.settings_ai_prompt_placeholder_needs_model)
            else -> stringResource(Res.string.settings_ai_prompt_placeholder_needs_key)
        },
        value = prompt,
        icon = "chat",
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Send,
        onSubmit = send,
    ) { prompt = it }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipButton(if (ai.busy) stringResource(Res.string.settings_ai_sending) else stringResource(Res.string.settings_ai_ask), color = if (ai.ready && !ai.busy) D.cyan else D.faint, onClick = { send() })
        if (ai.turns.isNotEmpty()) ChipButton(stringResource(Res.string.settings_clear), color = D.dim, onClick = { ai.clearConversation() })
    }
}

/**
 * BYOK-поля внутри карточки «Мой ключ API»: ключ/модель/эндпоинт + Save (шифрование — на стороне
 * [app.skerry.ui.ai.AiAssistantController.save]). Раскрываются вместе с выбором карточки.
 */
@Composable
private fun DesktopByokFields(ai: app.skerry.ui.ai.AiAssistantController) {
    var key by remember(ai.settings) { mutableStateOf(ai.settings.apiKey) }
    var model by remember(ai.settings) { mutableStateOf(ai.settings.model) }
    var baseUrl by remember(ai.settings) { mutableStateOf(ai.settings.baseUrl) }

    FieldLabel(stringResource(Res.string.settings_ai_field_api_key), top = 10.dp)
    SyncField(placeholder = stringResource(Res.string.settings_ai_placeholder_api_key), value = key, icon = "key", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { key = it }
    FieldLabel(stringResource(Res.string.settings_ai_field_model))
    SyncField(placeholder = stringResource(Res.string.settings_ai_placeholder_model), value = model, icon = "auto_awesome", keyboardType = KeyboardType.Text, imeAction = ImeAction.Next) { model = it }
    FieldLabel(stringResource(Res.string.settings_ai_field_endpoint))
    SyncField(placeholder = stringResource(Res.string.settings_ai_placeholder_endpoint), value = baseUrl, icon = "cloud", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done) { baseUrl = it }
    // http:// шлёт API-ключ и промпт (при Permissive — с секретами) открытым текстом — как в sync-паринге,
    // предупреждаем (кроме localhost, где cleartext осознан для локального прокси).
    if (isInsecureAiEndpoint(baseUrl)) {
        Txt(stringResource(Res.string.sync_insecure_url_warning), color = D.sunset, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ChipButton(stringResource(Res.string.settings_save), color = D.cyan, onClick = { ai.save(key, model, baseUrl) })
        if (ai.isConfigured) Txt(stringResource(Res.string.settings_ai_key_saved), color = D.moss, size = 11.5.sp)
        else Txt(stringResource(Res.string.settings_ai_not_configured), color = D.faint, size = 11.5.sp)
    }
}

@Composable
private fun AiSectionMock(state: DesktopDesignState) {
    SectionTitle(stringResource(Res.string.settings_ai_title), stringResource(Res.string.settings_ai_mock_subtitle))
    Txt(stringResource(Res.string.settings_ai_default_provider), color = D.text, size = 13.sp, weight = FontWeight.Medium)
    Txt(stringResource(Res.string.settings_ai_default_provider_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
    ProviderCard("lock", stringResource(Res.string.settings_ai_provider_device), stringResource(Res.string.settings_ai_provider_device_desc), selected = true, badge = stringResource(Res.string.settings_ai_badge_private))
    Spacer(Modifier.height(8.dp))
    ProviderCard("key", stringResource(Res.string.settings_ai_provider_byok), stringResource(Res.string.settings_ai_provider_byok_desc), selected = false)
    Spacer(Modifier.height(8.dp))
    ProviderCard("block", stringResource(Res.string.settings_ai_provider_off), stringResource(Res.string.settings_ai_provider_off_desc), selected = false)
    Spacer(Modifier.height(18.dp))
    HLine()
    Spacer(Modifier.height(6.dp))
    SettingToggleRow(stringResource(Res.string.settings_ai_sanitize), stringResource(Res.string.settings_ai_sanitize_desc), state.sanitize, state::toggleSanitize)
    SettingToggleRow(stringResource(Res.string.settings_ai_preview), stringResource(Res.string.settings_ai_preview_desc), state.preview, state::togglePreview)
    SettingToggleRow(stringResource(Res.string.settings_ai_confirm), stringResource(Res.string.settings_ai_confirm_desc), state.confirm, state::toggleConfirm)
}
