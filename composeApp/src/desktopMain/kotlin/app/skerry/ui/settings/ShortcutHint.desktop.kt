package app.skerry.ui.settings

internal actual fun isApplePlatform(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true
