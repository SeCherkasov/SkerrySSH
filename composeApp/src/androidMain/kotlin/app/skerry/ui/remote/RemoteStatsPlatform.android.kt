package app.skerry.ui.remote

// Android renders through the platform's own pipeline; there is no skiko.renderApi to report.
internal actual fun effectiveRenderApi(): String? = null
