package app.skerry.ui.design

import app.skerry.ui.terminal.DirectClipboard

/**
 * The platform's own clipboard path — the one Compose/AWT cannot see — present or not, holding
 * [content], and accepting writes or refusing them. Both answers matter: they differ between a
 * Wayland desktop and a headless CI, so nothing written against the real one would mean the same
 * thing twice (#282).
 */
internal class FakeDirectClipboard(
    private val owns: Boolean,
    private val content: String? = null,
    private val accepts: Boolean = true,
    /** The utility behind the path could not answer at all — distinct from a clipboard holding nothing. */
    private val refusesRead: Boolean = false,
) : DirectClipboard {

    /** Every text handed to [write], refused ones included. */
    val writes = mutableListOf<String>()

    override fun owns(): Boolean = owns

    override fun read(): String? =
        if (refusesRead) error("the platform clipboard did not answer") else content

    override fun write(text: String): Boolean {
        writes += text
        return accepts
    }
}
