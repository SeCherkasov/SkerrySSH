package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_ai_local_error_integrity
import app.skerry.ui.generated.resources.settings_ai_local_error_network
import app.skerry.ui.generated.resources.settings_ai_local_error_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * Localized message for a failed local model download. [LocalModelController] stores the typed
 * [LocalModelFailure]; the UI layer resolves it here so the text follows the interface language.
 */
@Composable
fun localModelFailureMessage(failure: LocalModelFailure): String = stringResource(
    when (failure) {
        LocalModelFailure.NETWORK -> Res.string.settings_ai_local_error_network
        LocalModelFailure.INTEGRITY -> Res.string.settings_ai_local_error_integrity
        LocalModelFailure.UNKNOWN -> Res.string.settings_ai_local_error_unknown
    },
)
