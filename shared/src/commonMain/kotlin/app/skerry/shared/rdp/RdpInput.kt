package app.skerry.shared.rdp

/** Mouse buttons as the client reports them; the wire flags differ per button family. */
enum class RdpMouseButton { Left, Right, Middle, Extended1, Extended2 }

/** Scroll direction of a wheel event. */
enum class RdpWheelAxis { Vertical, Horizontal }

/**
 * Client→server input, sent as fast-path PDUs (MS-RDPBCGR 2.2.8.1.2). Fast-path is used rather than
 * the slow path for every event: it is a five-byte header instead of about thirty, and on a busy
 * mouse that difference is the difference between a session that tracks the cursor and one that
 * lags behind it.
 *
 * Keyboard events carry PC/AT scancodes (set 1), not characters: RDP replays them into the remote
 * keyboard driver, so the remote layout decides what a key produces. [unicode] exists for the cases
 * that cannot be expressed that way — a character the local layout composes but the remote layout
 * has no key for.
 */
object RdpInput {

    /**
     * Press or release a key by its PC/AT set 1 [scancode]. [extended] marks the E0-prefixed keys;
     * [extended1] the E1 prefix, which on a real keyboard only Pause carries (F-18).
     */
    fun key(scancode: Int, down: Boolean, extended: Boolean = false, extended1: Boolean = false): ByteArray {
        var flags = 0
        if (!down) flags = flags or KBDFLAGS_RELEASE
        if (extended) flags = flags or KBDFLAGS_EXTENDED
        if (extended1) flags = flags or KBDFLAGS_EXTENDED1
        return packet(
            RdpWriter(4)
                .u8((EVENT_SCANCODE shl 5) or flags)
                .u8(scancode and 0xFF)
                .toByteArray(),
        )
    }

    /** Type [code] as a Unicode character, bypassing the remote layout. */
    fun unicode(code: Int, down: Boolean): ByteArray {
        val flags = if (down) 0 else KBDFLAGS_RELEASE
        return packet(
            RdpWriter(4)
                .u8((EVENT_UNICODE shl 5) or flags)
                .u16le(code)
                .toByteArray(),
        )
    }

    /** Move the pointer to ([x], [y]) in desktop coordinates. */
    fun mouseMove(x: Int, y: Int): ByteArray = mouse(PTRFLAGS_MOVE, x, y)

    /** Press or release [button] at ([x], [y]). */
    fun mouseButton(button: RdpMouseButton, down: Boolean, x: Int, y: Int): ByteArray {
        val extended = button == RdpMouseButton.Extended1 || button == RdpMouseButton.Extended2
        val buttonFlag = when (button) {
            RdpMouseButton.Left -> PTRFLAGS_BUTTON1
            RdpMouseButton.Right -> PTRFLAGS_BUTTON2
            RdpMouseButton.Middle -> PTRFLAGS_BUTTON3
            RdpMouseButton.Extended1 -> PTRXFLAGS_BUTTON1
            RdpMouseButton.Extended2 -> PTRXFLAGS_BUTTON2
        }
        val flags = buttonFlag or if (down) PTRFLAGS_DOWN else 0
        return if (extended) extendedMouse(flags, x, y) else mouse(flags, x, y)
    }

    /**
     * Scroll by [clicks] notches ([RdpWheelAxis.Vertical] positive = away from the user).
     *
     * The rotation is a signed 9-bit field in the low bits of the same word as the flags, so the
     * magnitude has to be clamped: a large trackpad delta would otherwise wrap into the flag bits
     * and arrive as a button press.
     */
    fun mouseWheel(clicks: Int, axis: RdpWheelAxis, x: Int, y: Int): ByteArray {
        val rotation = (clicks * WHEEL_STEP).coerceIn(-WHEEL_MAX, WHEEL_MAX)
        var flags = if (axis == RdpWheelAxis.Vertical) PTRFLAGS_WHEEL else PTRFLAGS_HWHEEL
        if (rotation < 0) flags = flags or PTRFLAGS_WHEEL_NEGATIVE
        flags = flags or (rotation and WHEEL_ROTATION_MASK)
        return mouse(flags, x, y)
    }

    /**
     * Tell the server the state of the lock keys. Sent on focus gain: the remote session keeps its
     * own Caps/Num state, and without this it drifts out of step with the local keyboard the moment
     * the user toggles one while the session is in the background.
     */
    fun syncLockKeys(scrollLock: Boolean, numLock: Boolean, capsLock: Boolean, kanaLock: Boolean): ByteArray {
        var flags = 0
        if (scrollLock) flags = flags or SYNC_SCROLL_LOCK
        if (numLock) flags = flags or SYNC_NUM_LOCK
        if (capsLock) flags = flags or SYNC_CAPS_LOCK
        if (kanaLock) flags = flags or SYNC_KANA_LOCK
        return packet(RdpWriter(2).u8((EVENT_SYNC shl 5) or flags).toByteArray())
    }

