package app.skerry.ui.nav

import androidx.compose.runtime.Composable

/**
 * Перехват системного «назад» (кнопка/жест). Android — делегирует `androidx.activity.compose.BackHandler`
 * (регистрация в `OnBackPressedDispatcher` Activity); desktop — no-op (системного back нет, навигация
 * внутри экранов идёт мышью/клавишами).
 *
 * При [enabled]=false обработчик прозрачен: событие уходит следующему по стеку (более «глубокий»/позже
 * скомпонованный [PlatformBackHandler] перехватывает первым — диспетчер вызывает обработчики LIFO).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
