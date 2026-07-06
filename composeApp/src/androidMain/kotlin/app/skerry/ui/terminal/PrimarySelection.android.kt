package app.skerry.ui.terminal

/** Android has no PRIMARY selection; middle-click paste falls back to the regular clipboard. */
internal actual fun readPrimarySelectionText(): String? = null

/** Android has no PRIMARY selection; no-op. */
internal actual fun writePrimarySelectionText(text: String) = Unit
