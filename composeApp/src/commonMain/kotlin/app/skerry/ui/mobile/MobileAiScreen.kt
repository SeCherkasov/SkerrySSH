package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.ui.ai.AiChatBubble
import app.skerry.ui.ai.AiChatError
import app.skerry.ui.ai.AiQuickChatHeader
import app.skerry.ui.ai.byokHintMessage
import app.skerry.ui.ai.isInsecureAiEndpoint
import app.skerry.ui.ai.rememberByokModelState
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.ai.ModelPickerMenu
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.ComboArrow
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.settings_ai_ask
import app.skerry.ui.generated.resources.settings_ai_field_api_key
import app.skerry.ui.generated.resources.settings_ai_field_endpoint
import app.skerry.ui.generated.resources.settings_ai_field_model
import app.skerry.ui.generated.resources.settings_ai_key_saved
import app.skerry.ui.generated.resources.settings_ai_live_subtitle
import app.skerry.ui.generated.resources.settings_ai_models_empty
import app.skerry.ui.generated.resources.settings_ai_search_models
import app.skerry.ui.generated.resources.settings_ai_show_models
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
import app.skerry.ui.generated.resources.settings_ai_refresh_models
import app.skerry.ui.generated.resources.settings_ai_refreshing
import app.skerry.ui.generated.resources.settings_ai_sending
import app.skerry.ui.generated.resources.settings_clear
import app.skerry.ui.generated.resources.settings_save
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.settings.AiProviderCards
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/**
 * Mobile AI settings screen (More -> "AI & privacy"), parity with desktop `LiveAiSection`:
 * provider cards (local model / BYOK / off), BYOK fields + Save to vault, quick chat. Fields/
 * labels/buttons use shared primitives ([MobileFormField]/[MobileFormInput]/[ChipButton]) so the
 * screen doesn't drift in width/height from the other mobile forms. Doesn't open without a live
 * controller ([LocalAi] == null); the More row is inert then, so `ai` is non-null here.
 */
