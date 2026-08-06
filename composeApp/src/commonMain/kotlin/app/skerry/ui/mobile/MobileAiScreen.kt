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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.ui.AiModelCache
import app.skerry.ui.ai.AiChatBubble
import app.skerry.ui.ai.AiFailure
import app.skerry.ui.ai.AiQuickChatHeader
import app.skerry.ui.ai.aiFailureMessage
import app.skerry.ui.ai.isInsecureAiEndpoint
import app.skerry.ui.ai.toFailure
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.ai.ModelPickerMenu
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.settings_ai_ask
import app.skerry.ui.generated.resources.settings_ai_field_api_key
import app.skerry.ui.generated.resources.settings_ai_field_endpoint
import app.skerry.ui.generated.resources.settings_ai_field_model
import app.skerry.ui.generated.resources.settings_ai_hint_endpoint_changed
import app.skerry.ui.generated.resources.settings_ai_hint_key_changed
import app.skerry.ui.generated.resources.settings_ai_hint_model_selected
import app.skerry.ui.generated.resources.settings_ai_key_saved
import app.skerry.ui.generated.resources.settings_ai_live_subtitle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobilePushHeader(stringResource(Res.string.more_ai_privacy), onBack = state::pop)
        if (ai == null) return@Column
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
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
                // Request error uses the error token (Skerry.colors.storm), as on desktop; Skerry.colors.sunset is reserved for warnings.
                ai.error?.let { Txt(aiFailureMessage(it), color = Skerry.colors.storm, size = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }

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
    var key by remember(ai.settings) { mutableStateOf(ai.settings.apiKey) }
    var model by remember(ai.settings) { mutableStateOf(ai.settings.model) }
    var baseUrl by remember(ai.settings) { mutableStateOf(ai.settings.baseUrl) }
    var models by remember(ai.settings) { mutableStateOf(AiModelCache.load(baseUrl)) }
    var favorites by remember(ai.settings) { mutableStateOf(AiModelCache.loadFavorites(baseUrl)) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailure by remember { mutableStateOf<AiFailure?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    // Status hint next to the action buttons: Pending stays until superseded (e.g. "key changed —
    // press Save", "refreshing…"), Flash clears itself after 3s (e.g. "models refreshed", "saved").
    var hint by remember { mutableStateOf<String?>(null) }
    var hintFlash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(hint, hintFlash) {
        if (hint != null && hintFlash) {
            delay(3000)
            hint = null
        }
    }
    // Hint texts must be resolved here (composable context) — stringResource is @Composable and
    // cannot be called from onChange/onClick lambdas.
    val hintEndpoint = stringResource(Res.string.settings_ai_hint_endpoint_changed)
    val hintKey = stringResource(Res.string.settings_ai_hint_key_changed)
    val hintModel = stringResource(Res.string.settings_ai_hint_model_selected)
    val hintRefreshing = stringResource(Res.string.settings_ai_refreshing_models)
    val hintRefreshed = stringResource(Res.string.settings_ai_refresh_done)
    val hintSaved = stringResource(Res.string.settings_ai_saved)

    Column(Modifier.padding(top = 10.dp)) {
        // ① Server address (endpoint): what the other two fields talk to.
        MobileFormField(stringResource(Res.string.settings_ai_field_endpoint)) {
            MobileFormInput(
                baseUrl,
                {
                    baseUrl = it
                    hint = hintEndpoint; hintFlash = false
                },
                stringResource(Res.string.settings_ai_placeholder_endpoint), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
            )
        }
        // http:// sends the key/prompt in plaintext — warn, except for localhost.
        if (isInsecureAiEndpoint(baseUrl)) {
            Txt(stringResource(Res.string.sync_insecure_url_warning), color = Skerry.colors.sunset, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(12.dp))
        // ② API key.
        MobileFormField(stringResource(Res.string.settings_ai_field_api_key)) {
            MobileFormInput(
                key,
                {
                    key = it
                    hint = hintKey; hintFlash = false
                },
                stringResource(Res.string.settings_ai_placeholder_api_key), masked = true, imeAction = ImeAction.Next,
            )
        }

        Spacer(Modifier.height(12.dp))
        // ③ Model: editable combo — type freely, or pick from the catalog the refresh button fetched.
        MobileFormField(stringResource(Res.string.settings_ai_field_model)) {
            // The dropdown wraps the whole field row (input + arrow button), so the menu opens
            // below the field at the full row width — a small trigger would pop a tiny menu that
            // squeezes long model names and overflows the screen on phones.
            AnchoredDropdown(
                expanded = modelMenuOpen,
                onDismiss = { modelMenuOpen = false },
                trigger = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            MobileFormInput(model, { model = it }, stringResource(Res.string.settings_ai_placeholder_model), imeAction = ImeAction.Done)
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(11.dp))
                                .background(Skerry.colors.bg)
                                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                                .clickable(enabled = models.isNotEmpty()) { modelMenuOpen = !modelMenuOpen }
                                .padding(horizontal = 12.dp, vertical = 13.dp),
                        ) { Sym("expand_more", size = 16.sp, color = Skerry.colors.faint) }
                    }
                },
                menu = { width ->
                    ModelPickerMenu(
                        width = width,
                        models = models,
                        selected = model,
                        favorites = favorites,
                        onToggleFavorite = { id ->
                            favorites = if (id in favorites) favorites - id else favorites + id
                            AiModelCache.saveFavorite(baseUrl, id, id in favorites)
                        },
                        onSelect = { m ->
                            model = m
                            modelMenuOpen = false
                            // Picking fills the field; nothing is persisted until Save (visible hint reminds that).
                            hint = hintModel; hintFlash = false
                        },
                        emptyText = stringResource(Res.string.settings_ai_models_empty),
                        searchPlaceholder = stringResource(Res.string.settings_ai_search_models),
                    )
                },
            )
        }
        refreshFailure?.let { failure ->
            Txt(aiFailureMessage(failure), color = Skerry.colors.sunset, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChipButton(stringResource(Res.string.settings_save), color = Skerry.colors.cyan, onClick = {
                ai.save(key, model, baseUrl)
                hint = hintSaved; hintFlash = true
            })
            ChipButton(
                if (refreshing) stringResource(Res.string.settings_ai_refreshing) else stringResource(Res.string.settings_ai_refresh_models),
                color = Skerry.colors.cyan,
                onClick = {
                    refreshFailure = null
                    refreshing = true
                    hint = hintRefreshing; hintFlash = false
                    scope.launch {
                        val result = ai.listModels(key, baseUrl)
                        refreshing = false
                        result.fold(
                            onSuccess = { fetched ->
                                models = fetched
                                AiModelCache.save(baseUrl, fetched)
                                hint = hintRefreshed; hintFlash = true
                            },
                            onFailure = { e ->
                                refreshFailure = if (e is AiException) e.toFailure() else AiFailure.UNKNOWN
                                hint = null
                            },
                        )
                    }
                },
                enabled = key.isNotBlank() && baseUrl.isNotBlank() && !refreshing,
            )
            if (hint != null) Txt(hint!!, color = if (hintFlash) Skerry.colors.moss else Skerry.colors.amber, size = 11.5.sp)
            else if (ai.isConfigured) Txt(stringResource(Res.string.settings_ai_key_saved), color = Skerry.colors.moss, size = 11.5.sp)
            else Txt(stringResource(Res.string.settings_ai_not_configured), color = Skerry.colors.faint, size = 11.5.sp)
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
