package app.skerry.ui.app

import org.jetbrains.skiko.OS

/**
 * The `skiko.renderApi` value for a [RenderBackend] on [os], or null for [RenderBackend.AUTO] —
 * setting nothing keeps Skiko's own pick and honours an explicit `-Dskiko.renderApi` from the
 * command line. HARDWARE names the platform's GPU API because Skiko has no single "any GPU" value.
 * Top-level so the mapping is testable without touching system properties (same reasoning as
 * `ffmpegH264Command`).
 */
internal fun skikoRenderApiFor(backend: RenderBackend, os: OS): String? = when (backend) {
    RenderBackend.AUTO -> null
    RenderBackend.SOFTWARE -> "SOFTWARE"
    RenderBackend.HARDWARE -> when (os) {
        OS.MacOS -> "METAL"
        OS.Windows -> "DIRECT3D"
        else -> "OPENGL"
    }
}
