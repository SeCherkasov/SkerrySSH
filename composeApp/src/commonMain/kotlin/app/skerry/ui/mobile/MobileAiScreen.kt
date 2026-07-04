package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.ui.ai.AiChatBubble
import app.skerry.ui.ai.AiQuickChatHeader
import app.skerry.ui.ai.isInsecureAiEndpoint
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.settings_ai_ask
import app.skerry.ui.generated.resources.settings_ai_field_api_key
import app.skerry.ui.generated.resources.settings_ai_field_endpoint
import app.skerry.ui.generated.resources.settings_ai_field_model
import app.skerry.ui.generated.resources.settings_ai_key_saved
import app.skerry.ui.generated.resources.settings_ai_live_subtitle
import app.skerry.ui.generated.resources.settings_ai_not_configured
import app.skerry.ui.generated.resources.settings_ai_off_note
import app.skerry.ui.generated.resources.settings_ai_placeholder_api_key
import app.skerry.ui.generated.resources.settings_ai_placeholder_endpoint
import app.skerry.ui.generated.resources.settings_ai_placeholder_model
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_needs_key
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_needs_model
import app.skerry.ui.generated.resources.settings_ai_prompt_placeholder_ready
import app.skerry.ui.generated.resources.settings_ai_quick_chat
import app.skerry.ui.generated.resources.settings_ai_quick_chat_desc
import app.skerry.ui.generated.resources.settings_ai_sending
import app.skerry.ui.generated.resources.settings_clear
import app.skerry.ui.generated.resources.settings_save
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.settings.AiProviderCards
import org.jetbrains.compose.resources.stringResource

/**
 * Мобильный экран настроек AI (More → «AI & privacy») — паритет с desktop `LiveAiSection`:
 * карточки провайдера (локальная модель / BYOK / выключен), BYOK-поля + Save в vault и quick chat.
 * Поля/подписи/кнопки — общие примитивы ([MobileFormField]/[MobileFormInput]/[ChipButton]),
 * чтобы экран не расходился по ширине/высоте с остальными мобильными формами. Без живого
 * контроллера ([LocalAi] == null) не открывается (строка More инертна), поэтому здесь `ai` не-null.
 */
@Composable
fun MobileAiScreen(state: MobileDesignState) {
    val ai = LocalAi.current
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobilePushHeader(stringResource(Res.string.more_ai_privacy), onBack = state::pop)
        if (ai == null) return@Column
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(
                stringResource(Res.string.settings_ai_live_subtitle),
                color = D.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp),
            )

            // Выбор провайдера — общий блок с desktop-настройками (AiProviderCards): состояние и
            // логика едины; BYOK-поля раскрываются внутри своей карточки (мобильный layout — ниже).
            AiProviderCards(ai, byokContent = { MobileByokFields(ai) })

            // AI выключен: quick-chat скрыт, конфиг сохранён и вернётся с провайдером.
            if (!ai.enabled) {
                Txt(
                    stringResource(Res.string.settings_ai_off_note),
                    color = D.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp),
                )
                Spacer(Modifier.height(96.dp))
                return@Column
            }

            MobileAiDivider()
            var chatOpen by remember { mutableStateOf(false) }
            AiQuickChatHeader(
                stringResource(Res.string.settings_ai_quick_chat),
                stringResource(Res.string.settings_ai_quick_chat_desc),
                open = chatOpen,
                onToggle = { chatOpen = !chatOpen },
            )
            if (chatOpen) {
                Spacer(Modifier.height(8.dp))
                ai.turns.forEach { turn -> AiChatBubble(turn.role, turn.text) }
                ai.streaming?.let { AiChatBubble(AiRole.ASSISTANT, if (it.isEmpty()) "…" else it) }
                // Ошибка запроса — error-токен (D.storm), как на desktop; D.sunset оставлен предупреждениям.
                ai.error?.let { Txt(it, color = D.storm, size = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }

                var prompt by remember { mutableStateOf("") }
                val send = { if (prompt.isNotBlank() && !ai.busy) { ai.ask(prompt); prompt = "" } }
                MobileFormInput(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = when {
                        ai.ready -> stringResource(Res.string.settings_ai_prompt_placeholder_ready)
                        ai.settings.provider == AiProviderKind.DEVICE -> stringResource(Res.string.settings_ai_prompt_placeholder_needs_model)
                        else -> stringResource(Res.string.settings_ai_prompt_placeholder_needs_key)
                    },
                    imeAction = ImeAction.Send,
                    onSubmit = send,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipButton(if (ai.busy) stringResource(Res.string.settings_ai_sending) else stringResource(Res.string.settings_ai_ask), color = if (ai.ready && !ai.busy) D.cyan else D.faint, onClick = { send() })
                    if (ai.turns.isNotEmpty()) ChipButton(stringResource(Res.string.settings_clear), color = D.dim, onClick = { ai.clearConversation() })
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

/**
 * BYOK-поля внутри карточки «Мой ключ API» (мобильный layout — [MobileFormField]/[MobileFormInput]);
 * раскрываются вместе с выбором карточки, состояние и Save — общие с desktop.
 */
@Composable
private fun MobileByokFields(ai: app.skerry.ui.ai.AiAssistantController) {
    var key by remember(ai.settings) { mutableStateOf(ai.settings.apiKey) }
    var model by remember(ai.settings) { mutableStateOf(ai.settings.model) }
    var baseUrl by remember(ai.settings) { mutableStateOf(ai.settings.baseUrl) }

    Column(Modifier.padding(top = 10.dp)) {
        MobileFormField(stringResource(Res.string.settings_ai_field_api_key)) {
            MobileFormInput(key, { key = it }, stringResource(Res.string.settings_ai_placeholder_api_key), masked = true, imeAction = ImeAction.Next)
        }
        Spacer(Modifier.height(12.dp))
        MobileFormField(stringResource(Res.string.settings_ai_field_model)) {
            MobileFormInput(model, { model = it }, stringResource(Res.string.settings_ai_placeholder_model), imeAction = ImeAction.Next)
        }
        Spacer(Modifier.height(12.dp))
        MobileFormField(stringResource(Res.string.settings_ai_field_endpoint)) {
            MobileFormInput(baseUrl, { baseUrl = it }, stringResource(Res.string.settings_ai_placeholder_endpoint), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
        }
        // http:// шлёт ключ/промпт открытым текстом (см. SettingsPanel) — предупреждаем, кроме localhost.
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
}

/** Разделитель блоков экрана: единый воздух сверху/снизу (18/14), как у desktop-секции. */
@Composable
private fun MobileAiDivider() {
    Spacer(Modifier.height(18.dp))
    HLine()
    Spacer(Modifier.height(14.dp))
}
