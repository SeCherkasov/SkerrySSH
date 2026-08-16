package app.skerry.ui.remote

internal actual fun effectiveRenderApi(): String? =
    System.getProperty("skiko.renderApi")?.lowercase() ?: "auto"
