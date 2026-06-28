package app.skerry.ui.secure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Счётчик «держателей» защиты экрана от снимков. Флаг включается на первом захвате и снимается
 * только на последнем освобождении — перекрывающиеся секретные экраны (например таб Vault и диалог
 * поверх него) не снимают защиту преждевременно.
 *
 * apply вызывается из Compose-эффектов (UI-поток), поэтому счётчик без синхронизации.
 */
class SecureFlagController(private val apply: (Boolean) -> Unit) {
    private var holders = 0

    fun acquire() {
        holders++
        if (holders == 1) apply(true)
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) apply(false)
    }
}

/** Применяет флаг защиты от снимков экрана на платформе. Android — FLAG_SECURE; desktop — no-op. */
expect fun applyPlatformSecureFlag(secure: Boolean)

private val secureFlagController = SecureFlagController(::applyPlatformSecureFlag)

/**
 * Помечает экран как секретный: пока этот composable в композиции, окно защищено от снимков экрана
 * и превью в списке недавних приложений. Только Android; desktop — no-op.
 */
@Composable
fun SecureScreen() {
    DisposableEffect(Unit) {
        secureFlagController.acquire()
        onDispose { secureFlagController.release() }
    }
}
