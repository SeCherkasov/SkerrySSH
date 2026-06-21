package app.skerry.shared.terminal

/**
 * Цвет ячейки. UI-независим (не Compose Color): рендер сам переводит в палитру темы.
 *  - [Default] — «цвет по умолчанию» (берётся из темы);
 *  - [Indexed] — индекс xterm-палитры: 0..7 базовые ANSI, 8..15 яркие, 16..255 куб/градации серого;
 *  - [Rgb] — truecolor (24-bit).
 */
sealed interface TermColor {
    data object Default : TermColor
    data class Indexed(val index: Int) : TermColor
    data class Rgb(val r: Int, val g: Int, val b: Int) : TermColor

    companion object {
        // Имена для читаемости тестов/кода. Это просто индексы xterm-палитры.
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

/** Атрибуты ячейки. Цвета — [TermColor]; флаги покрывают полный набор SGR-атрибутов. */
data class TermStyle(
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
    val strikethrough: Boolean = false,
)

/** Одна ячейка экрана: символ + его стиль. */
data class TermCell(val char: Char, val style: TermStyle = TermStyle())

/** Режим репортинга мыши в приложение (DEC private modes). Кодировку выбирает [TerminalEmulator.mouseSgr]. */
enum class MouseTracking { Off, X10, Normal, ButtonEvent, AnyEvent }

/** Форма курсора (DECSCUSR `CSI Ps SP q`): блок, подчёркивание или вертикальная черта. */
enum class CursorShape { Block, Underline, Bar }

/**
 * Полноценный VT/ANSI-эмулятор поверх фиксированной сетки `rows × cols` — устройство-независимая
 * логика (без Compose). UI рендерит [lines] (scrollback + экран) и блок-курсор по
 * [cursorRow]/[cursorCol] (абсолютные индексы в [lines]).
 *
 * Парсер — байтовая state-machine, держащая состояние между вызовами [feed], поэтому корректно
 * переживает разрезанные между чанками escape-последовательности И многобайтовый UTF-8.
 *
 * Покрыто: печать с автопереносом (DECAWM) и pending-wrap; CR/LF/BS/HT/BEL; IND/NEL/RI/HTS/RIS;
 * DECSC/DECRC; адресация курсора (CUU/CUD/CUF/CUB/CNL/CPL/CHA/CUP/VPA/HPA/CHT/CBT); стирание
 * (EL/ED, включая очистку scrollback по `ED 3`); вставка/удаление (ICH/DCH/ECH/IL/DL/SU/SD);
 * регион прокрутки (DECSTBM); insert-режим (IRM); полный SGR (атрибуты + 16/256/truecolor);
 * приватные режимы (DECCKM/DECOM/DECAWM/?25/alt-screen 47/1047/1049/mouse 1000-1006/bracketed
 * paste 2004); табстопы; ответы DSR/DA и заголовок окна (OSC 0/1/2). Неизвестные
 * последовательности безопасно поглощаются.
 *
 * НЕ потокобезопасен: [feed] и чтение состояния идут из одной корутины-сборщика вывода
 * (так его и использует `TerminalScreenState`).
 *
 * @param respond вызывается с ответами терминала (DSR/DA) — UI шлёт их обратно в PTY.
 * @param onBell вызывается на BEL (0x07).
 */
class TerminalEmulator(
    cols: Int = 80,
    rows: Int = 24,
    private val maxScrollback: Int = DEFAULT_MAX_SCROLLBACK,
    private val respond: (String) -> Unit = {},
    private val onBell: () -> Unit = {},
) {
    var cols: Int = cols.coerceAtLeast(1)
        private set
    var rows: Int = rows.coerceAtLeast(1)
        private set

    private var primaryGrid: MutableList<MutableList<TermCell>> = freshScreen()
    private var altGrid: MutableList<MutableList<TermCell>>? = null
    private var grid: MutableList<MutableList<TermCell>> = primaryGrid
    private val scrollback = ArrayDeque<List<TermCell>>()

    /** true, когда активен альтернативный буфер (полноэкранные TUI). У него нет scrollback. */
    var altScreen: Boolean = false
        private set

    // Курсор относительно текущего экрана (0-based).
    private var cx = 0
    private var cy = 0
    private var pendingWrap = false

    /** Абсолютный индекс строки курсора в [lines] (с учётом scrollback в основном буфере). */
    val cursorRow: Int get() = (if (altScreen) 0 else scrollback.size) + cy

    /** Колонка курсора (0-based). */
    val cursorCol: Int get() = cx

    /**
     * Снимок для отрисовки: scrollback (только основной буфер) + строки текущего экрана.
     * Каждая строка копируется в неизменяемый список — снимок безопасно переживает последующие
     * [feed] (внутренние строки сетки живые и мутируются на месте).
     */
    val lines: List<List<TermCell>>
        get() = ArrayList<List<TermCell>>((if (altScreen) 0 else scrollback.size) + grid.size).apply {
            if (!altScreen) scrollback.forEach { add(it.toList()) }
            grid.forEach { add(it.toList()) }
        }

    private var style = TermStyle()

    // Регион прокрутки (DECSTBM), 0-based включительно.
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    // Сохранённый курсор (DECSC/DECRC; также используется alt-screen 1049).
    private var savedCx = 0
    private var savedCy = 0
    private var savedStyle = TermStyle()

    private var tabStops = defaultTabStops(this.cols)

    // Режимы.
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false

    var cursorVisible: Boolean = true
        private set

    /** Форма курсора (DECSCUSR). По умолчанию блок, как у xterm. */
    var cursorShape: CursorShape = CursorShape.Block
        private set

    /** Должен ли курсор мигать (DECSCUSR steady/blink). По умолчанию мигает (xterm DECSCUSR 1). */
    var cursorBlink: Boolean = true
        private set

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
    var title: String = ""
        private set

    // --- Парсер ------------------------------------------------------------

    private enum class State { Ground, Esc, Csi, Osc, OscEsc, Consume, Utf8 }

    private var parser = State.Ground
    private val params = StringBuilder()

    // Промежуточный байт CSI (0x20..0x2f). NUL = «не было»: отличаем от РЕАЛЬНОГО пробельного
    // intermediate (0x20) у DECSCUSR (`CSI Ps SP q`) — иначе форму курсора не отличить от обычного CSI.
    private var csiIntermediate = NO_INTERMEDIATE
    private val osc = StringBuilder()

    private var utf8Remaining = 0
    private var utf8CodePoint = 0

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
            State.Consume -> parser = State.Ground // поглощаем один байт (designation набора и т.п.)
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
            b < 0x20 -> {} // прочие C0 — игнор
            b < 0x80 -> putChar(b.toChar())
            else -> beginUtf8(b)
        }
    }

