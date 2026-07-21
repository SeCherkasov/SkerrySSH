package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_ai_error_engine_crashed
import app.skerry.ui.generated.resources.settings_ai_error_invalid_request
import app.skerry.ui.generated.resources.settings_ai_error_network
import app.skerry.ui.generated.resources.settings_ai_error_protocol
import app.skerry.ui.generated.resources.settings_ai_error_rate_limited
import app.skerry.ui.generated.resources.settings_ai_error_unauthorized
import app.skerry.ui.generated.resources.settings_ai_error_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * Localized message for a failed AI request. Controllers store the typed [AiFailure]; the UI layer
 * resolves it here so the text follows the interface language (see also `aiBlockedMessage`).
 */
@Composable
fun aiFailureMessage(failure: AiFailure): String = stringResource(
    when (failure) {
        AiFailure.UNAUTHORIZED -> Res.string.settings_ai_error_unauthorized
        AiFailure.RATE_LIMITED -> Res.string.settings_ai_error_rate_limited
        AiFailure.NETWORK -> Res.string.settings_ai_error_network
        AiFailure.INVALID_REQUEST -> Res.string.settings_ai_error_invalid_request
        AiFailure.PROTOCOL -> Res.string.settings_ai_error_protocol
        AiFailure.ENGINE_CRASHED -> Res.string.settings_ai_error_engine_crashed
        AiFailure.UNKNOWN -> Res.string.settings_ai_error_unknown
    },
)