@Composable
fun MobileAiScreen(state: MobileDesignState) {
    val ai = LocalAi.current
    // imePadding BEFORE verticalScroll: applied after the scroll it would only pad the scrolling
    // content while the viewport keeps its full height, leaving a mid-page field (the model picker)
    // under the keyboard. First, it shrinks the viewport itself and the scroll position follows the
    // focused field (mirroring the terminal view's lift). Root-level padding would only shrink the
    // page bottom and leave a mid-page field covered. Popup windows receive no IME insets, which is
    // why the picker below is inline in the page flow, not a Popup.
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobilePushHeader(stringResource(Res.string.more_ai_privacy), onBack = state::pop)
        if (ai == null) return@Column
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(
                stringResource(Res.string.settings_ai_live_subtitle),
                color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp),
            )

            // Provider selection is shared with desktop settings (AiProviderCards): same state and
            // logic; BYOK fields expand inside their card (mobile layout below).
            AiProviderCards(ai, byokContent = { MobileByokFields(ai) })

            // AI off: quick chat hidden, config stays saved and comes back with the provider.
            if (!ai.enabled) {
                Txt(
                    stringResource(Res.string.settings_ai_off_note),
                    color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp),
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
                ai.error?.let { AiChatError(it) }

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
                    ChipButton(if (ai.busy) stringResource(Res.string.settings_ai_sending) else stringResource(Res.string.settings_ai_ask), color = if (ai.ready && !ai.busy) Skerry.colors.cyan else Skerry.colors.faint, onClick = { send() })
                    if (ai.turns.isNotEmpty()) ChipButton(stringResource(Res.string.settings_clear), color = Skerry.colors.dim, onClick = { ai.clearConversation() })
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

/**
 * BYOK fields inside the "My API key" card (mobile layout via [MobileFormField]/[MobileFormInput]);
 * expand together with the card selection, state and Save shared with desktop. Order: server
 * address → API key → model. The model field is an editable combo (type freely, or open the
 * dropdown to pick one from the catalog the refresh button fetched).
 */
@Composable
private fun MobileByokFields(ai: app.skerry.ui.ai.AiAssistantController) {
    val byok = rememberByokModelState(ai)

    Column(Modifier.padding(top = 10.dp)) {
        // ① Server address (endpoint): what the other two fields talk to.
        MobileFormField(stringResource(Res.string.settings_ai_field_endpoint)) {
            MobileFormInput(
                byok.baseUrl,
                byok::onEndpointChange,
                stringResource(Res.string.settings_ai_placeholder_endpoint), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
            )
        }
        // http:// sends the key/prompt in plaintext — warn, except for localhost.
        if (isInsecureAiEndpoint(byok.baseUrl)) {
            Txt(
                stringResource(Res.string.sync_insecure_url_warning),
                color = Skerry.colors.sunset, size = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp).testTag(UiTags.AI_INSECURE_ENDPOINT),
            )
        }

        Spacer(Modifier.height(12.dp))
        // ② API key.
        MobileFormField(stringResource(Res.string.settings_ai_field_api_key)) {
            MobileFormInput(
                byok.key,
                byok::onKeyChange,
                stringResource(Res.string.settings_ai_placeholder_api_key), masked = true, imeAction = ImeAction.Next,
            )
        }

        Spacer(Modifier.height(12.dp))
        // ③ Model: editable combo — type freely, or pick from the catalog the refresh button fetched.
        MobileFormField(stringResource(Res.string.settings_ai_field_model)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    MobileFormInput(byok.model, byok::onModelChange, stringResource(Res.string.settings_ai_placeholder_model), imeAction = ImeAction.Done)
                }
                // Opens even with an empty catalog: the menu then explains itself with "No models
                // found" instead of a silent no-op before the first refresh.
                ComboArrow(
                    label = stringResource(Res.string.settings_ai_show_models),
                    onClick = { byok.modelMenuOpen = !byok.modelMenuOpen },
                    corner = 11.dp,
                    horizontalPadding = 12.dp,
                    verticalPadding = 13.dp,
                )
            }
        }
        // The picker expands IN the page flow instead of a Popup: Popup windows get no IME insets and
        // stay put under the soft keyboard, but the screen root's imePadding lifts this whole page —
        // search box and list together — above the keyboard when the search field gains focus. Desktop
        // has no IME: the inline menu simply pushes the Save row down (scrollable, fine).
        if (byok.modelMenuOpen) {
            Spacer(Modifier.height(8.dp))
            ModelPickerMenu(
                modifier = Modifier.fillMaxWidth(),
                models = byok.models,
                selected = byok.model,
                favorites = byok.favorites,
                onToggleFavorite = byok::toggleFavorite,
                onSelect = byok::onSelectModel,
                emptyText = stringResource(Res.string.settings_ai_models_empty),
                searchPlaceholder = stringResource(Res.string.settings_ai_search_models),
                maxHeight = 260.dp,
            )
            Spacer(Modifier.height(8.dp))
        }
        byok.refreshFailure?.let { failure ->
            AiChatError(failure, compact = true)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChipButton(stringResource(Res.string.settings_save), color = Skerry.colors.cyan, onClick = {
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
            val hint = byok.hint
            if (hint != null) {
                // A live region: a refresh has no other feedback for a screen reader.
                Txt(
                    byokHintMessage(hint),
                    color = if (hint.flash) Skerry.colors.moss else Skerry.colors.amber,
                    size = 11.5.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (ai.isConfigured) {
                Txt(stringResource(Res.string.settings_ai_key_saved), color = Skerry.colors.moss, size = 11.5.sp)
            } else {
                Txt(stringResource(Res.string.settings_ai_not_configured), color = Skerry.colors.faint, size = 11.5.sp)
            }
        }
    }
}

/** Screen section divider: consistent spacing above/below (18/14), matching the desktop section. */
@Composable
private fun MobileAiDivider() {
    Spacer(Modifier.height(18.dp))
    HLine()
    Spacer(Modifier.height(14.dp))
}
