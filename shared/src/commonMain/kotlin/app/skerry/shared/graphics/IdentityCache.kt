package app.skerry.shared.graphics

/**
 * A tiny LRU keyed on reference identity, not equality. For the objects a wire cache re-announces
 * as the *same instance* (RDP's pointer cache): content equality would compare whole pixel arrays,
 * while identity makes "slot 3 again" a list scan. Single-thread use — every caller is confined to
 * one read loop; a content-equal duplicate under a different identity misses and recomputes, which
 * is only a small waste.
 */
class IdentityCache<K : Any, V>(private val capacity: Int) {
    private val entries = ArrayDeque<Pair<K, V>>()

    fun getOrPut(key: K, compute: () -> V): V {
        entries.firstOrNull { it.first === key }?.let { return it.second }
        val value = compute()
        entries.addFirst(key to value)
        if (entries.size > capacity) entries.removeLast()
        return value
    }
}
