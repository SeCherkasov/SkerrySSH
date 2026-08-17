package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteKeyEvent

/**
 * What the server believes is held down, and the rules for putting that belief right.
 *
 * A remote desktop has no way to ask; it only knows what this client told it. Two things go wrong
 * with that. A key-up can be lost — the window manager takes Alt+Tab and the Super key for itself
 * and keeps the release — and the modifier then stays down on the server for the rest of the
 * session, where every click arrives as Alt+click and the desktop stops answering the mouse. And
 * focus can leave with keys still down, which is what [releaseAll] is for (F-12).
 *
 * Split out of [RemoteDesktopScreenState] for the same reason [RemoteInputActor] was: it is state
 * with rules of its own that nothing else may touch.
 */
internal class HeldKeys {

    private val keys = LinkedHashMap<Long, RemoteKeyEvent>()

    // Keyed by the same identity as [keys], not by the modifier: left and right Alt are two keys the
    // server holds separately, and collapsing them lost the first one's identity — after which a
    // swallowed release for it could no longer be put right.
    private val modifiers = LinkedHashMap<Long, Pair<RemoteModifier, RemoteKeyEvent>>()

    /** Record a key going down or up. [modifier] names it when the key is one. */
    fun record(event: RemoteKeyEvent, down: Boolean, modifier: RemoteModifier?) {
        val id = identityOf(event)
        if (down) keys[id] = event else keys.remove(id)
        if (modifier == null) return
        if (down) modifiers[id] = modifier to event else modifiers.remove(id)
    }

    /**
     * The releases owed because the local machine no longer holds a modifier the server does —
     * in press order reversed, as a keyboard sends them. [except] is the modifier of the event this
     * reconciliation accompanies: its own transition belongs to [record], not here, or every
     * ordinary release would go out twice.
     */
    fun outOfStep(local: RemoteModifiers, except: RemoteModifier?): List<RemoteKeyEvent> {
        if (modifiers.isEmpty()) return emptyList()
        // Checked before anything is built: this runs on every raw mouse sample, and the answer is
        // "nothing drifted" for all but the one event that puts it right.
        if (modifiers.none { (_, held) -> held.first != except && !local.holds(held.first) }) {
            return emptyList()
        }
        val stale = modifiers.entries
            .filter { (_, held) -> held.first != except && !local.holds(held.first) }
        for ((id, _) in stale) {
            modifiers.remove(id)
            keys.remove(id)
        }
        return stale.map { (_, held) -> held.second }.asReversed()
    }

    /** Everything still down, in reverse press order — what a lost focus owes the server (F-12). */
    fun releaseAll(): List<RemoteKeyEvent> {
        val all = keys.values.toList().asReversed()
        keys.clear()
        modifiers.clear()
        return all
    }

    /** What makes a press and its release the same key, whichever field the protocol will use. */
    private fun identityOf(event: RemoteKeyEvent): Long = when {
        // A multi-scancode key (F-18) has no flat scancode; its first scan is unique among the
        // sequences, and collapsing them all to one identity dropped a release on focus loss.
        event.sequence.isNotEmpty() -> event.sequence.first().scancode.toLong() or (1L shl 56)
        event.scancode != 0 -> event.scancode.toLong() or (if (event.extended) 1L shl 32 else 0L)
        event.keySym != 0L -> event.keySym or (1L shl 40)
        else -> event.codePoint.toLong() or (1L shl 48)
    }
}
