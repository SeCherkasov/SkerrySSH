package app.skerry.ui.remote

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Writes the current remote frame to a PNG the user can find outside the app: the pictures folder on
 * desktop, the gallery on Android. [baseName] names the file and is sanitized by the implementation.
 *
 * Returns where it landed, for the confirmation the panel shows, or null when it could not be
 * written — a full disk or a revoked media permission is not worth an error dialog over a screenshot.
 */
expect suspend fun saveRemoteScreenshot(image: ImageBitmap, baseName: String): String?

/** A file name that is safe on every platform: a host name can carry anything a URL can. */
internal fun screenshotFileName(baseName: String, stamp: String): String {
    // Dots are kept — a host name is mostly dots — but never two in a row, which is the one spelling
    // that means a directory above this one.
    val safe = baseName.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
        .joinToString("")
        .replace("..", "-")
        .replace(Regex("-+"), "-")
        .trim('-', '.')
        .take(48)
        // Trimmed again: the cut can land on a separator and leave one dangling before the stamp.
        .trim('-', '.')
        .ifEmpty { "desktop" }
    return "skerry-$safe-$stamp.png"
}
