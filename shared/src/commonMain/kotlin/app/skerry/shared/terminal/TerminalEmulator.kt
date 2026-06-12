package app.skerry.shared.terminal

/** 16 ANSI-цветов + «по умолчанию» (берётся из темы на стороне UI). */
enum class TermColor {
    Default,
    Black, Red, Green, Yellow, Blue, Magenta, Cyan, White,
    BrightBlack, BrightRed, BrightGreen, BrightYellow, BrightBlue, BrightMagenta, BrightCyan, BrightWhite,
}

/** Атрибуты ячейки. UI-независимы: цвета — [TermColor], не Compose Color. */
data class TermStyle(
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
)

/** Одна ячейка экрана: символ + его стиль. */
data class TermCell(val char: Char, val style: TermStyle = TermStyle())

/**
 * Минимальный VT/ANSI-эмулятор: строит модель экрана из потока байтов PTY. Это устройство-
 * независимая логика (без Compose) — UI рендерит [lines]/[cursorRow]/[cursorCol].
 *
 * Парсер — байтовая state-machine, держащая состояние между вызовами [feed], поэтому корректно
 * переживает разрезанные между чанками escape-последовательности И многобайтовый UTF-8.
 *
 * Покрытый сейчас минимум (под интерактивный shell): печатные символы, CR/LF/BS/TAB, SGR
 * (цвета 30–37/90–97, фон 40–47/100–107, bold, reset; 38/48 — поглощаются как Default),
 * перемещение курсора в строке (CSI A/B/C/D/G/H), стирание строки (CSI K) и экрана (CSI J).
 * Полноэкранное позиционирование (vim/htop) и выделение — следующие шаги; неизвестные CSI/OSC
 * безопасно поглощаются, поэтому артефактов в выводе не остаётся.
 *
 * Модель — строко-ориентированная со scrollback (не фиксированная сетка): перенос длинных строк
 * делает сам shell по размеру PTY, а PTY транслирует LF→CRLF, поэтому LF опускает курсор, не
 * сбрасывая колонку (CR делает это отдельно). Глубина истории ограничена [maxLines].
 */
class TerminalEmulator(private val maxLines: Int = DEFAULT_MAX_LINES) {

    private val buffer = ArrayDeque<MutableList<TermCell>>().apply { addLast(mutableListOf()) }

    /** Снимок экрана для отрисовки (строки сверху вниз). Живой вид — снимок делает вызывающий. */
    val lines: List<List<TermCell>> get() = buffer

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    private var style = TermStyle()

    private var parser = State.Ground
    private val params = StringBuilder()

    // Сборка многобайтового UTF-8 в Ground.
    private var utf8Remaining = 0
    private var utf8CodePoint = 0

    private enum class State { Ground, Esc, Csi, Osc, OscEsc, Charset, Utf8 }

    fun feed(data: ByteArray) {
        for (b in data) process(b.toInt() and 0xff)
    }

    private fun process(b: Int) {
        when (parser) {
            State.Ground -> ground(b)
            State.Utf8 -> utf8(b)
            State.Esc -> esc(b)
            State.Csi -> csi(b)
            State.Osc -> osc(b)
            State.OscEsc -> oscEsc(b)
            State.Charset -> parser = State.Ground // поглощаем один байт (designation набора)
        }
    }

    private fun ground(b: Int) {
        when {
            b == 0x1b -> parser = State.Esc
            b == 0x0d -> cursorCol = 0
            b == 0x0a || b == 0x0b || b == 0x0c -> lineFeed()
            b == 0x08 -> if (cursorCol > 0) cursorCol--
            b == 0x09 -> cursorCol = ((cursorCol / TAB) + 1) * TAB
            b == 0x07 -> {} // BEL
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
            else -> {
                putChar('�')
                return
            }
        }
        utf8Remaining = len - 1
        utf8CodePoint = init
        parser = State.Utf8
    }

    private fun utf8(b: Int) {
        if (b and 0xC0 != 0x80) {
            // Битая последовательность: ставим замену и переразбираем текущий байт в Ground.
            putChar('�')
            parser = State.Ground
            process(b)
            return
        }
        utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
        if (--utf8Remaining == 0) {
            parser = State.Ground
            if (utf8CodePoint <= 0xFFFF) putChar(utf8CodePoint.toChar()) else putChar('�')
        }
    }

    private fun esc(b: Int) {
        parser = when (b.toChar()) {
            '[' -> {
                params.clear()
                State.Csi
            }
            ']' -> State.Osc
            '(', ')' -> State.Charset
            else -> State.Ground
        }
    }

    private fun csi(b: Int) {
        when {
            b in 0x30..0x3f -> params.append(b.toChar()) // цифры, ';', '?', приватные маркеры
            b in 0x20..0x2f -> {} // промежуточные байты — игнор
            b in 0x40..0x7e -> {
                dispatchCsi(b.toChar())
                parser = State.Ground
            }
            else -> parser = State.Ground
        }
    }

