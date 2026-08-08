package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_ai_hint_endpoint_changed
import app.skerry.ui.generated.resources.settings_ai_hint_key_changed
import app.skerry.ui.generated.resources.settings_ai_hint_model_changed
import app.skerry.ui.generated.resources.settings_ai_hint_model_selected
import app.skerry.ui.generated.resources.settings_ai_refresh_done
import app.skerry.ui.generated.resources.settings_ai_refreshing_models
import app.skerry.ui.generated.resources.settings_ai_saved
import org.jetbrains.compose.resources.stringResource

/**
 * Localized text of the BYOK status line. [ByokModelState] stores the typed [ByokHint]; the UI
 * resolves it here so the line follows the interface language (mirrors [aiFailureMessage]).
 */
@Composable
fun byokHintMessage(hint: ByokHint): String = stringResource(
    when (hint) {
        ByokHint.ENDPOINT_CHANGED -> Res.string.settings_ai_hint_endpoint_changed
        ByokHint.KEY_CHANGED -> Res.string.settings_ai_hint_key_changed
        ByokHint.MODEL_CHANGED -> Res.string.settings_ai_hint_model_changed
        ByokHint.MODEL_SELECTED -> Res.string.settings_ai_hint_model_selected
        ByokHint.REFRESHING -> Res.string.settings_ai_refreshing_models
        ByokHint.REFRESHED -> Res.string.settings_ai_refresh_done
        ByokHint.SAVED -> Res.string.settings_ai_saved
    },
)
