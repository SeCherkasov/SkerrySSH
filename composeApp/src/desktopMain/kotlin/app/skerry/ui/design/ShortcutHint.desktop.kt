package app.skerry.ui.design

internal actual fun isApplePlatform(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true
