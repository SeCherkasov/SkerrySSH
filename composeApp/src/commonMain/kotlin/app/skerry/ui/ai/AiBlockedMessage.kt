package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import app.skerry.shared.ai.AiRoute
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_ai_blocked_device_not_ready
import app.skerry.ui.generated.resources.term_ai_blocked_disabled
import app.skerry.ui.generated.resources.term_ai_blocked_not_configured
import app.skerry.ui.generated.resources.term_ai_blocked_strict
import org.jetbrains.compose.resources.stringResource

/**
 * Localized message for why the terminal AI bar was blocked ([TerminalAiController.blocked]). The
 * controller stores the typed [AiRoute.Reason]; the UI layer resolves it here so the text follows
 * the interface language instead of being hardcoded English in the controller.
 */
@Composable
fun aiBlockedMessage(reason: AiRoute.Reason): String = stringResource(
    when (reason) {
        AiRoute.Reason.CLOUD_NOT_CONFIGURED -> Res.string.term_ai_blocked_not_configured
        AiRoute.Reason.DEVICE_NOT_READY -> Res.string.term_ai_blocked_device_not_ready
        AiRoute.Reason.STRICT_NEEDS_DEVICE -> Res.string.term_ai_blocked_strict
        AiRoute.Reason.AI_DISABLED -> Res.string.term_ai_blocked_disabled
    },
)
