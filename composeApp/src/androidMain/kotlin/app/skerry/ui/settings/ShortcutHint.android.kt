package app.skerry.ui.settings

// Десктопная страница Keyboard на Android не показывается; хоткеи каркаса — только для физической
// клавиатуры десктопа.
internal actual fun isApplePlatform(): Boolean = false
