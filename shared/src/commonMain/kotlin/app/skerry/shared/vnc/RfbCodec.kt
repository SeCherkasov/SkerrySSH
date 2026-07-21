package app.skerry.shared.vnc

/**
 * Pure RFB (VNC) protocol state machine — no sockets, no threads. It pulls bytes through [VncSource]
 * and pushes replies through [VncSink]; both are injected, so the whole codec runs in `commonMain`
 * and tests drive it over in-memory fixtures. The socket wiring lives in `VncTcpTransport`
 * (jvmSharedMain). This mirrors the project's `TelnetCodec` (pure) + `TelnetTransport` (I/O) split.
 *
 * Pull, not push: [VncSource.readFully] blocks until N bytes arrive, so decoders read RFB's nested
 * variable-length structures (Hextile tiles, ZRLE/Tight sub-blocks) as straight-line code instead
 * of a resumable parser that could suspend at any byte boundary.
 *
 * Lifecycle: [handshake] once (returns the desktop name), then [readMessage] in a loop. The read
 * loop mutates [fb] in place and returns a [VncUpdate] describing what changed; the transport emits
 * it and then requests the next incremental update.
 */
class RfbCodec(
    private val source: VncSource,
    private val sink: VncSink,
    private val fb: VncFramebuffer,
    private val inflaterFactory: InflaterFactory,
    private val challengeResponder: VncChallengeResponder,
    private val imageDecoder: VncImageDecoder? = null,
    private val requestedEncodings: IntArray = DEFAULT_ENCODINGS,
) {
    // Current quality/compression preference, advertised as Tight pseudo-encodings in SetEncodings.
    private var quality: VncQuality = VncQuality.Auto

    // Whether we advertise the Cursor pseudo-encoding — i.e. whether the client draws the cursor.
    private var localCursor = true

    // ExtendedDesktopSize state. @Volatile like VncFramebuffer's fields: written by the read loop,
    // read by writeSetDesktopSize on a UI-driven coroutine. The screen id/flags are echoed back in
    // SetDesktopSize so the server recognises its own layout.
    @Volatile
    private var supportsSetDesktopSize = false

    @Volatile
    private var screenId = 0

    @Volatile
    private var screenFlags = 0

    // One persistent ZRLE zlib stream for the whole connection (created on first ZRLE rect).
    private var zrleInflater: Inflater? = null
    // Up to four independent persistent Tight zlib streams, created lazily as control bytes name them.
    private val tightInflaters = arrayOfNulls<Inflater>(4)

    private val u8 = ByteArray(1)
    private val u16 = ByteArray(2)
    private val u32 = ByteArray(4)

    /**
     * Run the RFB 3.x handshake with [auth] and set up the session: negotiate version + security,
     * authenticate, read ServerInit (allocating [fb] to the desktop size), then force our canonical
     * 32bpp pixel format, advertise [requestedEncodings], and request the initial full-screen
     * update. Returns the server's desktop name.
     */
    suspend fun handshake(auth: VncAuth): String {
        val minor = readProtocolVersion()
        val chosenType = negotiateSecurity(auth, minor)
        if (chosenType == SEC_VNC_AUTH) performVncAuth(auth)
        // SecurityResult: always in 3.8; in 3.3/3.7 only after a non-None security type.
        if (minor >= 8 || chosenType != SEC_NONE) readSecurityResult(minor)
        sink.write(byteArrayOf(1)) // ClientInit: shared = 1 (don't kick other viewers)
        val name = readServerInit()
        writeSetPixelFormat()
        writeSetEncodings()
        writeFramebufferUpdateRequest(incremental = false, x = 0, y = 0, w = fb.width, h = fb.height)
        return name
    }

    /**
     * Read server→client messages until one produces updates, apply them to [fb], and return them.
     * A list because ONE FramebufferUpdate can carry several kinds of change at once — a resize, a
     * cursor shape and painted rectangles are all "rectangles" on the wire — and the caller must see
     * every one of them.
     *
     * A loop, not self-recursion: a server may stream many SetColourMap messages (skipped under our
     * forced true-colour), and recursing per message would grow the suspend continuation chain
     * unbounded toward a StackOverflowError.
     */
    suspend fun readMessage(): List<VncUpdate> {
        while (true) {
            when (val type = readU8()) {
                MSG_FRAMEBUFFER_UPDATE -> return readFramebufferUpdate()
                MSG_SET_COLOUR_MAP -> skipSetColourMap() // true-colour forced; consume and continue
                MSG_BELL -> return listOf(VncUpdate.Bell)
                MSG_SERVER_CUT_TEXT -> return listOf(VncUpdate.ClipboardText(readServerCutText()))
                else -> throw VncProtocolException("unknown server message type $type")
            }
        }
    }

    // ---- handshake steps ----

    private suspend fun readProtocolVersion(): Int {
        val banner = readBytes(12)
        val text = banner.decodeToString()
        // "RFB 003.008\n" — parse the minor; clamp our reply to the highest we support (3.8).
        if (!text.startsWith("RFB 003.")) throw VncProtocolException("bad RFB version banner: $text")
        val serverMinor = text.substring(8, 11).toIntOrNull()
            ?: throw VncProtocolException("bad RFB version banner: $text")
        val minor = if (serverMinor >= 8) 8 else serverMinor
        sink.write("RFB 003.00$minor\n".encodeToByteArray())
        return minor
    }

    private suspend fun negotiateSecurity(auth: VncAuth, minor: Int): Int {
        if (minor < 7) {
            // 3.3: the server dictates a single security type as a u32.
            val type = readU32()
            return when (type) {
                SEC_INVALID -> throw VncAuthException(readFailureReason(minor))
                SEC_NONE, SEC_VNC_AUTH -> type
                else -> throw VncAuthException("server requires unsupported security type $type")
            }
        }
        // 3.7+/3.8: server offers a list; we pick.
        val count = readU8()
        if (count == 0) throw VncAuthException(readFailureReason(minor))
        val offered = readBytes(count).map { it.toInt() and 0xFF }.toSet()
        val want = if (auth is VncAuth.Password) SEC_VNC_AUTH else SEC_NONE
        val chosen = when {
            offered.contains(want) -> want
            // Fall back: if we wanted None but only VNC-Auth is offered, still try it (empty password).
            offered.contains(SEC_VNC_AUTH) -> SEC_VNC_AUTH
            offered.contains(SEC_NONE) -> SEC_NONE
            else -> throw VncAuthException("no supported security type offered: $offered")
        }
        sink.write(byteArrayOf(chosen.toByte()))
        return chosen
    }

    private suspend fun performVncAuth(auth: VncAuth) {
        val challenge = readBytes(16)
        val password = (auth as? VncAuth.Password)?.password ?: ""
        val response = challengeResponder.respond(password, challenge)
        if (response.size != 16) throw VncProtocolException("DES response must be 16 bytes, was ${response.size}")
        sink.write(response)
    }

    private suspend fun readSecurityResult(minor: Int) {
        val status = readU32()
        if (status != 0) {
            // 3.8 appends a reason string; 3.7 does not.
            val reason = if (minor >= 8) readFailureReason(minor) else "authentication failed"
            throw VncAuthException(reason)
        }
    }

    private suspend fun readFailureReason(minor: Int): String {
        if (minor < 8) return "connection failed"
        return readBoundedLatin1(MAX_REASON_LEN, "failure reason")
    }

    private suspend fun readServerInit(): String {
        val width = readU16()
        val height = readU16()
        readBytes(16) // server pixel format — ignored; we impose our own next
        val name = readBoundedLatin1(MAX_NAME_LEN, "server name")
        boundedResize(width, height)
        return name
    }

    // ---- framebuffer update ----

    /**
     * Read one FramebufferUpdate. Pseudo-encodings (DesktopSize, Cursor) are metadata carried in the
     * rectangle list, not pixels: they get their own update and never enter [VncUpdate.Region], whose
     * rects the UI blits straight out of [fb].
     *
     * The Region always comes last and is always present, even when empty: pseudo-encodings are
     * applied to [fb] first (a Resize must reach the UI before the rects written after it), and the
     * transport keys its "request the next update" off a Region, so every FramebufferUpdate must
     * carry exactly one — otherwise a cursor-only message would stall the session.
     */
    private suspend fun readFramebufferUpdate(): List<VncUpdate> {
        readU8() // padding
        val rectCount = readU16()
        val rects = ArrayList<VncRect>(rectCount)
        val pseudo = ArrayList<VncUpdate>()
        repeat(rectCount) {
            val x = readU16()
            val y = readU16()
            val w = readU16()
            val h = readU16()
            when (val encoding = readS32()) {
                ENC_DESKTOP_SIZE -> {
                    boundedResize(w, h)
                    pseudo += VncUpdate.Resize(w, h)
                }
                ENC_EXTENDED_DESKTOP_SIZE -> readExtendedDesktopSize(reason = x, status = y, width = w, height = h, out = pseudo)
                ENC_CURSOR -> pseudo += readCursor(hotspotX = x, hotspotY = y, width = w, height = h)
                else -> {
                    decodeRectangle(encoding, VncRect(x, y, w, h))
                    rects += VncRect(x, y, w, h)
                }
            }
        }
        return pseudo + VncUpdate.Region(rects)
    }

    /**
     * ExtendedDesktopSize pseudo-encoding (-308). The rect header's x/y carry the reason for the
     * change and, for our own SetDesktopSize requests, the status; w/h is the framebuffer size. The
     * payload is the screen layout, whose first screen we remember to echo back in SetDesktopSize.
     *
     * Appends to [out]: [VncUpdate.SetDesktopSizeSupported] the first time (receiving this rect at
     * all is how support is signalled) and a [VncUpdate.Resize] only when the size actually changed —
     * resizing to the same size would reallocate and blank a perfectly good picture.
     */
    private suspend fun readExtendedDesktopSize(reason: Int, status: Int, width: Int, height: Int, out: MutableList<VncUpdate>) {
        val screenCount = readU8()
        readBytes(3) // padding
        repeat(screenCount) { i ->
            val id = readS32()
            readU16(); readU16(); readU16(); readU16() // screen x, y, w, h — single-screen client, ignored
            val flags = readS32()
            if (i == 0) {
                screenId = id
                screenFlags = flags
            }
        }
        if (!supportsSetDesktopSize) {
            supportsSetDesktopSize = true
            out += VncUpdate.SetDesktopSizeSupported
        }
        // reason 1 = the server answering OUR SetDesktopSize; a non-zero status there is a refusal,
        // and the w/h that come with it must not be applied (a buggy server could still change them).
        if (reason == REASON_CLIENT_REQUEST && status != 0) return
        if (width != fb.width || height != fb.height) {
            boundedResize(width, height)
            out += VncUpdate.Resize(width, height)
        }
    }

    /**
     * Cursor pseudo-encoding (-239): [width]×[height] pixels in our canonical format, then a 1-bit
     * transparency mask of `ceil(width/8)` bytes per row, MSB = leftmost pixel, 1 = opaque.
     */
    private suspend fun readCursor(hotspotX: Int, hotspotY: Int, width: Int, height: Int): VncUpdate.CursorShape {
        // A cursor is a sprite, not a screen — bound it far below MAX_DIMENSION before deriving any
        // buffer size from it, so a hostile server can't bill us a multi-GB "cursor" for four bytes.
        if (width > MAX_CURSOR_DIMENSION || height > MAX_CURSOR_DIMENSION) {
            throw VncProtocolException("cursor ${width}x$height exceeds max $MAX_CURSOR_DIMENSION")
        }
        if (width == 0 || height == 0) return VncUpdate.CursorShape(EMPTY_ARGB, 0, 0, 0, 0)
        val pixels = readBytes(width * height * CanonicalPixelFormat.PIXEL_BYTES)
        val maskStride = (width + 7) / 8
        val mask = readBytes(maskStride * height)
        val argb = IntArray(width * height)
        var i = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val opaque = (mask[y * maskStride + x / 8].toInt() shr (7 - (x % 8))) and 1 == 1
                // Masked-out pixels are zeroed whole, not just their alpha: the desktop bitmap hands
                // these bytes to Skia as premultiplied, where alpha 0 over non-zero RGB is invalid
                // and renders as a halo around the sprite.
                argb[i] = if (opaque) CanonicalPixelFormat.argbFromPixel(pixels, i * CanonicalPixelFormat.PIXEL_BYTES) else 0
                i++
                x++
            }
            y++
        }
        return VncUpdate.CursorShape(argb, width, height, hotspotX, hotspotY)
    }

    /**
     * Decode one rectangle of the given [encoding] into [fb]. Encoding decoders live in
     * RfbEncodings.kt; the codec advertises only encodings it can decode ([requestedEncodings]), so
     * a server should never send an unadvertised one.
     */
    private suspend fun decodeRectangle(encoding: Int, rect: VncRect) {
        // Bound rect dimensions before any decoder derives a buffer size from them: a rect can't
        // legitimately exceed the (already-bounded) framebuffer, and unbounded w/h let w*h*bpp
        // overflow Int and mis-size decode buffers (→ OOB reads on crafted input).
        if (rect.width > MAX_DIMENSION || rect.height > MAX_DIMENSION) {
            throw VncProtocolException("rectangle ${rect.width}x${rect.height} exceeds max $MAX_DIMENSION")
        }
        when (encoding) {
            ENC_RAW -> decodeRaw(source, fb, rect)
            ENC_COPY_RECT -> decodeCopyRect(source, fb, rect)
            ENC_HEXTILE -> decodeHextile(source, fb, rect)
            ENC_ZRLE -> decodeZrle(source, fb, rect, zrleStream())
            ENC_TIGHT -> decodeTight(source, fb, rect, ::tightStream, ::resetTight, imageDecoder)
            else -> throw VncProtocolException("unsupported encoding $encoding for rect $rect")
        }
    }

    /** The connection's single persistent ZRLE zlib stream, created on first use. */
    private fun zrleStream(): Inflater =
        zrleInflater ?: inflaterFactory.create().also { zrleInflater = it }

    /** One of Tight's four persistent zlib streams (0..3), created on first use. */
    private fun tightStream(id: Int): Inflater =
        tightInflaters[id] ?: inflaterFactory.create().also { tightInflaters[id] = it }

    /** Reset a Tight zlib stream (control-byte reset bit): drop it so the next use starts fresh. */
    private fun resetTight(id: Int) {
        tightInflaters[id]?.close()
        tightInflaters[id] = null
    }

    /**
     * Release the persistent zlib streams (native state on the JVM — GC finalization is not
     * reliable for it). The transport calls this on session teardown; idempotent.
     */
    fun close() {
        zrleInflater?.close()
        zrleInflater = null
        for (i in tightInflaters.indices) {
            tightInflaters[i]?.close()
            tightInflaters[i] = null
        }
    }

    private suspend fun skipSetColourMap() {
        readU8() // padding
        readU16() // first colour
        val count = readU16()
        readBytes(count * 6) // r,g,b as u16 each
    }

    private suspend fun readServerCutText(): String {
        readBytes(3) // padding
        return readBoundedLatin1(MAX_CLIPBOARD_LEN, "server cut text")
    }

    // ---- client → server messages ----

    private suspend fun writeSetPixelFormat() {
        val msg = ByteArray(4) + CanonicalPixelFormat.toBytes()
        msg[0] = 0 // SetPixelFormat
        sink.write(msg)
    }

    private suspend fun writeSetEncodings() {
        // Base encodings + Cursor (while we draw it) + Tight quality/compression pseudo-encodings.
        val cursor = if (localCursor) intArrayOf(ENC_CURSOR) else IntArray(0)
        val all = requestedEncodings + cursor + qualityPseudoEncodings(quality)
        val n = all.size
        val msg = ByteArray(4 + n * 4)
        msg[0] = 2 // SetEncodings
        putU16(msg, 2, n)
        var off = 4
        for (enc in all) {
            putS32(msg, off, enc)
            off += 4
        }
        sink.write(msg)
    }

    /** Change the quality preference and re-advertise encodings (server applies it on the next update). */
    suspend fun setQuality(newQuality: VncQuality) {
        quality = newQuality
        writeSetEncodings()
    }

    /**
     * Advertise (or drop) the Cursor pseudo-encoding — see [VncSession.setLocalCursor]. Re-advertising
     * only changes what the server sends NEXT; the caller asks for a full update so the framebuffer
     * stops (or starts) carrying a baked-in cursor right away.
     */
    suspend fun setLocalCursor(enabled: Boolean) {
        localCursor = enabled
        writeSetEncodings()
    }

    /**
     * SetDesktopSize (message 251): ask the server to resize the desktop to [width]×[height] as a
     * single screen. A no-op until an ExtendedDesktopSize rect has arrived — the spec forbids the
     * message before the server has shown it understands the extension. Dimensions are clamped to
     * the same bound we accept from the server.
     */
    suspend fun writeSetDesktopSize(width: Int, height: Int) {
        if (!supportsSetDesktopSize) return
        val w = width.coerceIn(1, MAX_DIMENSION)
        val h = height.coerceIn(1, MAX_DIMENSION)
        val msg = ByteArray(24)
        msg[0] = 251.toByte()
        putU16(msg, 2, w)
        putU16(msg, 4, h)
        msg[6] = 1 // one screen
        putS32(msg, 8, screenId)
        putU16(msg, 12, 0); putU16(msg, 14, 0) // screen at the origin…
        putU16(msg, 16, w); putU16(msg, 18, h) // …covering the whole desktop
        putS32(msg, 20, screenFlags)
        sink.write(msg)
    }

    suspend fun writeFramebufferUpdateRequest(incremental: Boolean, x: Int, y: Int, w: Int, h: Int) {
        val msg = ByteArray(10)
        msg[0] = 3 // FramebufferUpdateRequest
        msg[1] = if (incremental) 1 else 0
        putU16(msg, 2, x); putU16(msg, 4, y); putU16(msg, 6, w); putU16(msg, 8, h)
        sink.write(msg)
    }

    suspend fun writePointer(event: VncPointerEvent) {
        val msg = ByteArray(6)
        msg[0] = 5 // PointerEvent
        msg[1] = event.buttonMask.toByte()
        putU16(msg, 2, event.x); putU16(msg, 4, event.y)
        sink.write(msg)
    }

    suspend fun writeKey(keySym: Long, down: Boolean) {
        val msg = ByteArray(8)
        msg[0] = 4 // KeyEvent
        msg[1] = if (down) 1 else 0
        putU32(msg, 4, keySym)
        sink.write(msg)
    }

    suspend fun writeClientCutText(text: String) {
        val body = text.encodeToByteArray()
        val msg = ByteArray(8 + body.size)
        msg[0] = 6 // ClientCutText
        putU32(msg, 4, body.size.toLong())
        body.copyInto(msg, 8)
        sink.write(msg)
    }

    // ---- low-level reads ----

    private suspend fun readU8(): Int {
        source.readFully(u8, 0, 1)
        return u8[0].toInt() and 0xFF
    }

    private suspend fun readU16(): Int {
        source.readFully(u16, 0, 2)
        return ((u16[0].toInt() and 0xFF) shl 8) or (u16[1].toInt() and 0xFF)
    }

    private suspend fun readS32(): Int {
        source.readFully(u32, 0, 4)
        return ((u32[0].toInt() and 0xFF) shl 24) or ((u32[1].toInt() and 0xFF) shl 16) or
            ((u32[2].toInt() and 0xFF) shl 8) or (u32[3].toInt() and 0xFF)
    }

    // u32 length fields fit in Int for any real framebuffer/message; guard against a negative (huge) value.
    private suspend fun readU32(): Int {
        val v = readS32()
        if (v < 0) throw VncProtocolException("oversized length field ${v.toLong() and 0xFFFFFFFFL}")
        return v
    }

    private suspend fun readBytes(n: Int): ByteArray {
        if (n == 0) return EMPTY
        val buf = ByteArray(n)
        source.readFully(buf, 0, n)
        return buf
    }

    /**
     * Read a wire-length-prefixed string, capping the length at [max] before allocating so a
     * malicious server can't force a multi-GB allocation with a few header bytes. Decoded as Latin-1
     * (RFB's historical charset): each byte maps 1:1 to a code point, so non-ASCII names/clipboard
     * text stay intact instead of being mangled as UTF-8.
     */
    private suspend fun readBoundedLatin1(max: Int, what: String): String {
        val len = readU32()
        if (len > max) throw VncProtocolException("$what length $len exceeds max $max")
        val bytes = readBytes(len)
        return buildString(bytes.size) { for (b in bytes) append((b.toInt() and 0xFF).toChar()) }
    }

    /** Resize the framebuffer after bounding both dimensions (guards Int overflow in `width*height`). */
    private fun boundedResize(width: Int, height: Int) {
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw VncProtocolException("framebuffer ${width}x$height exceeds max $MAX_DIMENSION")
        }
        fb.resize(width, height)
    }

    companion object {
        // Security types.
        const val SEC_INVALID = 0
        const val SEC_NONE = 1
        const val SEC_VNC_AUTH = 2

        // Server→client message types.
        const val MSG_FRAMEBUFFER_UPDATE = 0
        const val MSG_SET_COLOUR_MAP = 1
        const val MSG_BELL = 2
        const val MSG_SERVER_CUT_TEXT = 3

        // Caps on server-supplied sizes, applied before allocation to bound memory use against a
        // hostile/buggy server (the socket is plaintext and fully untrusted). Generous vs. any real
        // desktop, tight vs. a 2 GB OOM: max framebuffer/rect side (also keeps width*height in Int),
        // desktop-name bytes, and clipboard (ServerCutText) bytes.
        const val MAX_DIMENSION = 16_384
        const val MAX_NAME_LEN = 64 * 1024
        const val MAX_REASON_LEN = 64 * 1024
        const val MAX_CLIPBOARD_LEN = 4 * 1024 * 1024

        /** Cursor sprites are ~32-96px in practice; 1024 leaves room for hi-dpi and still caps pixels+mask at ~4.3 MB. */
        const val MAX_CURSOR_DIMENSION = 1024

        // Encodings.
        const val ENC_RAW = 0
        const val ENC_COPY_RECT = 1
        const val ENC_HEXTILE = 5
        const val ENC_TIGHT = 7
        const val ENC_ZRLE = 16
        const val ENC_CURSOR = -239
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_EXTENDED_DESKTOP_SIZE = -308

        /** ExtendedDesktopSize rect header x: 1 = the change answers this client's SetDesktopSize. */
        const val REASON_CLIENT_REQUEST = 1

        /**
         * Encodings advertised to the server, most-preferred first — the client only ever offers what
         * it can decode (the server picks from this list). [ENC_CURSOR] is NOT here: it's toggled per
         * session by [setLocalCursor], not a fixed preference.
         */
        val DEFAULT_ENCODINGS = intArrayOf(
            ENC_TIGHT, ENC_ZRLE, ENC_HEXTILE, ENC_COPY_RECT, ENC_RAW,
            ENC_EXTENDED_DESKTOP_SIZE, ENC_DESKTOP_SIZE,
        )

        /**
         * Tight quality/compression pseudo-encodings for a [VncQuality]. JPEG quality level N is
         * encoding -32+N (0..9); compression level N is -256+N. [VncQuality.Auto] advertises none
         * (server default). Higher quality → higher JPEG level + lower compression.
         */
        fun qualityPseudoEncodings(quality: VncQuality): IntArray = when (quality) {
            VncQuality.Auto -> intArrayOf()
            VncQuality.Low -> intArrayOf(-32 + 2, -256 + 9)   // quality 2, compression 9
            VncQuality.Medium -> intArrayOf(-32 + 6, -256 + 6) // quality 6, compression 6
            VncQuality.High -> intArrayOf(-32 + 9, -256 + 3)   // quality 9, compression 3
        }

        private val EMPTY = ByteArray(0)
        private val EMPTY_ARGB = IntArray(0)

        private fun putU16(dst: ByteArray, off: Int, v: Int) {
            dst[off] = (v ushr 8).toByte()
            dst[off + 1] = v.toByte()
        }

        private fun putS32(dst: ByteArray, off: Int, v: Int) {
            dst[off] = (v ushr 24).toByte()
            dst[off + 1] = (v ushr 16).toByte()
            dst[off + 2] = (v ushr 8).toByte()
            dst[off + 3] = v.toByte()
        }

        private fun putU32(dst: ByteArray, off: Int, v: Long) {
            dst[off] = (v ushr 24).toByte()
            dst[off + 1] = (v ushr 16).toByte()
            dst[off + 2] = (v ushr 8).toByte()
            dst[off + 3] = v.toByte()
        }
    }
}
