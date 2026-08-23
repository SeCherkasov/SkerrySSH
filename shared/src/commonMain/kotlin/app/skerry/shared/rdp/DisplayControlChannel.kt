package app.skerry.shared.rdp

import app.skerry.shared.rdp.egfx.DynamicChannelHandler
import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/**
 * The display control channel (MS-RDPEDISP): the only way a client can change a running session's
 * resolution, which is what makes an RDP window follow its frame instead of scaling a picture fixed
 * at connect time.
 *
 * The exchange is short. The server opens the channel and states its limits; from then on the client
 * sends a whole monitor layout whenever the viewport settles. The server answers by tearing the
 * share down and demanding capabilities again — the reactivation `RdpSessionCodec` already handles —
 * so nothing here waits for a reply.
 *
 * The layout is one monitor, since a session lives in a single window on both platforms. Sizes are
 * fitted to what the protocol and the server allow ([fit]) rather than sent as asked: a request the
 * server considers invalid is not answered at all, and the session would sit at the old size with no
 * sign of why.
 */
class DisplayControlChannel(private val send: suspend (ByteArray) -> Unit) : DynamicChannelHandler {

    /**
     * The server's limits, and whether they have arrived. Written by the read loop, read by whoever
     * asks for a resolution — a UI coroutine — so they are `@Volatile` for the same reason the window
     * size in `TelnetCodec` is.
     */
    @Volatile private var maxArea = 0L

    @Volatile private var capable = false

    /** Updates decoded since the last drain; both sides of this run on the read loop. */
    private val updates = mutableListOf<RdpUpdate>()

    override suspend fun onMessage(data: ByteArray) {
        val reader = RdpReader(data)
        while (reader.remaining >= HEADER_SIZE) {
            val type = reader.u32le()
            val length = reader.u32le()
            if (length < HEADER_SIZE || length - HEADER_SIZE > reader.remaining) {
                throw RdpProtocolException("a display control PDU of $length bytes does not fit its message")
            }
            val body = reader.slice(length - HEADER_SIZE)
            // The caps PDU is the only server→client message this protocol has.
            if (type == PDU_TYPE_CAPS) caps(body)
        }
    }

    /** The updates decoded since the last call; the session emits them into its flow. */
    fun drainUpdates(): List<RdpUpdate> {
        if (updates.isEmpty()) return emptyList()
        val out = updates.toList()
        updates.clear()
        return out
    }

    /**
     * Ask the server for a [width]×[height] desktop, drawn at [scale] (1.0 = 100%) on this display
     * so the session lays itself out at the local DPI instead of at 96 (see [RdpDisplayScale]).
     * A no-op until the server has stated its limits:
     * the channel exists but says nothing before its capability PDU, and a layout sent then is
     * discarded. Returns whether a layout went out.
     */
    suspend fun requestResolution(width: Int, height: Int, scale: Float = 1f): Boolean {
        if (!capable) return false
        val size = fit(width, height) ?: return false
        // Scaling is derived from the size actually asked for, not the one requested: [fit] may have
        // shrunk it, and millimetres computed for the larger one would state a DPI this layout does
        // not have.
        send(monitorLayout(size.first, size.second, RdpDisplayScale.of(size.first, size.second, scale)))
        return true
    }

    private fun caps(body: RdpReader) {
        // A short caps PDU is a server this client cannot size: acting on half of it would mean
        // inventing the limits the layout has to respect.
        if (body.remaining < CAPS_BODY_SIZE) return
        body.u32le() // MaxNumMonitors: this client sends one either way
        val factorA = body.u32le().toLong() and UNSIGNED
        val factorB = body.u32le().toLong() and UNSIGNED
        maxArea = factorA * factorB
        if (capable) return
        capable = true
        updates += RdpUpdate.ResizeSupported
    }

    /**
     * The nearest size the server will accept: inside the protocol's 200..8192 range, an even width
     * (MS-RDPEDISP 2.2.2.2.1), and within the area the capability PDU allowed — scaled down keeping
     * the aspect ratio, since a squashed desktop is worse than a smaller one. Null when even the
     * minimum would not fit, which no real server reports.
     */
    private fun fit(width: Int, height: Int): Pair<Int, Int>? {
        var w = width.coerceIn(MIN_SIZE, MAX_SIZE)
        var h = height.coerceIn(MIN_SIZE, MAX_SIZE)
        if (maxArea > 0 && w.toLong() * h > maxArea) {
            val scale = sqrt(maxArea.toDouble() / (w.toDouble() * h))
            w = (w * scale).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
            h = (h * scale).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
        }
        w -= w % 2
        if (maxArea > 0 && w.toLong() * h > maxArea) return null
        return w to h
    }

    private fun monitorLayout(width: Int, height: Int, scale: RdpDisplayScale): ByteArray = RdpWriter(PDU_SIZE)
        .u32le(PDU_TYPE_MONITOR_LAYOUT)
        .u32le(PDU_SIZE)
        .u32le(MONITOR_SIZE)
        .u32le(1) // NumMonitors
        .u32le(MONITOR_PRIMARY)
        .u32le(0).u32le(0) // Left, Top: the primary monitor's corner is the origin
        .u32le(width).u32le(height)
        // Physical size and both factors state the client's DPI; all four are zero on an unscaled
        // display, which is how the server is told to keep its own (MS-RDPEDISP 2.2.2.2.1).
        .u32le(scale.physicalWidthMm).u32le(scale.physicalHeightMm)
        .u32le(ORIENTATION_LANDSCAPE)
        .u32le(scale.desktopScaleFactor).u32le(scale.deviceScaleFactor)
        .toByteArray()

    companion object {
        /** The dynamic channel the server opens for this protocol. */
        const val NAME = "Microsoft::Windows::RDS::DisplayControl"

        private const val HEADER_SIZE = 8
        private const val CAPS_BODY_SIZE = 12

        private const val PDU_TYPE_MONITOR_LAYOUT = 0x00000002
        private const val PDU_TYPE_CAPS = 0x00000005

        private const val MONITOR_PRIMARY = 0x00000001

        /** The only orientation a session in a window has (MS-RDPEDISP 2.2.2.2.1). */
        private const val ORIENTATION_LANDSCAPE = 0
        private const val MONITOR_SIZE = 40
        private const val PDU_SIZE = HEADER_SIZE + 8 + MONITOR_SIZE

        /** MS-RDPEDISP 2.2.2.2.1: outside this range the layout is refused outright. */
        private const val MIN_SIZE = 200
        private const val MAX_SIZE = 8192

        private const val UNSIGNED = 0xFFFFFFFFL
    }
}
