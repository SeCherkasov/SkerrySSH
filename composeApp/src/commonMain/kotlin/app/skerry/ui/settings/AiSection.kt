package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.ui.ai.AiChatBubble
import app.skerry.ui.ai.AiQuickChatHeader
import app.skerry.ui.ai.ByokHints
import app.skerry.ui.ai.aiFailureMessage
import app.skerry.ui.ai.isInsecureAiEndpoint
import app.skerry.ui.ai.rememberByokModelState
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalAi
import app.skerry.ui.ai.ModelPickerMenu
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.HLine
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
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
import app.skerry.ui.generated.resources.settings_ai_hint_endpoint_changed
import app.skerry.ui.generated.resources.settings_ai_hint_key_changed
import app.skerry.ui.generated.resources.settings_ai_hint_model_selected
import app.skerry.ui.generated.resources.settings_ai_key_saved
import app.skerry.ui.generated.resources.settings_ai_models_empty
import app.skerry.ui.generated.resources.settings_ai_refresh_done
import app.skerry.ui.generated.resources.settings_ai_refreshing_models
import app.skerry.ui.generated.resources.settings_ai_saved
import app.skerry.ui.generated.resources.settings_ai_search_models
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
import app.skerry.ui.generated.resources.settings_ai_refresh_models
import app.skerry.ui.generated.resources.settings_ai_refreshing
import app.skerry.ui.generated.resources.settings_ai_sanitize
import app.skerry.ui.generated.resources.settings_ai_sanitize_desc
import app.skerry.ui.generated.resources.settings_ai_sending
import app.skerry.ui.generated.resources.settings_clear
import app.skerry.ui.generated.resources.settings_save
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.sync.SyncField
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// AI settings section: live BYOK tab (LocalAi) or mock preview.

@Composable
internal fun AiSection(state: DesktopDesignState) {
    val ai = LocalAi.current
    if (ai != null) LiveAiSection(ai) else AiSectionMock(state)
}

/** Horizontal divider between form sections; consistent spacing across the tab. */
@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(18.dp))
    HLine()
    Spacer(Modifier.height(12.dp))
}

/**
 * Live AI tab: default provider picker (device model / BYOK / off), each card expanding its own
 * content (model catalog / BYOK fields, key encrypted in the vault). Quick chat for testing the
 * connection is collapsible and hidden by default. The full terminal assistant (per-host policies,
 * command confirmation) is a separate slice; here model output is only displayed, not executed.
 */
