package app.skerry.ui.settings

/**
 * macOS ли текущая платформа. Определяет символику подписей хоткеев на странице Keyboard: на Apple
 * модификатор приложения — `⌘`/`⌥`, на Linux/Windows — `Ctrl+Shift`/`Alt` (ровно как распознаёт
 * [matchDesktopShortcut]). Android-actual возвращает `false` (десктопная страница там не показывается).
 */
internal expect fun isApplePlatform(): Boolean
