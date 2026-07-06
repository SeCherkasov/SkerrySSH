package app.skerry.ui.settings

// The desktop Keyboard settings page isn't shown on Android; shortcuts apply only to a physical
// desktop keyboard.
internal actual fun isApplePlatform(): Boolean = false
