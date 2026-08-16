package app.skerry.ui.remote

/**
 * The Skia render backend in effect, for the diagnostics overlay (F-30): the requested
 * `skiko.renderApi`, or "auto" when Skiko is picking. Null on platforms with no such knob
 * (Android), which hides the row. The *requested* value, honestly labelled: Skiko does not expose
 * which API it actually initialised through a public property.
 */
internal expect fun effectiveRenderApi(): String?
