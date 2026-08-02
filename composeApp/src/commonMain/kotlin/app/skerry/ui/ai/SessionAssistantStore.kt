package app.skerry.ui.ai

import app.skerry.shared.ai.AiPolicy

/**
 * One assistant conversation per pane, kept for as long as that pane is open.
 *
 * The panel is bound to the pane in focus, and switching tabs or panes must not silently discard
 * what was asked there — a controller lives with its pane, not with the panel's composition. A pane
 * whose host policy changed gets a fresh controller: the policy decides where its output may go, so
 * it cannot be patched into a running conversation.
 */
internal class SessionAssistantStore(private val create: (AiPolicy) -> SessionAssistantController) {

    private class Entry(val policy: AiPolicy, val controller: SessionAssistantController)

    private val byPane = mutableMapOf<String, Entry>()

    /** The conversation of [paneId] under [policy], creating or rebuilding it as needed. */
    fun controller(paneId: String, policy: AiPolicy): SessionAssistantController {
        val existing = byPane[paneId]
        if (existing != null && existing.policy == policy) return existing.controller
        existing?.controller?.cancel()
        return create(policy).also { byPane[paneId] = Entry(policy, it) }
    }

    /**
     * Drop the conversations of panes that are gone, cancelling anything they still had in flight —
     * a closed pane has nowhere to put an answer, and its request would keep a provider alive.
     */
    fun retain(paneIds: Set<String>) {
        val closed = byPane.keys - paneIds
        closed.forEach { id -> byPane.remove(id)?.controller?.cancel() }
    }

    /**
     * Drop every conversation, cancelling what they had in flight. The store outlives the panel's
     * composition, so nothing else would ever stop those requests: they run on the app-scoped
     * assistant scope and would keep streaming from the provider after AI was switched off.
     */
    fun close() {
        byPane.values.forEach { it.controller.cancel() }
        byPane.clear()
    }
}
