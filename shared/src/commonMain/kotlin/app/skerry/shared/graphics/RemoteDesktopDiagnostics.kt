package app.skerry.shared.graphics

import kotlin.concurrent.Volatile

/**
 * Live counters for one remote-desktop session, behind the diagnostics overlay (and the trace log).
 *
 * Written by the session's read loop as it decodes — every writer of a given field is either that
 * single thread or serialised by the transport's write lock — and read by the UI on a slow poll, so
 * plain `@Volatile` fields are enough: no field has two concurrent writers, and the overlay only
 * needs a recent value, not a consistent cut across all of them. [snapshot] exists so the overlay's
 * rate arithmetic works on one immutable value instead of racing the counters field by field.
 */
class RemoteDesktopDiagnostics {

    /** Graphics paths seen so far ("EGFX", "Surface bits", "Bitmap"), in first-seen order. */
    val paths: List<String> get() = pathList

    @Volatile
    private var pathList: List<String> = emptyList()

    /** The codec of the last decoded image ("Progressive", "RemoteFX", "AVC444", "RLE", …). */
    @Volatile
    var lastCodec: String? = null
        private set

    /** What the graphics capability exchange settled on ("GFX 10.4"); null before/without one. */
    @Volatile
    var negotiated: String? = null
        private set

    /** Which H.264 decoder serves the session ("ffmpeg (hwaccel auto)"); null without one (F-29). */
    @Volatile
    var decoder: String? = null
        private set

    /** Completed server frames (frame markers / EGFX frame ends). */
    @Volatile
    var serverFrames: Long = 0
        private set

    /** Drawing-order updates skipped because orders were never advertised (F-03's deciding count). */
    @Volatile
    var droppedOrders: Long = 0
        private set

    /** Bitmap rectangles received and not drawn (undecodable or refused before allocation). */
    @Volatile
    var droppedRects: Long = 0
        private set

    /** Full-desktop repaints this client asked for to recover dropped graphics. */
    @Volatile
    var fullRepaints: Long = 0
        private set

    @Volatile
    var bytesIn: Long = 0
        private set

    @Volatile
    var bytesOut: Long = 0
        private set

    /** Time spent decoding server graphics, and how many timed decodes it covers. */
    @Volatile
    var decodeNanos: Long = 0
        private set

    @Volatile
    var decodeCount: Long = 0
        private set

    fun notePath(name: String) {
        if (name !in pathList) pathList = pathList + name
    }

    fun noteCodec(name: String) {
        lastCodec = name
    }

    fun noteNegotiated(text: String) {
        negotiated = text
    }

    fun noteDecoder(text: String) {
        decoder = text
    }

    fun serverFrame() {
        serverFrames++
    }

    fun droppedOrder() {
        droppedOrders++
    }

    fun droppedRect() {
        droppedRects++
    }

    fun fullRepaint() {
        fullRepaints++
    }

    fun readBytes(count: Int) {
        bytesIn += count
    }

    fun wroteBytes(count: Int) {
        bytesOut += count
    }

    fun decodeTime(nanos: Long) {
        decodeNanos += nanos
        decodeCount++
    }

    /** One immutable cut of every counter, for delta arithmetic on the reader's side. */
    fun snapshot(): Snapshot = Snapshot(
        paths = paths,
        lastCodec = lastCodec,
        negotiated = negotiated,
        decoder = decoder,
        serverFrames = serverFrames,
        droppedOrders = droppedOrders,
        droppedRects = droppedRects,
        fullRepaints = fullRepaints,
        bytesIn = bytesIn,
        bytesOut = bytesOut,
        decodeNanos = decodeNanos,
        decodeCount = decodeCount,
    )

    data class Snapshot(
        val paths: List<String>,
        val lastCodec: String?,
        val negotiated: String?,
        val decoder: String?,
        val serverFrames: Long,
        val droppedOrders: Long,
        val droppedRects: Long,
        val fullRepaints: Long,
        val bytesIn: Long,
        val bytesOut: Long,
        val decodeNanos: Long,
        val decodeCount: Long,
    )

    companion object {
        /** A session that reports nothing — the default for test doubles and stub sessions. */
        val NONE = RemoteDesktopDiagnostics()
    }
}