@Composable
private fun LiveAiSection(ai: app.skerry.ui.ai.AiAssistantController) {
    AiProviderCards(ai, byokContent = { DesktopByokFields(ai) })

    // AI disabled: hide quick chat; config is preserved and returns with the provider.
    if (!ai.enabled) {
        Txt(
            stringResource(Res.string.settings_ai_off_note),
            color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp),
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
    ai.error?.let { Txt(aiFailureMessage(it), color = Skerry.colors.storm, size = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }

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
        onChange = { prompt = it },
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipButton(if (ai.busy) stringResource(Res.string.settings_ai_sending) else stringResource(Res.string.settings_ai_ask), color = if (ai.ready && !ai.busy) Skerry.colors.cyan else Skerry.colors.faint, onClick = { send() })
        if (ai.turns.isNotEmpty()) ChipButton(stringResource(Res.string.settings_clear), color = Skerry.colors.dim, onClick = { ai.clearConversation() })
    }
}

/**
 * BYOK fields inside the "My API key" card: endpoint / API key / model + Refresh models + Save
 * (encryption happens in [app.skerry.ui.ai.AiAssistantController.save]). The model field is an
 * editable combo: type any model id, or open the dropdown to pick one from the catalog fetched via
 * the refresh button. Expands when the card is selected.
 */
@Composable
private fun DesktopByokFields(ai: app.skerry.ui.ai.AiAssistantController) {
    // Hint texts must be resolved here (composable context) — stringResource is @Composable and
    // cannot be called from onChange/onClick lambdas.
    val hints = ByokHints(
        endpointChanged = stringResource(Res.string.settings_ai_hint_endpoint_changed),
        keyChanged = stringResource(Res.string.settings_ai_hint_key_changed),
        modelSelected = stringResource(Res.string.settings_ai_hint_model_selected),
        refreshing = stringResource(Res.string.settings_ai_refreshing_models),
        refreshed = stringResource(Res.string.settings_ai_refresh_done),
        saved = stringResource(Res.string.settings_ai_saved),
    )
    val byok = rememberByokModelState(ai, hints)

    // ① Server address (endpoint). First: it is what the other two fields talk to.
    FieldLabel(stringResource(Res.string.settings_ai_field_endpoint), top = 10.dp)
    SyncField(
        placeholder = stringResource(Res.string.settings_ai_placeholder_endpoint), value = byok.baseUrl, icon = "cloud", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
        onChange = byok::onEndpointChange,
    )
    // http:// sends the API key and prompt (with secrets under Permissive) in cleartext; warn,
    // except for localhost where cleartext is intentional for a local proxy.
    if (isInsecureAiEndpoint(byok.baseUrl)) {
        Txt(stringResource(Res.string.sync_insecure_url_warning), color = Skerry.colors.sunset, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }

    // ② API key.
    FieldLabel(stringResource(Res.string.settings_ai_field_api_key))
    SyncField(
        placeholder = stringResource(Res.string.settings_ai_placeholder_api_key), value = byok.key, icon = "key", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true,
        onChange = byok::onKeyChange,
    )

    // ③ Model: editable combo — type freely, or pick from the catalog the refresh button fetched.
    FieldLabel(stringResource(Res.string.settings_ai_field_model))
    // The dropdown wraps the whole field row (input + arrow button) so the menu opens below at the
    // full row width; a small trigger would pop a tiny menu that squeezes long model names and can
    // overflow the window edge on narrow layouts.
    AnchoredDropdown(
        expanded = byok.modelMenuOpen,
        onDismiss = { byok.modelMenuOpen = false },
        trigger = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SyncField(placeholder = stringResource(Res.string.settings_ai_placeholder_model), value = byok.model, icon = "auto_awesome", keyboardType = KeyboardType.Text, imeAction = ImeAction.Done, onChange = { byok.model = it })
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(Skerry.colors.bg)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                        // Opens even with an empty catalog: the menu then explains itself with
                        // "No models found" instead of a silent no-op before the first refresh.
                        .clickable { byok.modelMenuOpen = !byok.modelMenuOpen }
                        .padding(horizontal = 10.dp, vertical = 11.dp),
                ) { Sym("expand_more", size = 16.sp, color = Skerry.colors.faint) }
            }
        },
        menu = { width ->
            ModelPickerMenu(
                modifier = Modifier.width(width),
                models = byok.models,
                selected = byok.model,
                favorites = byok.favorites,
                onToggleFavorite = byok::toggleFavorite,
                onSelect = byok::onSelectModel,
                emptyText = stringResource(Res.string.settings_ai_models_empty),
                searchPlaceholder = stringResource(Res.string.settings_ai_search_models),
            )
        },
    )
    byok.refreshFailure?.let { failure ->
        Txt(aiFailureMessage(failure), color = Skerry.colors.sunset, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(stringResource(Res.string.settings_save), onClick = {
            ai.save(byok.key, byok.model, byok.baseUrl)
            byok.markSaved()
        })
        ChipButton(
            if (byok.refreshing) stringResource(Res.string.settings_ai_refreshing) else stringResource(Res.string.settings_ai_refresh_models),
            color = Skerry.colors.cyan,
            onClick = byok::refresh,
            // Local proxies (Ollama, LM Studio, …) take no API key — an endpoint alone is enough.
            enabled = byok.baseUrl.isNotBlank() && !byok.refreshing,
        )
        if (byok.hint != null) Txt(byok.hint!!, color = if (byok.hintFlash) Skerry.colors.moss else Skerry.colors.amber, size = 11.5.sp)
        else if (ai.isConfigured) Txt(stringResource(Res.string.settings_ai_key_saved), color = Skerry.colors.moss, size = 11.5.sp)
        else Txt(stringResource(Res.string.settings_ai_not_configured), color = Skerry.colors.faint, size = 11.5.sp)
    }
}

@Composable
private fun AiSectionMock(state: DesktopDesignState) {
    Txt(stringResource(Res.string.settings_ai_default_provider), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
    Txt(stringResource(Res.string.settings_ai_default_provider_desc), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
    ProviderCard("lock", stringResource(Res.string.settings_ai_provider_device), stringResource(Res.string.settings_ai_provider_device_desc), selected = true, badge = stringResource(Res.string.settings_ai_badge_private))
    Spacer(Modifier.height(8.dp))
    ProviderCard("key", stringResource(Res.string.settings_ai_provider_byok), stringResource(Res.string.settings_ai_provider_byok_desc), selected = false)
    Spacer(Modifier.height(8.dp))
    ProviderCard("block", stringResource(Res.string.settings_ai_provider_off), stringResource(Res.string.settings_ai_provider_off_desc), selected = false)
    Spacer(Modifier.height(18.dp))
    HLine()
    Spacer(Modifier.height(6.dp))
    SettingToggleRow(stringResource(Res.string.settings_ai_sanitize), stringResource(Res.string.settings_ai_sanitize_desc), state.sanitize, state::toggleSanitize)
    HLine()
    SettingToggleRow(stringResource(Res.string.settings_ai_preview), stringResource(Res.string.settings_ai_preview_desc), state.preview, state::togglePreview)
    HLine()
    SettingToggleRow(stringResource(Res.string.settings_ai_confirm), stringResource(Res.string.settings_ai_confirm_desc), state.confirm, state::toggleConfirm)
}
