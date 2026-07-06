package app.skerry.ui.nav

import androidx.compose.runtime.Composable

/**
 * Intercepts the system back button/gesture. Android delegates to `androidx.activity.compose.BackHandler`
 * (registered on the Activity's `OnBackPressedDispatcher`); desktop is a no-op (no system back).
 *
 * When [enabled] is false the handler is transparent: the event passes to the next one in the stack
 * (the deepest/most recently composed [PlatformBackHandler] intercepts first, LIFO).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
