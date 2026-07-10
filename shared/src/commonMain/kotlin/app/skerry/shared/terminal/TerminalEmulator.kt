package app.skerry.shared.terminal

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Cell color. UI-independent (not Compose Color) — the renderer maps it into the theme palette.
 *  - [Default] — theme default;
 *  - [Indexed] — xterm palette index: 0..7 base ANSI, 8..15 bright, 16..255 cube/grayscale;
 *  - [Rgb] — truecolor (24-bit).
 */
sealed interface TermColor {
    data object Default : TermColor
    data class Indexed(val index: Int) : TermColor
    data class Rgb(val r: Int, val g: Int, val b: Int) : TermColor

    companion object {
        // Named aliases for test/code readability — plain xterm palette indices.
        val Black = Indexed(0)
        val Red = Indexed(1)
        val Green = Indexed(2)
        val Yellow = Indexed(3)
        val Blue = Indexed(4)
        val Magenta = Indexed(5)
        val Cyan = Indexed(6)
        val White = Indexed(7)
        val BrightBlack = Indexed(8)
        val BrightRed = Indexed(9)
        val BrightGreen = Indexed(10)
        val BrightYellow = Indexed(11)
        val BrightBlue = Indexed(12)
        val BrightMagenta = Indexed(13)
        val BrightCyan = Indexed(14)
        val BrightWhite = Indexed(15)
    }
}

/**
 * Underline style (modern SGR `4:x`): besides classic single — double, curly, dotted, dashed.
 * [None] — no underline. The renderer draws the line shape itself.
 */
enum class UnderlineStyle { None, Single, Double, Curly, Dotted, Dashed }

/**
 * Cell attributes. Colors are [TermColor]; flags cover the full SGR attribute set.
 *
 * Underline is [underlineStyle] (shape) + [underlineColor] (color, `SGR 58/59`; [TermColor.Default]
 * means "use the text color"). The boolean [underline] is a derived convenience for renderer/tests
 * that only need to know whether underline is present at all.
 */
data class TermStyle(
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underlineStyle: UnderlineStyle = UnderlineStyle.None,
    val underlineColor: TermColor = TermColor.Default,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
    val strikethrough: Boolean = false,
) {
    /** Whether underline is present in any form. */
    val underline: Boolean get() = underlineStyle != UnderlineStyle.None
}

/**
 * Cell width in the grid (for double-width CJK/emoji):
 *  - [Single] — ordinary one-column cell;
 *  - [Wide] — leading cell of a two-column character (the renderer stretches the glyph over 2 columns);
 *  - [Continuation] — second column under a wide character; no glyph drawn, cursor skips over it.
 */
enum class CellWidth { Single, Wide, Continuation }

/**
 * One screen cell: displayed text + style + width. [text] is a String (not `Char`) to hold
 * astral characters (emoji > U+FFFF as a surrogate pair) and combining sequences when needed;
 * a [Continuation] cell's [text] is empty.
 */
data class TermCell(
    val text: String = " ",
    val style: TermStyle = TermStyle(),
    val width: CellWidth = CellWidth.Single,
    /** Hyperlink URI (OSC 8) for this cell, or `null`. Clickability/highlight is up to the renderer. */
    val hyperlink: String? = null,
) {
    /** Convenience constructor from a single BMP character (blank, ASCII, line-drawing glyph). */
    constructor(char: Char, style: TermStyle = TermStyle()) : this(char.toString(), style, CellWidth.Single)
}

/**
 * One grid row: cells + the [wrapped] flag. `wrapped == true` means a soft line break — auto-wrap
 * (DECAWM) cut the line and it logically continues on the next row; an honest `\n` leaves it `false`.
 * Reflow on resize joins adjacent wrapped rows into one logical line and re-splits it at the new
 * width. Delegates to [MutableList], so all grid code treats a row as a list of cells without
 * knowing about the flag.
 */
class TermRow(
    private val cells: MutableList<TermCell>,
    var wrapped: Boolean = false,
) : MutableList<TermCell> by cells

/** Mouse reporting mode to the application (DEC private modes). Encoding is chosen by [TerminalEmulator.mouseSgr]. */
enum class MouseTracking { Off, X10, Normal, ButtonEvent, AnyEvent }

/** Cursor shape (DECSCUSR `CSI Ps SP q`): block, underline, or vertical bar. */
enum class CursorShape { Block, Underline, Bar }

/** Default scrollback depth (rows) — fallback used when the setting isn't provided. */
const val DEFAULT_MAX_SCROLLBACK = 5000

/**
 * Full VT/ANSI emulator over a fixed `rows × cols` grid — device-independent logic (no Compose).
 * The UI renders [lines] (scrollback + screen) and a block cursor at [cursorRow]/[cursorCol]
 * (absolute indices into [lines]).
 *
 * The parser is a byte state machine holding state across [feed] calls, so it correctly survives
 * escape sequences and multi-byte UTF-8 split across chunks.
 *
 * Covered: printing with auto-wrap (DECAWM) and pending-wrap; CR/LF/BS/HT/BEL; IND/NEL/RI/HTS/RIS;
 * DECSC/DECRC; cursor addressing (CUU/CUD/CUF/CUB/CNL/CPL/CHA/CUP/VPA/HPA/CHT/CBT); erase
 * (EL/ED; `ED 2`/`ED 3` move the screen to scrollback, history is kept — clear/Ctrl+L rely on this);
 * insert/delete (ICH/DCH/ECH/IL/DL/SU/SD);
 * scroll region (DECSTBM); insert mode (IRM); full SGR (attributes + 16/256/truecolor);
 * private modes (DECCKM/DECOM/DECAWM/?25/alt-screen 47/1047/1049/mouse 1000-1006/bracketed
 * paste 2004); tab stops; DSR/DA replies and window title (OSC 0/1/2). Unknown sequences are
 * safely absorbed.
 *
 * NOT thread-safe: [feed] and state reads run on the same output-collecting coroutine
 * (as used by `TerminalScreenState`).
 *
 * @param respond called with terminal replies (DSR/DA) — the UI sends them back to the PTY.
 * @param onBell called on BEL (0x07).
 * @param onClipboardCopy called on an OSC 52 write (decoded text) — the UI puts it on the system
 *   clipboard. A clipboard READ request (OSC 52 with `?`) is deliberately ignored: the user's
 *   clipboard contents are not handed to an untrusted server.
 */