    private fun osc(b: Int) {
        when (b) {
            0x07 -> parser = State.Ground // BEL — конец OSC
            0x1b -> parser = State.OscEsc // возможно ST (ESC \)
        }
    }

    private fun oscEsc(b: Int) {
        // ST = ESC \ ; в любом случае выходим из OSC.
        parser = State.Ground
        if (b != '\\'.code) process(b)
    }

    private fun dispatchCsi(final: Char) {
        val private = params.isNotEmpty() && params[0] == '?'
        if (private) return // приватные режимы (показ курсора, alt-screen и т.п.) — игнор
        val args = parseParams()
        fun arg(i: Int, default: Int) = args.getOrNull(i)?.takeIf { it >= 0 } ?: default
        when (final) {
            'm' -> applySgr(args)
            'A' -> cursorRow = (cursorRow - arg(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + arg(0, 1)).coerceAtMost(buffer.size - 1)
            'C' -> cursorCol += arg(0, 1)
            'D' -> cursorCol = (cursorCol - arg(0, 1)).coerceAtLeast(0)
            'G' -> cursorCol = (arg(0, 1) - 1).coerceAtLeast(0)
            'H', 'f' -> {
                cursorRow = (arg(0, 1) - 1).coerceIn(0, buffer.size - 1)
                cursorCol = (arg(1, 1) - 1).coerceAtLeast(0)
            }
            'K' -> eraseLine(arg(0, 0))
            'J' -> eraseScreen(arg(0, 0))
        }
    }

    /** Разбор параметров CSI в список целых; пустой параметр → -1 (заменяется default'ом). */
    private fun parseParams(): List<Int> {
        val raw = if (params.isNotEmpty() && params[0] == '?') params.substring(1) else params.toString()
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { it.toIntOrNull() ?: -1 }
    }

    private fun applySgr(args: List<Int>) {
        if (args.isEmpty()) {
            style = TermStyle()
            return
        }
        var i = 0
        while (i < args.size) {
            when (val p = args[i]) {
                0, -1 -> style = TermStyle()
                1 -> style = style.copy(bold = true)
                22 -> style = style.copy(bold = false)
                in 30..37 -> style = style.copy(fg = basic(p - 30))
                38 -> {
                    style = style.copy(fg = TermColor.Default)
                    i += skipExtendedColor(args, i)
                }
                39 -> style = style.copy(fg = TermColor.Default)
                in 40..47 -> style = style.copy(bg = basic(p - 40))
                48 -> {
                    style = style.copy(bg = TermColor.Default)
                    i += skipExtendedColor(args, i)
                }
                49 -> style = style.copy(bg = TermColor.Default)
                in 90..97 -> style = style.copy(fg = bright(p - 90))
                in 100..107 -> style = style.copy(bg = bright(p - 100))
                else -> {} // dim/italic/underline и пр. — пока игнорируем
            }
            i++
        }
    }

    /** 38/48: сколько дополнительных параметров (5;n или 2;r;g;b) пропустить за маркером. */
    private fun skipExtendedColor(args: List<Int>, at: Int): Int = when (args.getOrNull(at + 1)) {
        5 -> 2
        2 -> 4
        else -> 0
    }

    private fun basic(n: Int) = TermColor.entries[TermColor.Black.ordinal + n]

    private fun bright(n: Int) = TermColor.entries[TermColor.BrightBlack.ordinal + n]

    private fun putChar(ch: Char) {
        val line = buffer[cursorRow]
        while (line.size <= cursorCol) line.add(TermCell(' ', style))
        line[cursorCol] = TermCell(ch, style)
        cursorCol++
    }

    private fun lineFeed() {
        cursorRow++
        if (cursorRow >= buffer.size) {
            buffer.addLast(mutableListOf())
            if (buffer.size > maxLines) {
                buffer.removeFirst()
                cursorRow--
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val line = buffer[cursorRow]
        when (mode) {
            0 -> while (line.size > cursorCol) line.removeAt(line.size - 1)
            1 -> for (c in 0 until minOf(cursorCol + 1, line.size)) line[c] = TermCell(' ', style)
            2 -> line.clear()
        }
    }

    private fun eraseScreen(mode: Int) {
        when (mode) {
            0 -> {
                while (buffer.size > cursorRow + 1) buffer.removeLast()
                eraseLine(0)
            }
            else -> { // 1/2/3 — для строко-ориентированной модели сводим к полной очистке
                buffer.clear()
                buffer.addLast(mutableListOf())
                cursorRow = 0
                cursorCol = 0
            }
        }
    }

    private companion object {
        const val TAB = 8
        const val DEFAULT_MAX_LINES = 5000
    }
}