    private fun mouse(flags: Int, x: Int, y: Int): ByteArray = packet(
        RdpWriter(8)
            .u8(EVENT_MOUSE shl 5)
            .u16le(flags)
            .u16le(x.coerceIn(0, 0xFFFF))
            .u16le(y.coerceIn(0, 0xFFFF))
            .toByteArray(),
    )

    private fun extendedMouse(flags: Int, x: Int, y: Int): ByteArray = packet(
        RdpWriter(8)
            .u8(EVENT_MOUSEX shl 5)
            .u16le(flags)
            .u16le(x.coerceIn(0, 0xFFFF))
            .u16le(y.coerceIn(0, 0xFFFF))
            .toByteArray(),
    )

    /**
     * Wrap one event in a fast-path input PDU. The length field spans the whole packet and uses the
     * two-byte form throughout: input events are small, but the one-byte form only reaches 127 and
     * a single form keeps the header size constant.
     */
    private fun packet(event: ByteArray): ByteArray {
        val total = event.size + 3
        return RdpWriter(total)
            .u8(FASTPATH_INPUT_ACTION or (1 shl 2)) // one event
            .u16be(total or 0x8000)
            .bytes(event)
            .toByteArray()
    }

    private const val FASTPATH_INPUT_ACTION = 0x0

    private const val EVENT_SCANCODE = 0
    private const val EVENT_MOUSE = 1
    private const val EVENT_MOUSEX = 2
    private const val EVENT_SYNC = 3
    private const val EVENT_UNICODE = 4

    private const val KBDFLAGS_RELEASE = 0x01
    private const val KBDFLAGS_EXTENDED = 0x02
    private const val KBDFLAGS_EXTENDED1 = 0x04

    private const val PTRFLAGS_HWHEEL = 0x0400
    private const val PTRFLAGS_WHEEL = 0x0200
    private const val PTRFLAGS_WHEEL_NEGATIVE = 0x0100
    private const val PTRFLAGS_MOVE = 0x0800
    private const val PTRFLAGS_DOWN = 0x8000
    private const val PTRFLAGS_BUTTON1 = 0x1000
    private const val PTRFLAGS_BUTTON2 = 0x2000
    private const val PTRFLAGS_BUTTON3 = 0x4000
    private const val PTRXFLAGS_BUTTON1 = 0x0001
    private const val PTRXFLAGS_BUTTON2 = 0x0002

    private const val WHEEL_ROTATION_MASK = 0x01FF
    private const val WHEEL_STEP = 120
    private const val WHEEL_MAX = 0xFF

    private const val SYNC_SCROLL_LOCK = 0x01
    private const val SYNC_NUM_LOCK = 0x02
    private const val SYNC_CAPS_LOCK = 0x04
    private const val SYNC_KANA_LOCK = 0x08
}

/**
 * Slow-path client PDUs that are not input: acknowledging frames, asking for a repaint, telling the
 * server the window is hidden, and shutting the session down.
 */
object RdpClientPdus {

    /**
     * Acknowledge frame [frameId] of the surface-command stream.
     *
     * This is not optional bookkeeping: having claimed the frame-acknowledge capability, the server
     * stops sending once its allowance of unacknowledged frames is used up — a client that never
     * answers gets a session that freezes after two frames.
     */
    fun frameAcknowledge(shareId: Int, userId: Int, frameId: Int): ByteArray =
        RdpShare.dataPdu(
            shareId,
            userId,
            RdpShare.PDUTYPE2_FRAME_ACKNOWLEDGE,
            RdpWriter(4).u32le(frameId).toByteArray(),
        )

    /** Ask the server to resend [rects] — used after the window was obscured or restored. */
    fun refreshRect(shareId: Int, userId: Int, rects: List<RdpRect>): ByteArray {
        val body = RdpWriter(4 + rects.size * 8)
        body.u8(rects.size.coerceAtMost(255))
        body.zeros(3) // pad3Octets
        for (rect in rects.take(255)) {
            body.u16le(rect.x)
            body.u16le(rect.y)
            body.u16le(rect.x + rect.width - 1)
            body.u16le(rect.y + rect.height - 1)
        }
        return RdpShare.dataPdu(shareId, userId, RdpShare.PDUTYPE2_REFRESH_RECT, body.toByteArray())
    }

    /**
     * Tell the server whether the session is visible. Sending "not visible" when the window is
     * minimised stops the server rendering and streaming a desktop nobody is looking at.
     */
    fun suppressOutput(shareId: Int, userId: Int, visible: Boolean, width: Int, height: Int): ByteArray {
        val body = RdpWriter(12)
        body.u8(if (visible) 1 else 0) // allowDisplayUpdates
        body.zeros(3) // pad3Octets
        if (visible) {
            body.u16le(0).u16le(0).u16le(width - 1).u16le(height - 1)
        }
        return RdpShare.dataPdu(shareId, userId, RdpShare.PDUTYPE2_SUPPRESS_OUTPUT, body.toByteArray())
    }

    /** Ask the server to end the session (the user closing the tab, not the window). */
    fun shutdownRequest(shareId: Int, userId: Int): ByteArray =
        RdpShare.dataPdu(shareId, userId, RdpShare.PDUTYPE2_SHUTDOWN_REQUEST, ByteArray(0))
}