    private fun beginUtf8(b: Int) {
        val (len, init) = when {
            b and 0xE0 == 0xC0 -> 2 to (b and 0x1F)
            b and 0xF0 == 0xE0 -> 3 to (b and 0x0F)
            b and 0xF8 == 0xF0 -> 4 to (b and 0x07)
            else -> { putChar('�'); return }
        }
        utf8Remaining = len - 1
        utf8CodePoint = init
        parser = State.Utf8
    }

    private fun utf8(b: Int) {
        if (b and 0xC0 != 0x80) {
            putChar('�')
            parser = State.Ground
            process(b)
            return
        }
        utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
        if (--utf8Remaining == 0) {
            parser = State.Ground
            putChar(if (utf8CodePoint in 0..0xFFFF) utf8CodePoint.toChar() else '�')
        }
    }

    private fun esc(b: Int) {
        when (b.toChar()) {
            '[' -> { params.clear(); csiIntermediate = NO_INTERMEDIATE; parser = State.Csi }
            ']' -> { osc.clear(); parser = State.Osc }
            '(', ')', '*', '+', '-', '.', '/', '#', ' ' -> parser = State.Consume
            '7' -> { saveCursor(); parser = State.Ground }
            '8' -> { restoreCursor(); parser = State.Ground }
            'D' -> { lineFeed(); pendingWrap = false; parser = State.Ground } // IND
            'E' -> { lineFeed(); cx = 0; pendingWrap = false; parser = State.Ground } // NEL
            'M' -> { reverseIndex(); pendingWrap = false; parser = State.Ground } // RI
            'H' -> { tabStops[cx] = true; parser = State.Ground } // HTS
            'c' -> { reset(); parser = State.Ground } // RIS
            '=' -> { applicationKeypad = true; parser = State.Ground } // DECKPAM
            '>' -> { applicationKeypad = false; parser = State.Ground } // DECKPNM
            else -> parser = State.Ground
        }
    }

    private fun csi(b: Int) {
        when {
            b in 0x30..0x3f -> params.append(b.toChar()) // цифры, ';', ':', приватные маркеры ?<=>
            b in 0x20..0x2f -> csiIntermediate = b.toChar() // промежуточные байты
            b in 0x40..0x7e -> { dispatchCsi(b.toChar()); parser = State.Ground }
            else -> parser = State.Ground
        }
    }

