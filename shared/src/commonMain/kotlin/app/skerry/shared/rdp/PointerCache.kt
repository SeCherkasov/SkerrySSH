package app.skerry.shared.rdp

/**
 * The cursor shapes the server parked in the pointer cache (MS-RDPBCGR 2.2.9.1.1.4.6).
 *
 * Every shape update carries the slot the server filed it under; once a shape is in a slot, the
 * server switches back to it with a Cached Pointer Update that carries nothing but the index. That
 * is how the cursor goes back to the arrow after an I-beam — without the cache the client has no
 * shape to restore and the last explicit one stays on screen.
 *
 * Owned by the session and shared by both decoders, like [SessionPalette]: a shape can arrive
 * fast-path and be recalled slow-path.
 */
class PointerCache {
    private val slots = arrayOfNulls<RdpUpdate.PointerShape>(CAPACITY)

    /** File [shape] under [index]. Indices past the cache we advertised are dropped, not stored. */
    fun put(index: Int, shape: RdpUpdate.PointerShape) {
        if (index in slots.indices) slots[index] = shape
    }

    /** The shape in [index], or null for a slot the server never filled. */
    fun get(index: Int): RdpUpdate.PointerShape? = slots.getOrNull(index)

    companion object {
        /** Slots, and the number advertised as colorPointerCacheSize/pointerCacheSize. */
        const val CAPACITY = 25
    }
}