class TerminalEmulator(
    cols: Int = 80,
    rows: Int = 24,
    maxScrollback: Int = DEFAULT_MAX_SCROLLBACK,
    // Default cursor shape/blink (the "cursor style" user setting). In effect until the application
    // sets its own via DECSCUSR; RIS (full reset) returns to these, not to a hardcoded xterm default.
    // Defaults (block + blinking) preserve prior behavior.
    private val initialCursorShape: CursorShape = CursorShape.Block,
    private val initialCursorBlink: Boolean = true,
    private val respond: (String) -> Unit = {},
    private val onBell: () -> Unit = {},
    private val onClipboardCopy: (String) -> Unit = {},
) {
    var cols: Int = cols.coerceAtLeast(1)
        private set
    var rows: Int = rows.coerceAtLeast(1)
        private set

    private var primaryGrid: MutableList<TermRow> = freshScreen()
    private var altGrid: MutableList<TermRow>? = null
    private var grid: MutableList<TermRow> = primaryGrid
    private val scrollback = ArrayDeque<TermRow>()

    // Immutable copies of scrollback rows, kept in lockstep with [scrollback]. A row is frozen once
    // it leaves the grid, so it's copied exactly once on entry — [lines] then reuses these instead
    // of deep-copying the whole history per snapshot (O(history×cols) allocations per PTY chunk,
    // which froze fast output on deep scrollback).
    private val scrollbackView = ArrayDeque<List<TermCell>>()

    // Scrollback depth (the "scrollback buffer" setting). Not a val: can be changed on the fly on
    // an already-open session via [applyMaxScrollback] — shrinking trims excess old rows
    // immediately, growing just lets new rows stick around longer.
    private var maxScrollback: Int = maxScrollback.coerceAtLeast(0)

    /** true when the alternate buffer (full-screen TUIs) is active. It has no scrollback. */
    var altScreen: Boolean = false
        private set

    // Cursor relative to the current screen (0-based).
    private var cx = 0
    private var cy = 0
    private var pendingWrap = false

    // Last printed codepoint — for REP (CSI Ps b): nano 9.0/ncurses fill bars (reverse title bar)
    // with it instead of literal spaces. null before the first print. Stored as Int (codepoint),
    // not Char, to correctly repeat astral characters too.
    private var lastPrintedCp: Int? = null

    /** Absolute row index of the cursor in [lines] (accounting for scrollback on the main buffer). */
    val cursorRow: Int get() = (if (altScreen) 0 else scrollback.size) + cy

    /** Cursor column (0-based). */
    val cursorCol: Int get() = cx

    /**
     * Snapshot for rendering: scrollback (main buffer only) + current screen rows. Each row is
     * copied into an immutable list — the snapshot safely survives subsequent [feed] calls (the
     * grid's internal rows are live and mutated in place).
     */
    val lines: List<List<TermCell>>
        get() = ArrayList<List<TermCell>>((if (altScreen) 0 else scrollbackView.size) + grid.size).apply {
            if (!altScreen) addAll(scrollbackView)
            grid.forEach { add(it.toList()) }
        }

    private var style = TermStyle()

    // Scroll region (DECSTBM), 0-based inclusive.
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    // Saved cursor (DECSC/DECRC; also used by alt-screen 1049). Per VT220, DECSC also saves the
    // active graphic set — otherwise text after DECRC would misrender as line-drawing.
    private var savedCx = 0
    private var savedCy = 0
    private var savedStyle = TermStyle()
    private var savedG0LineDrawing = false
    private var savedG1LineDrawing = false
    private var savedGlG1 = false

    private var tabStops = defaultTabStops(this.cols)

    // Modes.
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false

    var cursorVisible: Boolean = true
        private set

    // User default cursor (the "cursor style" setting): RIS returns to this, and it can also be
    // changed on the fly on an open session via [applyCursorDefault]. Separate from the current
    // [cursorShape]/[cursorBlink], which the application can override with its own DECSCUSR.
    private var defaultCursorShape: CursorShape = initialCursorShape
    private var defaultCursorBlink: Boolean = initialCursorBlink

    /** Cursor shape (DECSCUSR). Starts from [initialCursorShape] (default: block, like xterm). */
    var cursorShape: CursorShape = initialCursorShape
        private set

    /** Whether the cursor blinks (DECSCUSR steady/blink). Starts from [initialCursorBlink] (default: blinking). */
    var cursorBlink: Boolean = initialCursorBlink
        private set

    /**
     * Changes the user default cursor on the fly (the setting changed on an already-open session):
     * applies immediately to the current cursor and to the value RIS returns to. The application
     * can still override the shape with its own DECSCUSR later. Call from the emulator's owner coroutine.
     */
    fun applyCursorDefault(shape: CursorShape, blink: Boolean) {
        defaultCursorShape = shape
        defaultCursorBlink = blink
        cursorShape = shape
        cursorBlink = blink
    }

    /**
     * Changes scrollback depth on the fly (the "scrollback buffer" setting changed on an
     * already-open session). Shrinking trims excess old rows from the start of history
     * immediately. Call from the emulator's owner coroutine.
     */
    fun applyMaxScrollback(lines: Int) {
        maxScrollback = lines.coerceAtLeast(0)
        while (scrollback.size > maxScrollback) {
            scrollback.removeFirst()
            scrollbackView.removeFirst()
        }
    }

    var applicationCursorKeys: Boolean = false
        private set
    var applicationKeypad: Boolean = false
        private set
    var bracketedPaste: Boolean = false
        private set
    var mouseTracking: MouseTracking = MouseTracking.Off
        private set
    var mouseSgr: Boolean = false
        private set
    var mousePixels: Boolean = false
        private set

    /** Focus reporting (DEC 1004): when enabled, the UI sends ESC[I on window focus and ESC[O on blur. */
    var focusReporting: Boolean = false
        private set
    var title: String = ""
        private set

    // Window title stack (XTWINOPS CSI 22/23 t): vim/tmux save the title on entry and restore it
    // on exit. Capped against an untrusted server that could push without ever popping.
    private val titleStack = ArrayDeque<String>()

    // Active OSC 8 hyperlink (URI) — attached to printed cells until closed by an empty URI.
    private var currentHyperlink: String? = null

    // Palette overrides (OSC 4): index 0..255 → Rgb. Empty means theme defaults are used.
    // The renderer consults this layer when resolving TermColor.Indexed.
    private val paletteOverrides = HashMap<Int, TermColor.Rgb>()

    // Cache of the immutable palette snapshot: rebuilt only on mutation (paletteDirty), so
    // publishSnapshot() returns the SAME reference across frames — otherwise Compose's
    // referential-equality check would see the state as "dirty" and a HashMap would be allocated
    // on every feed.
    private var paletteCache: Map<Int, TermColor.Rgb> = emptyMap()
    private var paletteDirty = false

    /** Snapshot of palette overrides (OSC 4) for the renderer; empty until the application sets any. */
    fun paletteSnapshot(): Map<Int, TermColor.Rgb> {
        if (paletteDirty) {
            paletteCache = if (paletteOverrides.isEmpty()) emptyMap() else HashMap(paletteOverrides)
            paletteDirty = false
        }
        return paletteCache
    }

    // --- Parser --------------------------------------------------------------

    private enum class State { Ground, Esc, Csi, Osc, OscEsc, Consume, Utf8, StrSeq, StrSeqEsc }

    private var parser = State.Ground
    private val params = StringBuilder()

    // CSI intermediate byte (0x20..0x2f). NUL = "none seen": distinguishes it from a REAL space
    // intermediate (0x20) in DECSCUSR (`CSI Ps SP q`) — otherwise cursor-shape would be indistinguishable
    // from a plain CSI.
    private var csiIntermediate = NO_INTERMEDIATE
    private val osc = StringBuilder()

    // Body of a string sequence (DCS/APC/PM/SOS) — accumulated until ST (ESC\) or BEL. [strSeqIsDcs]
    // distinguishes DCS (parsed: XTGETTCAP) from APC/PM/SOS (absorbed whole: kitty graphics, etc).
    private val strSeq = StringBuilder()
    private var strSeqIsDcs = false

    private var utf8Remaining = 0
    private var utf8CodePoint = 0

    // G0/G1 graphic sets (DEC). true = DEC Special Graphics (line-drawing), false = US-ASCII.
    // glG1 — which set is currently in GL: false=G0, true=G1; toggled by SO (0x0e) / SI (0x0f).
    private var g0LineDrawing = false
    private var g1LineDrawing = false
    private var glG1 = false
    // Where the next designation byte applies: 0=G0 (`ESC (`), 1=G1 (`ESC )`), -1=other (absorb).
    private var pendingDesignation = -1

    fun feed(data: ByteArray) {
        for (b in data) process(b.toInt() and 0xff)
    }

    private fun process(b: Int) {
        when (parser) {
            State.Ground -> ground(b)
            State.Utf8 -> utf8(b)
            State.Esc -> esc(b)
            State.Csi -> csi(b)
            State.Osc -> oscByte(b)
            State.OscEsc -> oscEsc(b)
            State.Consume -> consumeDesignation(b)
            State.StrSeq -> strSeqByte(b)
            State.StrSeqEsc -> strSeqEsc(b)
        }
    }

    private fun ground(b: Int) {
        when {
            b == 0x1b -> parser = State.Esc
            b == 0x0d -> { cx = 0; pendingWrap = false }
            b == 0x0a || b == 0x0b || b == 0x0c -> { lineFeed(); pendingWrap = false }
            b == 0x08 -> { if (cx > 0) cx--; pendingWrap = false }
            b == 0x09 -> { cx = nextTabStop(cx); pendingWrap = false }
            b == 0x07 -> onBell()
            b == 0x0e -> glG1 = true  // SO — activate G1 in GL
            b == 0x0f -> glG1 = false // SI — restore G0 in GL
            b < 0x20 -> {} // other C0 — ignored
            b < 0x80 -> putCodePoint(mapGlyph(b).code)
            else -> beginUtf8(b)
        }
    }

    /** Applies the active graphic set: in DEC Special Graphics, ASCII 0x60..0x7e maps to box-drawing. */
    private fun mapGlyph(b: Int): Char {
        val lineDrawing = if (glG1) g1LineDrawing else g0LineDrawing
        return if (lineDrawing && b in 0x60..0x7e) DEC_SPECIAL_GRAPHICS[b - 0x60] else b.toChar()
    }

    /** Handles the byte after `ESC (`/`ESC )`/others: `0` = line-drawing, else US-ASCII. */
    private fun consumeDesignation(b: Int) {
        val lineDrawing = b == '0'.code
        when (pendingDesignation) {
            0 -> g0LineDrawing = lineDrawing
            1 -> g1LineDrawing = lineDrawing
        }
        pendingDesignation = -1
        parser = State.Ground
    }

    private fun beginUtf8(b: Int) {
        val (len, init) = when {
            b and 0xE0 == 0xC0 -> 2 to (b and 0x1F)
            b and 0xF0 == 0xE0 -> 3 to (b and 0x0F)
            b and 0xF8 == 0xF0 -> 4 to (b and 0x07)
            else -> { putCodePoint(0xFFFD); return }
        }
        utf8Remaining = len - 1
        utf8CodePoint = init
        parser = State.Utf8
    }

    private fun utf8(b: Int) {
        if (b and 0xC0 != 0x80) {
            putCodePoint(0xFFFD)
            parser = State.Ground
            process(b)
            return
        }
        utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
        if (--utf8Remaining == 0) {
            parser = State.Ground
            putCodePoint(utf8CodePoint)
        }
    }

    private fun esc(b: Int) {
        when (b.toChar()) {
            '[' -> { params.clear(); csiIntermediate = NO_INTERMEDIATE; parser = State.Csi }
            ']' -> { osc.clear(); parser = State.Osc }
            '(' -> { pendingDesignation = 0; parser = State.Consume } // designate G0
            ')' -> { pendingDesignation = 1; parser = State.Consume } // designate G1
            '*', '+', '-', '.', '/', '#', ' ' -> { pendingDesignation = -1; parser = State.Consume }
            '7' -> { saveCursor(); parser = State.Ground }
            '8' -> { restoreCursor(); parser = State.Ground }
            'D' -> { lineFeed(); pendingWrap = false; parser = State.Ground } // IND
            'E' -> { lineFeed(); cx = 0; pendingWrap = false; parser = State.Ground } // NEL
            'M' -> { reverseIndex(); pendingWrap = false; parser = State.Ground } // RI
            'H' -> { tabStops[cx] = true; parser = State.Ground } // HTS
            'c' -> { reset(); parser = State.Ground } // RIS
            '=' -> { applicationKeypad = true; parser = State.Ground } // DECKPAM
            '>' -> { applicationKeypad = false; parser = State.Ground } // DECKPNM
            'P' -> { strSeq.clear(); strSeqIsDcs = true; parser = State.StrSeq }       // DCS (sixel/DECRQSS/XTGETTCAP)
            'X', '^', '_' -> { strSeq.clear(); strSeqIsDcs = false; parser = State.StrSeq } // SOS/PM/APC (kitty graphics)
            else -> parser = State.Ground
        }
    }

    private fun csi(b: Int) {
        when {
            // Length cap: digits/separators beyond the limit are dropped, but parsing continues to the
            // final byte (so the tail doesn't leak into Ground as text). Real CSIs are dozens of bytes,
            // so truncation is harmless.
            b in 0x30..0x3f -> if (params.length < MAX_CSI_PARAMS_LEN) params.append(b.toChar()) // digits, ';', ':', markers ?<=>
            b in 0x20..0x2f -> csiIntermediate = b.toChar() // intermediate bytes
            b in 0x40..0x7e -> { dispatchCsi(b.toChar()); parser = State.Ground }
            else -> parser = State.Ground
        }
    }

    private fun oscByte(b: Int) {
        when (b) {
            0x07 -> { finishOsc(); parser = State.Ground } // BEL — end of OSC
            0x1b -> parser = State.OscEsc // possibly ST (ESC \)
            // Length cap: an untrusted server could send an unbounded OSC (especially OSC 52 base64) and
            // blow up the heap to OOM. Bytes beyond the limit are dropped, but parsing continues to the
            // terminator (so the tail doesn't leak into Ground as text). A truncated OSC 52 -> broken
            // base64 -> silently discarded in setClipboard.
            else -> if (osc.length < MAX_OSC_LEN) osc.append((b and 0xff).toChar())
        }
    }

    private fun oscEsc(b: Int) {
        finishOsc()
        parser = State.Ground
        if (b != '\\'.code) process(b)
    }

    private fun finishOsc() {
        // OSC bytes were accumulated as characters 1:1 (byte->char), but the payload is UTF-8 (window
        // title, hyperlink URI; the terminal is UTF-8-only overall, like the Ground state). The whole
        // payload is decoded at once: ASCII subcommands (base64 OSC 52, color specs) are unaffected by
        // decoding, broken sequences become U+FFFD instead of mojibake.
        val s = ByteArray(osc.length) { osc[it].code.toByte() }.decodeToString()
        val sep = s.indexOf(';')
        // A code-only OSC (no ';') is valid — e.g. `OSC 104 ST` (reset the whole palette).
        val code = (if (sep < 0) s else s.substring(0, sep)).toIntOrNull() ?: return
        val rest = if (sep < 0) "" else s.substring(sep + 1)
        when (code) {
            // C0/C1/DEL are stripped from the title: a server must not corrupt the tab UI or smuggle
            // control bytes into consumers of the string (logs, etc).
            0, 1, 2 -> title = rest.filter { it.code in 0x20..0x7e || it.code >= 0xa0 }
            4 -> setPalette(rest)     // OSC 4 ; index ; spec [ ; index ; spec ... ]
            8 -> setHyperlink(rest)   // OSC 8 ; params ; URI
            52 -> setClipboard(rest)  // OSC 52 ; Pc ; Pd
            104 -> resetPalette(rest) // OSC 104 [ ; index ... ]  (empty = whole palette)
        }
    }

    /**
     * OSC 52: `Pc;Pd`. Pd is base64 text to write to the clipboard (via [onClipboardCopy]).
     * Pd == `?` is a server request to READ the clipboard; deliberately ignored (the user's
     * clipboard is never handed to the server). Invalid base64 is silently discarded.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun setClipboard(rest: String) {
        val data = rest.substringAfter(';', "")
        if (data.isEmpty() || data.startsWith("?")) return // empty or a read request — clipboard not handed over
        val text = runCatching { Base64.decode(data).decodeToString() }.getOrNull() ?: return
        if (text.length > MAX_CLIPBOARD_LEN) return // anti-flood: server can't dump megabytes into the system clipboard
        onClipboardCopy(text)
    }

    /** OSC 4: `index;spec` pairs. A `?` spec (query) is skipped — nothing to reply with (renderer owns colors). */
    private fun setPalette(rest: String) {
        val parts = rest.split(';')
        var i = 0
        while (i + 1 < parts.size) {
            val idx = parts[i].toIntOrNull()
            val rgb = parseXColor(parts[i + 1])
            if (idx != null && idx in 0..255 && rgb != null) { paletteOverrides[idx] = rgb; paletteDirty = true }
            i += 2
        }
    }

    /** OSC 104: no arguments resets the whole palette; otherwise resets the listed indices. */
    private fun resetPalette(rest: String) {
        if (rest.isBlank()) { paletteOverrides.clear(); paletteDirty = true; return }
        for (part in rest.split(';')) part.toIntOrNull()?.let { if (paletteOverrides.remove(it) != null) paletteDirty = true }
    }

    /**
     * Parses an X11/xterm color spec into [TermColor.Rgb]: `rgb:R/G/B` (1..4 hex digits per
     * component, scaled to 0..255) and `#RGB`/`#RRGGBB`/`#RRRRGGGGBBBB`. Anything else (`?`, color
     * names) yields null.
     */
    private fun parseXColor(spec: String): TermColor.Rgb? {
        if (spec.startsWith("rgb:")) {
            val ch = spec.substring(4).split('/')
            if (ch.size != 3) return null
            val r = scaleHex(ch[0]) ?: return null
            val g = scaleHex(ch[1]) ?: return null
            val b = scaleHex(ch[2]) ?: return null
            return TermColor.Rgb(r, g, b)
        }
        if (spec.startsWith("#")) {
            val hex = spec.substring(1)
            // X11 #-form: 3 (#RGB), 6 (#RRGGBB), or 12 (#RRRRGGGGBBBB) hex digits; other lengths are invalid.
            if (hex.length != 3 && hex.length != 6 && hex.length != 12) return null
            val n = hex.length / 3
            val r = scaleHex(hex.substring(0, n)) ?: return null
            val g = scaleHex(hex.substring(n, 2 * n)) ?: return null
            val b = scaleHex(hex.substring(2 * n, 3 * n)) ?: return null
            return TermColor.Rgb(r, g, b)
        }
        return null
    }

    /** Hex component of 1..4 digits scaled to 0..255 by digit count (`ff`->255, `ffff`->255, `8080`->128). */
    private fun scaleHex(h: String): Int? {
        if (h.isEmpty() || h.length > 4) return null
        val v = h.toIntOrNull(16) ?: return null
        val max = (1 shl (4 * h.length)) - 1
        return (v * 255 + max / 2) / max
    }

    /**
     * OSC 8: `params;URI` — params (e.g. `id=...`) are ignored, the URI is taken after the first
     * `;`. An empty URI closes the current link. The active URI attaches to subsequently printed cells.
     */
    private fun setHyperlink(rest: String) {
        // Length cap: the URI is duplicated onto every printed cell, so a megabyte URI would bloat the grid.
        val uri = rest.substringAfter(';', "").trim().take(MAX_HYPERLINK_LEN)
        currentHyperlink = uri.ifEmpty { null }
    }

    // --- String sequences (DCS / APC / PM / SOS) ----------------------------

    private fun strSeqByte(b: Int) {
        when (b) {
            // BEL as terminator is a nonstandard relaxation (per ECMA-48, DCS/APC/PM/SOS terminate only
            // on ST), convenient for applications that send BEL. Note: 0x07 can occur in binary graphics
            // data, so a future streamed-graphics feature would need to drop this relaxation.
            0x07 -> { finishStrSeq(); parser = State.Ground }
            0x1b -> parser = State.StrSeqEsc                        // possibly ST (ESC \)
            // Length cap reuses MAX_OSC_LEN (4 MiB) — generous headroom for future streamed graphics;
            // current XTGETTCAP usage needs only kilobytes. Protects against unbounded DCS -> OOM.
            else -> if (strSeq.length < MAX_OSC_LEN) strSeq.append((b and 0xff).toChar())
        }
    }

    private fun strSeqEsc(b: Int) {
        finishStrSeq()
        parser = State.Ground
        // process(b) is safe even when b==ESC: strSeq was already cleared in finishStrSeq, so no double flush.
        if (b != '\\'.code) process(b) // not ST — ESC starts a new sequence
    }

    /**
     * Finishes a string sequence. APC/PM/SOS are absorbed whole (their body never touches the
     * screen — kitty graphics, window messages, etc. don't leak as garbage). DCS is additionally
     * parsed for XTGETTCAP (`+q`); other DCS forms (sixel `q...`, DECRQSS `$q...`) are currently just
     * absorbed — image rendering isn't implemented yet.
     */
    private fun finishStrSeq() {
        if (strSeqIsDcs) {
            val s = strSeq.toString()
            if (s.startsWith("+q")) replyXtGetTcap(s.substring(2))
        }
        strSeq.clear()
    }

    /**
     * XTGETTCAP (`DCS + q <hex-name>[;...] ST`): replies with a DCS response for each requested
     * name — `DCS 1 + r <name>=<hex-value> ST` for known ones, `DCS 0 + r <name> ST` for unknown
     * (names/values in hex, as in terminfo). Reports itself as xterm-256color so applications enable
     * 256 colors / truecolor.
     *
     * Security: [hexName] is inserted into the reply string as-is, but `hexDecode(hexName) ?: continue`
     * only lets through VALID hex (even length, digits 0-9a-fA-F), so ESC/`\`/`;` can never reach the
     * reply — injecting control sequences into the stream back to the server is impossible.
     */
    private fun replyXtGetTcap(hexNames: String) {
        var replies = 0
        for (hexName in hexNames.split(';')) {
            if (replies >= MAX_XTGETTCAP_REPLIES) break // anti-amplification: one DCS can't spawn thousands of replies
            if (hexName.isEmpty()) continue
            val name = hexDecode(hexName) ?: continue // also guarantees hexName is clean hex (see above)
            replies++
            val value = when (name) {
                "Co", "colors" -> "256"
                "TN" -> "xterm-256color"
                "RGB" -> "8/8/8"
                else -> null
            }
            if (value != null) respond("${ESC}P1+r$hexName=${hexEncode(value)}$ESC\\")
            else respond("${ESC}P0+r$hexName$ESC\\")
        }
    }

    /** Decodes a string of hex-digit pairs to ASCII; odd length or non-hex yields null. */
    private fun hexDecode(s: String): String? {
        if (s.length % 2 != 0) return null
        val sb = StringBuilder(s.length / 2)
        var i = 0
        while (i < s.length) {
            val v = s.substring(i, i + 2).toIntOrNull(16) ?: return null
            sb.append(v.toChar())
            i += 2
        }
        return sb.toString()
    }

    /** Encodes an ASCII string as hex-digit pairs (uppercase, as in terminfo/xterm). */
    private fun hexEncode(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (ch in s) {
            val code = ch.code and 0xff
            sb.append(HEX_DIGITS[code ushr 4]).append(HEX_DIGITS[code and 0xf])
        }
        return sb.toString()
    }

    // --- CSI dispatch --------------------------------------------------------

    private fun dispatchCsi(final: Char) {
        val raw = params.toString()
        val privateMarker = raw.firstOrNull()?.takeIf { it == '?' || it == '<' || it == '=' || it == '>' }
        // DECRQM (CSI [?] Ps $ p): request the current mode state — reply with DECRPM.
        if (csiIntermediate == '$' && final == 'p') {
            val body = if (privateMarker == '?') raw.substring(1) else raw
            reportMode(parseArgs(body).firstOrNull() ?: 0, private = privateMarker == '?')
            return
        }
        if (privateMarker == '?') { privateMode(final, parseArgs(raw.substring(1))); return }
        if (privateMarker != null) {
            // Secondary DA (CSI > c) and others — reply minimally, absorb the rest.
            if (privateMarker == '>' && final == 'c') respond("$ESC[>0;10;0c")
            // XTVERSION (CSI > q): report terminal name/version via DCS.
            if (privateMarker == '>' && final == 'q') respond("${ESC}P>|Skerry(0.1)$ESC\\")
            return
        }
        if (csiIntermediate == '!' && final == 'p') { softReset(); return }
        // DECSCUSR (CSI Ps SP q) — cursor shape and blink.
        if (csiIntermediate == ' ' && final == 'q') { setCursorStyle(parseArgs(raw)); return }
        if (csiIntermediate != NO_INTERMEDIATE) return // other intermediate sequences are absorbed

        val args = parseArgs(raw)
        fun arg(i: Int, d: Int) = args.getOrNull(i)?.takeIf { it > 0 } ?: d
        when (final) {
            'm' -> style = SgrParser.apply(SgrParser.parseParams(raw), style)
            '@' -> insertChars(arg(0, 1))
            'A' -> { cy = (cy - arg(0, 1)).coerceAtLeast(topLimit()); pendingWrap = false }
            'B', 'e' -> { cy = (cy + arg(0, 1)).coerceAtMost(bottomLimit()); pendingWrap = false }
            'C', 'a' -> { cx = (cx + arg(0, 1)).coerceAtMost(cols - 1); pendingWrap = false }
            'D' -> { cx = (cx - arg(0, 1)).coerceAtLeast(0); pendingWrap = false }
            'E' -> { cy = (cy + arg(0, 1)).coerceAtMost(bottomLimit()); cx = 0; pendingWrap = false }
            'F' -> { cy = (cy - arg(0, 1)).coerceAtLeast(topLimit()); cx = 0; pendingWrap = false }
            'G', '`' -> { cx = (arg(0, 1) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'd' -> { cy = absRow(arg(0, 1) - 1); pendingWrap = false }
            'H', 'f' -> cursorTo(arg(0, 1) - 1, arg(1, 1) - 1)
            // Count is capped at the column count: the cursor hits the edge regardless, so a larger
            // repeat is pointless. Without the cap, `ESC[2147483647I` would loop ~2 billion times
            // uninterruptibly, hanging the session/UI (the server is untrusted; see other repeat commands).
            'I' -> { repeat(arg(0, 1).coerceAtMost(cols)) { cx = nextTabStop(cx) }; pendingWrap = false }
            'Z' -> { repeat(arg(0, 1).coerceAtMost(cols)) { cx = prevTabStop(cx) }; pendingWrap = false }
            'J' -> eraseDisplay(args.getOrNull(0)?.takeIf { it >= 0 } ?: 0)
            'K' -> eraseLine(args.getOrNull(0)?.takeIf { it >= 0 } ?: 0)
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            'P' -> deleteChars(arg(0, 1))
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            'X' -> eraseChars(arg(0, 1))
            'b' -> repeatLastChar(arg(0, 1))
            'g' -> clearTabStop(args.getOrNull(0)?.takeIf { it >= 0 } ?: 0)
            'h' -> if (args.contains(4)) insertMode = true
            'l' -> if (args.contains(4)) insertMode = false
            'r' -> setScrollRegion(args.getOrNull(0)?.takeIf { it > 0 } ?: 1, args.getOrNull(1)?.takeIf { it > 0 } ?: rows)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> deviceStatus(args.getOrNull(0) ?: 0)
            'c' -> if ((args.getOrNull(0) ?: 0) == 0) respond("$ESC[?1;2c") // DA: VT100 with AVO
            't' -> windowOp(args)
        }
    }

    /**
     * XTWINOPS (CSI Ps ; Ps ; Ps t). Only the window title stack is supported — 22 (push) and 23
     * (pop); the second parameter selects the target: 0 = icon + window title, 2 = window title
     * (both modeled as the title), 1 = icon name only. In xterm, icon and window title are SEPARATE
     * stacks; icon-only (`;1`) never touches the window stack on push or pop, so such operations are
     * ignored entirely here — the single (window) stack only ever sees balanced `{0,2}` pairs and
     * stays consistent. Other operations (resize/move/geometry queries) are deliberately ignored:
     * they're either irrelevant to an embedded terminal or a fingerprinting leak (xterm disables them
     * by default too).
     */
    private fun windowOp(args: List<Int>) {
        val op = args.getOrNull(0) ?: return
        val target = args.getOrNull(1) ?: 0 // absent -> 0 (icon + window)
        if (target != 0 && target != 2) return // icon-only (1) or other -> window title untouched
        when (op) {
            22 -> {
                if (titleStack.size >= MAX_TITLE_STACK) titleStack.removeFirst() // cap against push-without-pop
                titleStack.addLast(title)
            }
            23 -> titleStack.removeLastOrNull()?.let { title = it }
        }
    }

    private fun privateMode(final: Char, codes: List<Int>) {
        if (final != 'h' && final != 'l') return
        val on = final == 'h'
        for (code in codes) when (code) {
            1 -> applicationCursorKeys = on
            6 -> { originMode = on; cursorTo(0, 0) }
            7 -> autoWrap = on
            25 -> cursorVisible = on
            9 -> mouseTracking = if (on) MouseTracking.X10 else MouseTracking.Off
            1000 -> mouseTracking = if (on) MouseTracking.Normal else MouseTracking.Off
            1002 -> mouseTracking = if (on) MouseTracking.ButtonEvent else MouseTracking.Off
            1003 -> mouseTracking = if (on) MouseTracking.AnyEvent else MouseTracking.Off
            1004 -> focusReporting = on
            1006 -> mouseSgr = on
            1016 -> mousePixels = on
            2004 -> bracketedPaste = on
            47, 1047 -> setAltScreen(on, saveRestore = false)
            1049 -> setAltScreen(on, saveRestore = true)
        }
    }

    /**
     * DECRQM -> DECRPM: replies with the state of mode [code]. Pm: 1 = set, 2 = reset,
     * 0 = unrecognized. [private] selects the DEC-private (`?`-marker) or ANSI set.
     */
    private fun reportMode(code: Int, private: Boolean) {
        val set: Boolean? = if (private) privateModeSet(code) else ansiModeSet(code)
        val pm = when (set) { true -> 1; false -> 2; null -> 0 }
        val marker = if (private) "?" else ""
        respond("$ESC[$marker$code;$pm\$y")
    }

    /** Current state of a DEC-private mode for DECRQM, or `null` if the mode is unrecognized. */
    private fun privateModeSet(code: Int): Boolean? = when (code) {
        1 -> applicationCursorKeys
        6 -> originMode
        7 -> autoWrap
        25 -> cursorVisible
        9 -> mouseTracking == MouseTracking.X10
        1000 -> mouseTracking == MouseTracking.Normal
        1002 -> mouseTracking == MouseTracking.ButtonEvent
        1003 -> mouseTracking == MouseTracking.AnyEvent
        1004 -> focusReporting
        1006 -> mouseSgr
        1016 -> mousePixels
        2004 -> bracketedPaste
        47, 1047, 1049 -> altScreen
        else -> null
    }

    /** Current state of an ANSI mode for DECRQM, or `null` if the mode is unrecognized. */
    private fun ansiModeSet(code: Int): Boolean? = when (code) {
        4 -> insertMode // IRM
        else -> null
    }

    private fun deviceStatus(code: Int) {
        when (code) {
            5 -> respond("$ESC[0n") // OK
            6 -> { // CPR — cursor position, 1-based, relative to origin under DECOM
                val row = (if (originMode) cy - scrollTop else cy) + 1
                respond("$ESC[$row;${cx + 1}R")
            }
        }
    }

    // --- Printing and movement ------------------------------------------------

    private fun putCodePoint(cp: Int) {
        if (CharMetrics.isCombining(cp) && appendCombining(cp)) return // attached to the base — cursor unchanged
        val w = CharMetrics.charWidth(cp)
        if (pendingWrap) {
            // Soft wrap: the row being left logically continues on the next one — mark it wrapped
            // (for reflow) BEFORE lineFeed, while cy still points at it.
            grid[cy].wrapped = true
            cx = 0
            lineFeed()
            pendingWrap = false
        }
        // A wide character doesn't fit in the last column: with autowrap, move to a new row;
        // otherwise place it alone in the last cell (no room for a continuation).
        if (w == 2 && cx >= cols - 1 && autoWrap) { grid[cy].wrapped = true; cx = 0; lineFeed() }

        val text = CharMetrics.codePointToString(cp)
        val row = grid[cy]
        if (insertMode) {
            repeat(w) { row.add(cx, blankCell()) }
            while (row.size > cols) row.removeAt(row.size - 1)
        }
        if (w == 2 && cx < cols - 1) {
            row[cx] = TermCell(text, style, CellWidth.Wide, currentHyperlink)
            row[cx + 1] = TermCell("", style, CellWidth.Continuation, currentHyperlink)
        } else {
            row[cx] = TermCell(text, style, CellWidth.Single, currentHyperlink)
        }
        lastPrintedCp = cp
        val rightmost = (cx + w - 1).coerceAtMost(cols - 1)
        if (rightmost >= cols - 1) { cx = cols - 1; if (autoWrap) pendingWrap = true } else cx = rightmost + 1
    }

    /**
     * Attaches a combining mark (diacritic, ZWJ, variation selector) to the text of the previous
     * base cell without moving the cursor or changing its width — so "e"+U+0301 renders as one cell,
     * and ZWJ emoji chains don't fall apart. Returns false when there's no base to the left (cursor
     * in column 0) — the mark is then printed as an ordinary cell (fallback). Cluster length is
     * capped (protects against an untrusted server flooding marks into one cell).
     */
    private fun appendCombining(cp: Int): Boolean {
        val row = grid[cy]
        val baseCol = when {
            pendingWrap -> cx                                                    // cursor hasn't moved: cx == cols-1 (>=0), the last printed cell
            cx == 0 -> return false                                              // nothing to the left to attach to
            row[cx - 1].width == CellWidth.Continuation && cx >= 2 -> cx - 2     // under a Wide char — take the Wide cell itself
            else -> cx - 1
        }
        val base = row[baseCol]
        if (base.width == CellWidth.Continuation) return false                   // guard: never attach to a bare continuation
        if (base.text.length >= MAX_GRAPHEME_LEN) return true                    // cluster full — silently drop the mark
        row[baseCol] = base.copy(text = base.text + CharMetrics.codePointToString(cp))
        return true
    }

    /** REP (CSI Ps b): repeats the last printed character Ps times. Clamped to the screen area. */
    private fun repeatLastChar(n: Int) {
        val cp = lastPrintedCp ?: return
        repeat(n.coerceIn(1, cols * rows)) { putCodePoint(cp) }
    }

    private fun lineFeed() {
        if (cy == scrollBottom) scrollUp(1)
        else if (cy < rows - 1) cy++
    }

    private fun reverseIndex() {
        if (cy == scrollTop) scrollDown(1)
        else if (cy > 0) cy--
    }

    private fun cursorTo(row: Int, col: Int) {
        cy = absRow(row)
        cx = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    /** Maps a 0-based row to absolute, honoring origin mode (within the region). */
    private fun absRow(row: Int): Int =
        if (originMode) (scrollTop + row).coerceIn(scrollTop, scrollBottom) else row.coerceIn(0, rows - 1)

    private fun topLimit() = if (originMode) scrollTop else 0
    private fun bottomLimit() = if (originMode) scrollBottom else rows - 1

    // --- Scrolling ---------------------------------------------------------

    private fun scrollUp(n: Int) = repeat(n.coerceAtMost(scrollBottom - scrollTop + 1)) {
        val removed = grid.removeAt(scrollTop)
        if (!altScreen && scrollTop == 0) pushScrollback(removed)
        grid.add(scrollBottom, blankRow())
    }

    private fun scrollDown(n: Int) = repeat(n.coerceAtMost(scrollBottom - scrollTop + 1)) {
        grid.removeAt(scrollBottom)
        grid.add(scrollTop, blankRow())
    }

    private fun pushScrollback(row: TermRow) {
        scrollback.addLast(row)
        scrollbackView.addLast(row.toList())
        while (scrollback.size > maxScrollback) {
            scrollback.removeFirst()
            scrollbackView.removeFirst()
        }
    }

    // --- Erase / insert / delete ------------------------------------------

    private fun eraseLine(mode: Int) {
        val row = grid[cy]
        when (mode) {
            // Erasing the tail (0) or the whole row (2) removes its continuation — clear wrapped so
            // reflow doesn't glue the next row to it. Erasing the head (1) leaves the tail alone.
            0 -> { for (c in cx until cols) row[c] = blankCell(); row.wrapped = false }
            1 -> for (c in 0..cx.coerceAtMost(cols - 1)) row[c] = blankCell()
            2 -> { for (c in 0 until cols) row[c] = blankCell(); row.wrapped = false }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cy + 1 until rows) blankLine(r) }
            1 -> { for (r in 0 until cy) blankLine(r); eraseLine(1) }
            // ED 2/3 clear the whole screen. On the primary buffer the old screen goes to scrollback so
            // `clear`/`Ctrl+L` leave output scrollable (like gnome-terminal/VTE) rather than lost. ED 3
            // ("erase saved lines") deliberately does not wipe history — clear sends it right after ED 2,
            // by which point the screen is already empty, so no extra rows reach history.
            2, 3 -> clearScreenToScrollback()
        }
    }

    /**
     * Clear the visible screen by moving it to scrollback (primary buffer only) — old output stays
     * scrollable. Trailing empty rows aren't moved to history, else every `clear` would spawn a screen
     * of blank rows. The alt screen has no scrollback — clear in place.
     */
    private fun clearScreenToScrollback() {
        if (altScreen) {
            for (r in 0 until rows) blankLine(r)
            return
        }
        // Don't move visually-empty trailing rows to history (else every clear would spawn a screen of
        // blank rows). "Empty" = spaces with no background color: a row with a BCE background (colored
        // strip) counts as content and is kept.
        var last = rows - 1
        while (last >= 0 && grid[last].all { it.text == " " && it.style.bg == TermColor.Default && !it.style.inverse }) last--
        // The boundary between the moved screen and the fresh blank grid must not glue during reflow:
        // clear wrapped on the last moved row, else a resize would attach a blank grid row to it.
        if (last >= 0) grid[last].wrapped = false
        for (r in 0..last) pushScrollback(grid[r])
        for (r in 0 until rows) grid[r] = blankRow()
    }

    private fun eraseChars(n: Int) {
        val row = grid[cy]
        for (c in cx until (cx + n).coerceAtMost(cols)) row[c] = blankCell()
    }

    private fun insertChars(n: Int) {
        val row = grid[cy]
        repeat(n.coerceAtMost(cols - cx)) { row.add(cx, blankCell()) }
        while (row.size > cols) row.removeAt(row.size - 1)
    }

    private fun deleteChars(n: Int) {
        val row = grid[cy]
        repeat(n.coerceAtMost(cols - cx)) { row.removeAt(cx) }
        while (row.size < cols) row.add(blankCell())
    }

    private fun insertLines(n: Int) {
        if (cy < scrollTop || cy > scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - cy + 1)) {
            grid.removeAt(scrollBottom)
            grid.add(cy, blankRow())
        }
        cx = 0
        pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cy < scrollTop || cy > scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - cy + 1)) {
            grid.removeAt(cy)
            grid.add(scrollBottom, blankRow())
        }
        cx = 0
        pendingWrap = false
    }

    private fun setScrollRegion(top1: Int, bottom1: Int) {
        val top = (top1 - 1).coerceIn(0, rows - 1)
        val bottom = (bottom1 - 1).coerceIn(0, rows - 1)
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
            cursorTo(0, 0)
        }
    }

    // --- Cursor / reset / alt-screen --------------------------------------

    private fun saveCursor() {
        savedCx = cx; savedCy = cy; savedStyle = style
        savedG0LineDrawing = g0LineDrawing; savedG1LineDrawing = g1LineDrawing; savedGlG1 = glG1
    }

    private fun restoreCursor() {
        cx = savedCx.coerceIn(0, cols - 1)
        cy = savedCy.coerceIn(0, rows - 1)
        style = savedStyle
        g0LineDrawing = savedG0LineDrawing; g1LineDrawing = savedG1LineDrawing; glG1 = savedGlG1
        pendingWrap = false
    }

    private fun setAltScreen(on: Boolean, saveRestore: Boolean) {
        if (on == altScreen) return
        if (on) {
            if (saveRestore) saveCursor()
            val fresh = freshScreen()
            altGrid = fresh
            grid = fresh
            altScreen = true
            resetRegion()
        } else {
            altGrid = null
            grid = primaryGrid
            altScreen = false
            resetRegion()
            if (saveRestore) restoreCursor()
        }
    }

    private fun resetRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    /**
     * DECSCUSR (`CSI Ps SP q`): the number sets cursor shape and blink. 0/1 — blinking block (default),
     * 2 — block, 3 — blinking underline, 4 — underline, 5 — blinking bar, 6 — bar. Odd (and 0) blink,
     * even don't.
     */
    private fun setCursorStyle(args: List<Int>) {
        val n = args.getOrNull(0)?.takeIf { it >= 0 } ?: 0
        cursorShape = when (n) {
            3, 4 -> CursorShape.Underline
            5, 6 -> CursorShape.Bar
            else -> CursorShape.Block
        }
        cursorBlink = n == 0 || n % 2 == 1
    }

    private fun softReset() {
        style = TermStyle()
        resetRegion()
        originMode = false
        insertMode = false
        autoWrap = true
        cursorVisible = true
        cursorShape = CursorShape.Block
        cursorBlink = true
        applicationCursorKeys = false
        applicationKeypad = false
        pendingWrap = false
        g0LineDrawing = false; g1LineDrawing = false; glG1 = false
        pendingDesignation = -1
    }

    private fun reset() {
        primaryGrid = freshScreen()
        altGrid = null
        grid = primaryGrid
        altScreen = false
        scrollback.clear()
        scrollbackView.clear()
        cx = 0; cy = 0
        pendingWrap = false
        lastPrintedCp = null
        currentHyperlink = null
        paletteOverrides.clear()
        paletteDirty = true
        style = TermStyle()
        resetRegion()
        tabStops = defaultTabStops(cols)
        originMode = false; insertMode = false; autoWrap = true; cursorVisible = true
        cursorShape = defaultCursorShape; cursorBlink = defaultCursorBlink
        applicationCursorKeys = false; applicationKeypad = false
        bracketedPaste = false; mouseTracking = MouseTracking.Off; mouseSgr = false; mousePixels = false; focusReporting = false
        g0LineDrawing = false; g1LineDrawing = false; glG1 = false
        pendingDesignation = -1
        strSeq.clear(); strSeqIsDcs = false; parser = State.Ground // RIS aborts any partial sequence
        title = ""; titleStack.clear() // RIS resets the title to default (the tab falls back to host.label)
    }

    // --- Tab stops ---------------------------------------------------------

    private fun nextTabStop(from: Int): Int {
        var c = from + 1
        while (c < cols && !tabStops[c]) c++
        return c.coerceAtMost(cols - 1)
    }

    private fun prevTabStop(from: Int): Int {
        var c = from - 1
        while (c > 0 && !tabStops[c]) c--
        return c.coerceAtLeast(0)
    }

    /** TBC: 0 — clear the tab stop at the current column, 3 — clear all. */
    private fun clearTabStop(mode: Int) {
        when (mode) {
            0 -> if (cx in tabStops.indices) tabStops[cx] = false
            3 -> tabStops.fill(false)
        }
    }

    // --- Resize ------------------------------------------------------------

    /**
     * Resize the grid. The primary buffer is reflowed: soft-wrapped rows are joined into logical rows
     * and re-split at the new width, scrollback participates, the cursor moves with its text. The alt
     * buffer is not reflowed (the app repaints) — trim/pad. Resets the scroll region and tab stops.
     */
    fun resize(newCols: Int, newRows: Int) {
        // Upper cap: rules out Int overflow in cols*rows (REP) and an insane amount of resize work;
        // 2000 comfortably covers any real display at a tiny font.
        val nc = newCols.coerceIn(1, MAX_DIMENSION)
        val nr = newRows.coerceIn(1, MAX_DIMENSION)
        if (nc == cols && nr == rows) return
        val wasPendingWrap = pendingWrap
        val (newCy, newCx) = reflowPrimary(nc, nr, trackCursor = !altScreen)
        if (!altScreen) grid = primaryGrid
        altGrid?.let { resizeGrid(it, nc, nr, activePrimary = false) }
        cols = nc
        rows = nr
        tabStops = defaultTabStops(nc)
        resetRegion()
        if (altScreen) {
            cx = cx.coerceIn(0, nc - 1)
            cy = cy.coerceIn(0, nr - 1)
            pendingWrap = false
        } else {
            cx = newCx.coerceIn(0, nc - 1)
            cy = newCy.coerceIn(0, nr - 1)
            // If the cursor was in pending-wrap and after reflow landed on the last column again — keep
            // the mode (the next char wraps rather than overwrites). Otherwise reset it.
            pendingWrap = wasPendingWrap && cx == nc - 1
        }
    }

    /**
     * Reflow the primary buffer (scrollback + [primaryGrid]) to width [nc]/height [nr] — the
     * algorithm in [TerminalReflow.reflow] (pure functions); here only input collection and applying the
     * result to state. Returns the new cursor position `(cy, cx)` in new-grid coordinates (meaningful
     * only with [trackCursor]).
     */
    private fun reflowPrimary(nc: Int, nr: Int, trackCursor: Boolean): Pair<Int, Int> {
        val src = ArrayList<TermRow>(scrollback.size + primaryGrid.size).apply {
            addAll(scrollback); addAll(primaryGrid)
        }
        val result = TerminalReflow.reflow(
            src = src,
            nc = nc,
            nr = nr,
            maxScrollback = maxScrollback,
            cursorAbs = scrollback.size + cy,
            cursorCol = cx,
            rowsBelowCursor = rows - 1 - cy,
            trackCursor = trackCursor,
        )
        scrollback.clear()
        scrollbackView.clear()
        result.scrollback.forEach {
            scrollback.addLast(it)
            scrollbackView.addLast(it.toList())
        }
        primaryGrid = result.grid
        return Pair(result.cursorRow, result.cursorCol)
    }

    /** Resize the grid without reflow (alt-screen): trim/pad columns and rows. */
    private fun resizeGrid(g: MutableList<TermRow>, nc: Int, nr: Int, activePrimary: Boolean) {
        for (row in g) {
            while (row.size > nc) row.removeAt(row.size - 1)
            while (row.size < nc) row.add(TermCell(' '))
        }
        if (g.size > nr) {
            // When shrinking the active primary buffer, shift the top into scrollback so the cursor stays visible.
            if (activePrimary) {
                val keepFromTop = (cy - (nr - 1)).coerceAtLeast(0).coerceAtMost(g.size - nr)
                repeat(keepFromTop) { pushScrollback(g.removeAt(0)); cy-- }
            }
            while (g.size > nr) g.removeAt(g.size - 1)
        } else {
            while (g.size < nr) g.add(TermRow(MutableList(nc) { TermCell(' ') }))
        }
    }

    // --- Cell factories ----------------------------------------------------

    private fun freshScreen(): MutableList<TermRow> =
        MutableList(rows) { TermRow(MutableList(cols) { TermCell(' ') }) }

    /**
     * A blank cell with the current background — background-color-erase (BCE): erase and scroll paint
     * cells with the current SGR background, including reverse-video (then the "background" is the text
     * color). Carry fg/bg/inverse: ncurses (nano/htop) refills reverse strips via `ESC[K` relying on BCE
     * — without the inverse flag the row tail would draw with the normal background. Glyph attributes
     * (bold/underline/strike) aren't carried on a blank cell: xterm doesn't apply them on erase.
     */
    private fun blankCell() = TermCell(' ', TermStyle(fg = style.fg, bg = style.bg, inverse = style.inverse))

    private fun blankRow() = TermRow(MutableList(cols) { blankCell() })

    private fun blankLine(r: Int) {
        val row = grid[r]
        for (c in 0 until cols) row[c] = blankCell()
        row.wrapped = false
    }

    private fun parseArgs(raw: String): List<Int> {
        if (raw.isEmpty()) return emptyList()
        // Non-SGR CSI (cursor, DECSCUSR, modes) carry no colon; fold ':' to ';' just in case. SGR is
        // parsed by the separate colon-aware [SgrParser.parseParams] (subparameters matter there).
        return raw.replace(':', ';').split(';').map { it.toIntOrNull() ?: -1 }
    }

    private companion object {
        /** OSC string length cap (OOM guard on untrusted output); 4 MiB with headroom for OSC 52. */
        const val MAX_OSC_LEN = 4 * 1024 * 1024

        /** CSI params buffer length cap (OOM guard: a server pours digits with no final byte). */
        const val MAX_CSI_PARAMS_LEN = 1024

        /** OSC 52 text size cap for writing to the system clipboard (anti-flood). */
        const val MAX_CLIPBOARD_LEN = 64 * 1024

        /** Cap on replies per XTGETTCAP request (anti-amplification of outgoing PTY traffic). */
        const val MAX_XTGETTCAP_REPLIES = 64

        /** OSC 8 URI length cap (the URI is copied into every cell — grid bloat guard). */
        const val MAX_HYPERLINK_LEN = 2048

        /** Grid width/height cap: against Int overflow in cols*rows and excessive resize work. */
        const val MAX_DIMENSION = 2000

        /** Window-title stack depth cap (CSI 22 t without a matching 23 t) — bloat guard. */
        const val MAX_TITLE_STACK = 128

        /** Cell grapheme-cluster length cap (base + combining) — bloat guard. */
        const val MAX_GRAPHEME_LEN = 32
        const val TAB = 8
        val ESC = 27.toChar().toString()
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /** Sentinel "CSI had no intermediate byte" (NUL) — distinguishes it from a real space (0x20) in DECSCUSR. */
        // NUL via toChar(), not a char literal: the project bans raw control bytes in source, and the
        // Edit tool folds \u-escapes into a raw byte. The comparison is rare (once per CSI sequence), so
        // the lack of a const inline is immaterial.
        val NO_INTERMEDIATE = 0.toChar()

        /**
         * DEC Special Graphics (VT100 line-drawing): ASCII 0x60..0x7e → Unicode glyphs. Index = code -
         * 0x60. Corners/tees/lines (j..x) are what tmux/mc/htop draw borders with; the rest (diamond,
         * shading, scan lines, ≤≥π≠£·) are filled in for completeness.
         */
        const val DEC_SPECIAL_GRAPHICS =
            "◆▒␉␌␍␊°±" + // ` a b c d e f g
            "␤␋┘┐┌└┼⎺" + // h i j k l m n o
            "⎻─⎼⎽├┤┴┬" + // p q r s t u v w
            "│≤≥π≠£·"          // x y z { | } ~

        fun defaultTabStops(cols: Int) = BooleanArray(cols) { it % TAB == 0 && it != 0 }
    }
}