    private fun oscByte(b: Int) {
        when (b) {
            0x07 -> { finishOsc(); parser = State.Ground } // BEL — конец OSC
            0x1b -> parser = State.OscEsc // возможно ST (ESC \)
            else -> osc.append((b and 0xff).toChar())
        }
    }

    private fun oscEsc(b: Int) {
        finishOsc()
        parser = State.Ground
        if (b != '\\'.code) process(b)
    }

    private fun finishOsc() {
        val s = osc.toString()
        val sep = s.indexOf(';')
        if (sep <= 0) return
        val code = s.substring(0, sep).toIntOrNull() ?: return
        if (code == 0 || code == 1 || code == 2) title = s.substring(sep + 1)
    }

    // --- Диспетчеризация CSI ----------------------------------------------

    private fun dispatchCsi(final: Char) {
        val raw = params.toString()
        val privateMarker = raw.firstOrNull()?.takeIf { it == '?' || it == '<' || it == '=' || it == '>' }
        if (privateMarker == '?') { privateMode(final, parseArgs(raw.substring(1))); return }
        if (privateMarker != null) {
            // Вторичная DA (CSI > c) и прочие — отвечаем минимально, остальное поглощаем.
            if (privateMarker == '>' && final == 'c') respond("$ESC[>0;10;0c")
            return
        }
        if (csiIntermediate == '!' && final == 'p') { softReset(); return }
        // DECSCUSR (CSI Ps SP q) — форма и мигание курсора.
        if (csiIntermediate == ' ' && final == 'q') { setCursorStyle(parseArgs(raw)); return }
        if (csiIntermediate != NO_INTERMEDIATE) return // прочие intermediate-последовательности поглощаем

        val args = parseArgs(raw)
        fun arg(i: Int, d: Int) = args.getOrNull(i)?.takeIf { it > 0 } ?: d
        when (final) {
            'm' -> applySgr(args)
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
            'I' -> { repeat(arg(0, 1)) { cx = nextTabStop(cx) }; pendingWrap = false }
            'Z' -> { repeat(arg(0, 1)) { cx = prevTabStop(cx) }; pendingWrap = false }
            'J' -> eraseDisplay(args.getOrNull(0)?.takeIf { it >= 0 } ?: 0)
            'K' -> eraseLine(args.getOrNull(0)?.takeIf { it >= 0 } ?: 0)
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            'P' -> deleteChars(arg(0, 1))
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            'X' -> eraseChars(arg(0, 1))
            'g' -> clearTabStop(args.getOrNull(0)?.takeIf { it >= 0 } ?: 0)
            'h' -> if (args.contains(4)) insertMode = true
            'l' -> if (args.contains(4)) insertMode = false
            'r' -> setScrollRegion(args.getOrNull(0)?.takeIf { it > 0 } ?: 1, args.getOrNull(1)?.takeIf { it > 0 } ?: rows)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> deviceStatus(args.getOrNull(0) ?: 0)
            'c' -> if ((args.getOrNull(0) ?: 0) == 0) respond("$ESC[?1;2c") // DA: VT100 с AVO
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
            1006 -> mouseSgr = on
            2004 -> bracketedPaste = on
            47, 1047 -> setAltScreen(on, saveRestore = false)
            1049 -> setAltScreen(on, saveRestore = true)
        }
    }

    private fun deviceStatus(code: Int) {
        when (code) {
            5 -> respond("$ESC[0n") // OK
            6 -> { // CPR — позиция курсора, 1-based, относительно origin при DECOM
                val row = (if (originMode) cy - scrollTop else cy) + 1
                respond("$ESC[$row;${cx + 1}R")
            }
        }
    }

    // --- SGR ---------------------------------------------------------------

