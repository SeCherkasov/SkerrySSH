package app.skerry.ui.design

import app.skerry.ui.terminal.SystemClipboard

/**
 * The platform's own clipboard, kept in memory: it records what was written, hands back what it
 * holds, and can refuse the way `wl-copy`/`wl-paste` do — a refused write throws where the direct
 * path owns the clipboard, and a read that could not answer throws rather than looking empty (#282).
 *
 * [refuseWrites] refuses that many leading writes and takes the rest, so a test can show that one
 * refusal is one refusal and not the end of the surface it happened on.
 */
internal class FakeSystemClipboard(
    private var content: String? = null,
    private val refuseWrites: Int = 0,
    private val refusesRead: Boolean = false,
) : SystemClipboard {

    /** Every text handed to [write], refused ones included. */
    val writes = mutableListOf<String>()

    override suspend fun read(): String? =
        if (refusesRead) error("the platform clipboard could not answer") else content

    override suspend fun write(text: String) {
        writes += text
        if (writes.size <= refuseWrites) error("the platform clipboard refused the text")
        content = text
    }
}
