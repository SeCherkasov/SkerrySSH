package app.skerry.ui.remote

import androidx.compose.ui.graphics.ImageBitmap
import app.skerry.shared.io.safeFileStem

/**
 * Writes the current remote frame to a PNG the user can find outside the app: the pictures folder on
 * desktop, the gallery on Android. [baseName] names the file and is sanitized by the implementation.
 *
 * Returns where it landed, for the confirmation the panel shows, or null when it could not be
 * written — a full disk or a revoked media permission is not worth an error dialog over a screenshot.
 */
expect suspend fun saveRemoteScreenshot(image: ImageBitmap, baseName: String): String?

/**
 * A file name that is safe on every platform: a host name can carry anything a URL can. Dots are
 * kept — a host name is mostly dots — and [safeFileStem] answers for the rest.
 */
internal fun screenshotFileName(baseName: String, stamp: String): String =
    "skerry-${safeFileStem(baseName, fallback = "desktop", keepDots = true)}-$stamp.png"