    private fun applySgr(args: List<Int>) {
        if (args.isEmpty()) { style = TermStyle(); return }
        var i = 0
        while (i < args.size) {
            when (val p = args[i]) {
                0, -1 -> style = TermStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                5, 6 -> style = style.copy(blink = true)
                7 -> style = style.copy(inverse = true)
                8 -> style = style.copy(hidden = true)
                9 -> style = style.copy(strikethrough = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                25 -> style = style.copy(blink = false)
                27 -> style = style.copy(inverse = false)
                28 -> style = style.copy(hidden = false)
                29 -> style = style.copy(strikethrough = false)
                in 30..37 -> style = style.copy(fg = TermColor.Indexed(p - 30))
                38 -> { val (col, used) = extendedColor(args, i); style = style.copy(fg = col); i += used }
                39 -> style = style.copy(fg = TermColor.Default)
                in 40..47 -> style = style.copy(bg = TermColor.Indexed(p - 40))
                48 -> { val (col, used) = extendedColor(args, i); style = style.copy(bg = col); i += used }
                49 -> style = style.copy(bg = TermColor.Default)
                in 90..97 -> style = style.copy(fg = TermColor.Indexed(8 + p - 90))
                in 100..107 -> style = style.copy(bg = TermColor.Indexed(8 + p - 100))
            }
            i++
        }
    }

    /** Разбор 38/48: `5;n` → Indexed(n), `2;r;g;b` → Rgb. Возвращает цвет и число дополнительно съеденных параметров. */
    private fun extendedColor(args: List<Int>, at: Int): Pair<TermColor, Int> = when (args.getOrNull(at + 1)) {
        5 -> (args.getOrNull(at + 2)?.let { TermColor.Indexed(it.coerceIn(0, 255)) } ?: TermColor.Default) to 2
        2 -> {
            val r = args.getOrNull(at + 2)?.coerceIn(0, 255) ?: 0
            val g = args.getOrNull(at + 3)?.coerceIn(0, 255) ?: 0
            val b = args.getOrNull(at + 4)?.coerceIn(0, 255) ?: 0
            TermColor.Rgb(r, g, b) to 4
        }
        else -> TermColor.Default to 0
    }

    // --- Печать и перемещение ---------------------------------------------

    private fun putChar(ch: Char) {
        if (pendingWrap) {
            cx = 0
            lineFeed()
            pendingWrap = false
        }
        val row = grid[cy]
        if (insertMode) {
            row.add(cx, TermCell(ch, style))
            while (row.size > cols) row.removeAt(row.size - 1)
        } else {
            row[cx] = TermCell(ch, style)
        }
        if (cx >= cols - 1) { if (autoWrap) pendingWrap = true } else cx++
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

    /** Перевод 0-based строки в абсолютную с учётом origin-режима (внутри региона). */
    private fun absRow(row: Int): Int =
        if (originMode) (scrollTop + row).coerceIn(scrollTop, scrollBottom) else row.coerceIn(0, rows - 1)

    private fun topLimit() = if (originMode) scrollTop else 0
    private fun bottomLimit() = if (originMode) scrollBottom else rows - 1

    // --- Прокрутка ---------------------------------------------------------

    private fun scrollUp(n: Int) = repeat(n.coerceAtMost(scrollBottom - scrollTop + 1)) {
        val removed = grid.removeAt(scrollTop)
        if (!altScreen && scrollTop == 0) pushScrollback(removed)
        grid.add(scrollBottom, blankRow())
    }

    private fun scrollDown(n: Int) = repeat(n.coerceAtMost(scrollBottom - scrollTop + 1)) {
        grid.removeAt(scrollBottom)
        grid.add(scrollTop, blankRow())
    }

    private fun pushScrollback(row: List<TermCell>) {
        scrollback.addLast(row)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    // --- Стирание / вставка / удаление ------------------------------------

    private fun eraseLine(mode: Int) {
        val row = grid[cy]
        when (mode) {
            0 -> for (c in cx until cols) row[c] = blankCell()
            1 -> for (c in 0..cx.coerceAtMost(cols - 1)) row[c] = blankCell()
            2 -> for (c in 0 until cols) row[c] = blankCell()
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cy + 1 until rows) blankLine(r) }
            1 -> { for (r in 0 until cy) blankLine(r); eraseLine(1) }
            2 -> for (r in 0 until rows) blankLine(r)
            3 -> { for (r in 0 until rows) blankLine(r); scrollback.clear() }
        }
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

    // --- Курсор / сброс / alt-screen --------------------------------------

    private fun saveCursor() {
        savedCx = cx; savedCy = cy; savedStyle = style
    }

    private fun restoreCursor() {
        cx = savedCx.coerceIn(0, cols - 1)
        cy = savedCy.coerceIn(0, rows - 1)
        style = savedStyle
        pendingWrap = false
    }

    private fun setAltScreen(on: Boolean, saveRestore: Boolean) {
        if (on == altScreen) return
        if (on) {
            if (saveRestore) saveCursor()
            altGrid = freshScreen()
            grid = altGrid!!
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
     * DECSCUSR (`CSI Ps SP q`): число задаёт форму и мигание курсора. 0/1 — мигающий блок (дефолт),
     * 2 — блок, 3 — мигающее подчёркивание, 4 — подчёркивание, 5 — мигающая черта, 6 — черта.
     * Нечётные (и 0) мигают, чётные — нет.
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
    }

    private fun reset() {
        primaryGrid = freshScreen()
        altGrid = null
        grid = primaryGrid
        altScreen = false
        scrollback.clear()
        cx = 0; cy = 0
        pendingWrap = false
        style = TermStyle()
        resetRegion()
        tabStops = defaultTabStops(cols)
        originMode = false; insertMode = false; autoWrap = true; cursorVisible = true
        cursorShape = CursorShape.Block; cursorBlink = true
        applicationCursorKeys = false; applicationKeypad = false
        bracketedPaste = false; mouseTracking = MouseTracking.Off; mouseSgr = false
    }

    // --- Табстопы ----------------------------------------------------------

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

    /** TBC: 0 — снять табстоп в текущей колонке, 3 — очистить все. */
    private fun clearTabStop(mode: Int) {
        when (mode) {
            0 -> if (cx in tabStops.indices) tabStops[cx] = false
            3 -> tabStops.fill(false)
        }
    }

    // --- Resize ------------------------------------------------------------

    /** Изменить размер сетки. Сбрасывает регион прокрутки и табстопы, клампит курсор. */
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(1)
        val nr = newRows.coerceAtLeast(1)
        if (nc == cols && nr == rows) return
        resizeGrid(primaryGrid, nc, nr, activePrimary = !altScreen)
        altGrid?.let { resizeGrid(it, nc, nr, activePrimary = false) }
        cols = nc
        rows = nr
        tabStops = defaultTabStops(nc)
        resetRegion()
        cx = cx.coerceIn(0, nc - 1)
        cy = cy.coerceIn(0, nr - 1)
        pendingWrap = false
    }

    private fun resizeGrid(g: MutableList<MutableList<TermCell>>, nc: Int, nr: Int, activePrimary: Boolean) {
        for (row in g) {
            while (row.size > nc) row.removeAt(row.size - 1)
            while (row.size < nc) row.add(TermCell(' '))
        }
        if (g.size > nr) {
            // При сжатии активного основного буфера сдвигаем верх в scrollback, чтобы курсор остался виден.
            if (activePrimary) {
                val keepFromTop = (cy - (nr - 1)).coerceAtLeast(0).coerceAtMost(g.size - nr)
                repeat(keepFromTop) { pushScrollback(g.removeAt(0)); cy-- }
            }
            while (g.size > nr) g.removeAt(g.size - 1)
        } else {
            while (g.size < nr) g.add(MutableList(nc) { TermCell(' ') })
        }
    }

    // --- Фабрики ячеек -----------------------------------------------------

    private fun freshScreen(): MutableList<MutableList<TermCell>> =
        MutableList(rows) { MutableList(cols) { TermCell(' ') } }

    /** Пустая ячейка с текущим фоном (стирание/прокрутка красят фоном current-bg). */
    private fun blankCell() = TermCell(' ', TermStyle(bg = style.bg))

    private fun blankRow() = MutableList(cols) { blankCell() }

    private fun blankLine(r: Int) {
        val row = grid[r]
        for (c in 0 until cols) row[c] = blankCell()
    }

    private fun parseArgs(raw: String): List<Int> {
        if (raw.isEmpty()) return emptyList()
        // Поддержка colon-формы SGR (38:2:...) сведением ':' к ';'.
        return raw.replace(':', ';').split(';').map { it.toIntOrNull() ?: -1 }
    }

    private companion object {
        const val DEFAULT_MAX_SCROLLBACK = 5000
        const val TAB = 8
        val ESC = 27.toChar().toString()

        /** Sentinel «у CSI не было intermediate-байта» (NUL) — отличаем от реального пробела (0x20) DECSCUSR. */
        // NUL через toChar(), а не char-литерал: правило проекта запрещает сырые управляющие
        // байты в исходнике, а \u-escape Edit-инструмент сворачивает в сырой байт. Сравнение
        // редкое (раз на CSI-последовательность), поэтому отсутствие const-инлайна несущественно.
        val NO_INTERMEDIATE = 0.toChar()

        fun defaultTabStops(cols: Int) = BooleanArray(cols) { it % TAB == 0 && it != 0 }
    }
}
