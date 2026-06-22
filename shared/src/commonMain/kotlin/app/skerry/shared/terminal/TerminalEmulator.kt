package app.skerry.shared.terminal

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

/**
 * Стиль подчёркивания (modern SGR `4:x`): помимо классического одиночного — двойное, волнистое,
 * пунктирное и штриховое. [None] — без подчёркивания. Рендер сам рисует линию нужной формы.
 */
enum class UnderlineStyle { None, Single, Double, Curly, Dotted, Dashed }

/**
 * Атрибуты ячейки. Цвета — [TermColor]; флаги покрывают полный набор SGR-атрибутов.
 *
 * Подчёркивание держим как [underlineStyle] (форма) + [underlineColor] (цвет, `SGR 58/59`; [TermColor.Default]
 * означает «брать цвет текста»). Булев [underline] — производное для рендера/тестов, которым достаточно
 * факта наличия подчёркивания.
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
    /** Есть ли подчёркивание любой формы. */
    val underline: Boolean get() = underlineStyle != UnderlineStyle.None
}

/**
 * Ширина ячейки в сетке (для CJK/emoji двойной ширины):
 *  - [Single] — обычная клетка в одну колонку;
 *  - [Wide] — ведущая клетка двухколоночного символа (рендер растягивает глиф на 2 колонки);
 *  - [Continuation] — вторая колонка под широким символом; глиф не рисуется, курсор её перешагивает.
 */
enum class CellWidth { Single, Wide, Continuation }

/**
 * Одна ячейка экрана: отображаемый текст + стиль + ширина. [text] — строка (а не `Char`), чтобы
 * вмещать астральные символы (emoji > U+FFFF как суррогатную пару) и при необходимости комбинируемые
 * последовательности; у [Continuation]-клетки [text] пуст.
 */
data class TermCell(
    val text: String = " ",
    val style: TermStyle = TermStyle(),
    val width: CellWidth = CellWidth.Single,
    /** URI гиперссылки (OSC 8) для этой клетки, либо `null`. Кликабельность/подсветку решает рендер. */
    val hyperlink: String? = null,
) {
    /** Удобный конструктор от одиночного BMP-символа (бланк, ASCII, line-drawing-глиф). */
    constructor(char: Char, style: TermStyle = TermStyle()) : this(char.toString(), style, CellWidth.Single)
}

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
 * @param onClipboardCopy вызывается на OSC 52-запись (декодированный текст) — UI кладёт его в системный
 *   буфер. Запрос ЧТЕНИЯ буфера (OSC 52 с `?`) намеренно игнорируется: содержимое буфера пользователя
 *   не отдаётся недоверенному серверу.
 */
class TerminalEmulator(
    cols: Int = 80,
    rows: Int = 24,
    private val maxScrollback: Int = DEFAULT_MAX_SCROLLBACK,
    private val respond: (String) -> Unit = {},
    private val onBell: () -> Unit = {},
    private val onClipboardCopy: (String) -> Unit = {},
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

    // Последний напечатанный codepoint — для REP (CSI Ps b): nano 9.0/ncurses заполняют им полосы
    // (reverse title-бар), вместо литеральных пробелов. null до первой печати. Хранится как Int
    // (codepoint), а не Char, чтобы корректно повторять и астральные символы.
    private var lastPrintedCp: Int? = null

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

    // Сохранённый курсор (DECSC/DECRC; также используется alt-screen 1049). По VT220 DECSC
    // сохраняет и активный графический набор — иначе текст после DECRC мисрендерится в line-drawing.
    private var savedCx = 0
    private var savedCy = 0
    private var savedStyle = TermStyle()
    private var savedG0LineDrawing = false
    private var savedG1LineDrawing = false
    private var savedGlG1 = false

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

    /** Focus reporting (DEC 1004): когда включён, UI шлёт ESC[I при фокусе окна и ESC[O при потере. */
    var focusReporting: Boolean = false
        private set
    var title: String = ""
        private set

    // Активная гиперссылка OSC 8 (URI) — вешается на печатаемые клетки до закрытия пустым URI.
    private var currentHyperlink: String? = null

    // Переопределения палитры (OSC 4): index 0..255 → Rgb. Пусто = используются дефолты темы.
    // Рендер консультируется с этим слоем при разрешении TermColor.Indexed.
    private val paletteOverrides = HashMap<Int, TermColor.Rgb>()

    // Кэш неизменяемого снимка палитры: пересобирается лишь при мутации (paletteDirty), чтобы
    // publishSnapshot() отдавал ОДНУ И ТУ ЖЕ ссылку между кадрами — иначе referential-equality Compose
    // считал бы state «грязным» и аллокация HashMap шла бы на каждый feed.
    private var paletteCache: Map<Int, TermColor.Rgb> = emptyMap()
    private var paletteDirty = false

    /** Снимок переопределений палитры (OSC 4) для рендера; пуст, пока приложение их не задавало. */
    fun paletteSnapshot(): Map<Int, TermColor.Rgb> {
        if (paletteDirty) {
            paletteCache = if (paletteOverrides.isEmpty()) emptyMap() else HashMap(paletteOverrides)
            paletteDirty = false
        }
        return paletteCache
    }

    /** Текущее переопределение цвета палитры `index` (OSC 4), либо `null`. */
    fun paletteOverride(index: Int): TermColor.Rgb? = paletteOverrides[index]

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

    // Графические наборы G0/G1 (DEC). true = DEC Special Graphics (line-drawing), false = US-ASCII.
    // glG1 — какой набор сейчас в GL: false=G0, true=G1; переключают SO (0x0e) / SI (0x0f).
    private var g0LineDrawing = false
    private var g1LineDrawing = false
    private var glG1 = false
    // Куда применить следующий designation-байт: 0=G0 (`ESC (`), 1=G1 (`ESC )`), -1=прочее (поглотить).
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
            b == 0x0e -> glG1 = true  // SO — активировать G1 в GL
            b == 0x0f -> glG1 = false // SI — вернуть G0 в GL
            b < 0x20 -> {} // прочие C0 — игнор
            b < 0x80 -> putCodePoint(mapGlyph(b).code)
            else -> beginUtf8(b)
        }
    }

    /** Применяет активный графический набор: в DEC Special Graphics ASCII 0x60..0x7e → box-drawing. */
    private fun mapGlyph(b: Int): Char {
        val lineDrawing = if (glG1) g1LineDrawing else g0LineDrawing
        return if (lineDrawing && b in 0x60..0x7e) DEC_SPECIAL_GRAPHICS[b - 0x60] else b.toChar()
    }

    /** Обрабатывает байт после `ESC (`/`ESC )`/прочих: `0` = line-drawing, иначе US-ASCII. */
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
            // Кап длины: недоверенный сервер мог бы слать бесконечный OSC (особенно OSC 52 base64) и
            // раздуть кучу до OOM. Сверх лимита байты роняем, но парсим до терминатора (хвост не утечёт
            // в Ground как текст). Усечённый OSC 52 → битый base64 → тихо отбрасывается в setClipboard.
            else -> if (osc.length < MAX_OSC_LEN) osc.append((b and 0xff).toChar())
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
        // code-only OSC (без ';') допустим — например `OSC 104 ST` (сброс всей палитры).
        val code = (if (sep < 0) s else s.substring(0, sep)).toIntOrNull() ?: return
        val rest = if (sep < 0) "" else s.substring(sep + 1)
        when (code) {
            0, 1, 2 -> title = rest
            4 -> setPalette(rest)     // OSC 4 ; index ; spec [ ; index ; spec ... ]
            8 -> setHyperlink(rest)   // OSC 8 ; params ; URI
            52 -> setClipboard(rest)  // OSC 52 ; Pc ; Pd
            104 -> resetPalette(rest) // OSC 104 [ ; index ... ]  (пусто = вся палитра)
        }
    }

    /**
     * OSC 52: `Pc;Pd`. Pd — base64 текста для записи в буфер обмена (через [onClipboardCopy]).
     * Pd == `?` — запрос ЧТЕНИЯ буфера сервером; намеренно игнорируем (буфер пользователя не утекает).
     * Невалидный base64 тихо отбрасываем.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun setClipboard(rest: String) {
        val data = rest.substringAfter(';', "")
        if (data.isEmpty() || data.startsWith("?")) return // пусто либо запрос чтения — буфер не отдаём
        val text = runCatching { Base64.decode(data).decodeToString() }.getOrNull() ?: return
        onClipboardCopy(text)
    }

    /** OSC 4: пары `index;spec`. spec `?` (запрос) пропускаем — отвечать нечем (цвета знает рендер). */
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

    /** OSC 104: без аргументов — сброс всей палитры; иначе сброс перечисленных индексов. */
    private fun resetPalette(rest: String) {
        if (rest.isBlank()) { paletteOverrides.clear(); paletteDirty = true; return }
        for (part in rest.split(';')) part.toIntOrNull()?.let { if (paletteOverrides.remove(it) != null) paletteDirty = true }
    }

    /**
     * Разбор X11/xterm color-spec в [TermColor.Rgb]: `rgb:R/G/B` (по 1..4 hex-цифры на компоненту,
     * масштабируются в 0..255) и `#RGB`/`#RRGGBB`/`#RRRRGGGGBBBB`. Прочее (`?`, имена цветов) → null.
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
            // X11 #-форма: 3 (#RGB), 6 (#RRGGBB) или 12 (#RRRRGGGGBBBB) hex-цифр; прочие длины — мусор.
            if (hex.length != 3 && hex.length != 6 && hex.length != 12) return null
            val n = hex.length / 3
            val r = scaleHex(hex.substring(0, n)) ?: return null
            val g = scaleHex(hex.substring(n, 2 * n)) ?: return null
            val b = scaleHex(hex.substring(2 * n, 3 * n)) ?: return null
            return TermColor.Rgb(r, g, b)
        }
        return null
    }

    /** hex-компонента 1..4 цифр → 0..255 масштабированием по разрядности (`ff`→255, `ffff`→255, `8080`→128). */
    private fun scaleHex(h: String): Int? {
        if (h.isEmpty() || h.length > 4) return null
        val v = h.toIntOrNull(16) ?: return null
        val max = (1 shl (4 * h.length)) - 1
        return (v * 255 + max / 2) / max
    }

    /**
     * OSC 8: `params;URI` — params (например `id=...`) игнорируем, берём URI после первого `;`.
     * Пустой URI закрывает текущую ссылку. Активный URI вешается на последующие печатаемые клетки.
     */
    private fun setHyperlink(rest: String) {
        val uri = rest.substringAfter(';', "").trim()
        currentHyperlink = uri.ifEmpty { null }
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
            'm' -> applySgr(parseSgrParams(raw))
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
            'b' -> repeatLastChar(arg(0, 1))
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
            1004 -> focusReporting = on
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

    /**
     * Применяет SGR. [params] — список параметров, разделённых `;`; каждый параметр — массив своих
     * `:`-субпараметров (modern colon-форма: `4:3`, `38:2::r:g:b`, `58:5:n`). Расширенные цвета (38/48/58)
     * понимают обе формы: colon-субпараметры внутри одного параметра ИЛИ legacy-форму с `;`,
     * где тип и компоненты идут отдельными параметрами (тогда они доедаются из хвоста списка).
     */
    private fun applySgr(params: List<IntArray>) {
        if (params.isEmpty()) { style = TermStyle(); return }
        var i = 0
        while (i < params.size) {
            val param = params[i]
            when (val p = param.firstOrNull() ?: 0) {
                0 -> style = TermStyle() // пустые субпараметры parseSgrParams даёт как 0, не -1
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underlineStyle = underlineFromSub(param.getOrElse(1) { 1 }))
                5, 6 -> style = style.copy(blink = true)
                7 -> style = style.copy(inverse = true)
                8 -> style = style.copy(hidden = true)
                9 -> style = style.copy(strikethrough = true)
                21 -> style = style.copy(underlineStyle = UnderlineStyle.Double)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underlineStyle = UnderlineStyle.None)
                25 -> style = style.copy(blink = false)
                27 -> style = style.copy(inverse = false)
                28 -> style = style.copy(hidden = false)
                29 -> style = style.copy(strikethrough = false)
                in 30..37 -> style = style.copy(fg = TermColor.Indexed(p - 30))
                38 -> { val (col, used) = extendedColor(params, i); style = style.copy(fg = col); i += used }
                39 -> style = style.copy(fg = TermColor.Default)
                in 40..47 -> style = style.copy(bg = TermColor.Indexed(p - 40))
                48 -> { val (col, used) = extendedColor(params, i); style = style.copy(bg = col); i += used }
                49 -> style = style.copy(bg = TermColor.Default)
                58 -> { val (col, used) = extendedColor(params, i); style = style.copy(underlineColor = col); i += used }
                59 -> style = style.copy(underlineColor = TermColor.Default)
                in 90..97 -> style = style.copy(fg = TermColor.Indexed(8 + p - 90))
                in 100..107 -> style = style.copy(bg = TermColor.Indexed(8 + p - 100))
            }
            i++
        }
    }

    private fun underlineFromSub(sub: Int): UnderlineStyle = when (sub) {
        0 -> UnderlineStyle.None
        2 -> UnderlineStyle.Double
        3 -> UnderlineStyle.Curly
        4 -> UnderlineStyle.Dotted
        5 -> UnderlineStyle.Dashed
        else -> UnderlineStyle.Single // 1 и неизвестные подстили
    }

    /**
     * Разбор расширенного цвета (38/48/58) в обеих формах. Возвращает цвет и число ДОПОЛНИТЕЛЬНЫХ
     * `;`-параметров, съеденных из [params] начиная с [at] (для colon-формы — 0, цвет уже внутри параметра).
     */
    private fun extendedColor(params: List<IntArray>, at: Int): Pair<TermColor, Int> {
        val param = params[at]
        // Colon-форма: тип и компоненты — субпараметры внутри одного параметра (38:2:..., 38:5:n).
        if (param.size > 1) return colonColor(param) to 0
        // Legacy `;`-форма: следующий параметр — тип, далее компоненты отдельными параметрами.
        fun nextFirst(k: Int) = params.getOrNull(at + k)?.firstOrNull()
        return when (nextFirst(1)) {
            5 -> (nextFirst(2)?.let { TermColor.Indexed(it.coerceIn(0, 255)) } ?: TermColor.Default) to 2
            2 -> {
                val r = (nextFirst(2) ?: 0).coerceIn(0, 255)
                val g = (nextFirst(3) ?: 0).coerceIn(0, 255)
                val b = (nextFirst(4) ?: 0).coerceIn(0, 255)
                TermColor.Rgb(r, g, b) to 4
            }
            else -> TermColor.Default to 0
        }
    }

    /**
     * Цвет из colon-субпараметров одного параметра: `[2, cs?, r, g, b]` → Rgb (необязательное поле
     * colorspace при 6+ элементах пропускаем), `[5, n]` → Indexed. Первый элемент — селектор 38/48/58.
     */
    private fun colonColor(param: IntArray): TermColor = when (param.getOrElse(1) { -1 }) {
        5 -> TermColor.Indexed(param.getOrElse(2) { 0 }.coerceIn(0, 255))
        2 -> {
            val base = if (param.size >= 6) 3 else 2 // 38:2:cs:r:g:b vs 38:2:r:g:b
            TermColor.Rgb(
                param.getOrElse(base) { 0 }.coerceIn(0, 255),
                param.getOrElse(base + 1) { 0 }.coerceIn(0, 255),
                param.getOrElse(base + 2) { 0 }.coerceIn(0, 255),
            )
        }
        else -> TermColor.Default
    }

    // --- Печать и перемещение ---------------------------------------------

    private fun putCodePoint(cp: Int) {
        val w = charWidth(cp)
        if (pendingWrap) {
            cx = 0
            lineFeed()
            pendingWrap = false
        }
        // Широкий символ не помещается в последнюю колонку: при автопереносе уходим на новую строку,
        // иначе размещаем его как одиночный в последней клетке (нет места под continuation).
        if (w == 2 && cx >= cols - 1 && autoWrap) { cx = 0; lineFeed() }

        val text = codePointToString(cp)
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

    /** REP (CSI Ps b): повторить последний печатный символ Ps раз. Кламп — не больше площади экрана. */
    private fun repeatLastChar(n: Int) {
        val cp = lastPrintedCp ?: return
        repeat(n.coerceIn(1, cols * rows)) { putCodePoint(cp) }
    }

    /**
     * Ширина символа в колонках по упрощённой таблице East Asian Width + emoji: 2 для CJK/Hangul/
     * kana/fullwidth-форм и эмодзи-блоков, иначе 1. Комбинируемые/нулевой ширины пока считаем за 1
     * (отдельный слой объединения — позже).
     */
    private fun charWidth(cp: Int): Int = if (
        cp in 0x1100..0x115F ||              // Hangul Jamo
        cp in 0x2E80..0x303E ||              // CJK радикалы, Kangxi, punctuation
        cp in 0x3041..0x33FF ||              // Hiragana/Katakana, CJK symbols
        cp in 0x3400..0x4DBF ||              // CJK Ext A
        cp in 0x4E00..0x9FFF ||              // CJK Unified
        cp in 0xA000..0xA4CF ||              // Yi
        cp in 0xAC00..0xD7A3 ||              // Hangul syllables
        cp in 0xF900..0xFAFF ||              // CJK compatibility
        cp in 0xFE10..0xFE19 ||              // vertical forms
        cp in 0xFE30..0xFE6F ||              // CJK compat forms
        cp in 0xFF00..0xFF60 ||              // fullwidth forms
        cp in 0xFFE0..0xFFE6 ||              // fullwidth signs
        cp in 0x1F300..0x1FAFF ||            // emoji, symbols, pictographs
        cp in 0x20000..0x3FFFD               // CJK Ext B и далее
    ) 2 else 1

    /** Codepoint → строка: BMP — один Char, астральный — суррогатная пара, невалидный — U+FFFD. */
    private fun codePointToString(cp: Int): String = when {
        cp in 0..0xFFFF && cp !in 0xD800..0xDFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
        else -> "�"
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
        g0LineDrawing = false; g1LineDrawing = false; glG1 = false
        pendingDesignation = -1
    }

    private fun reset() {
        primaryGrid = freshScreen()
        altGrid = null
        grid = primaryGrid
        altScreen = false
        scrollback.clear()
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
        cursorShape = CursorShape.Block; cursorBlink = true
        applicationCursorKeys = false; applicationKeypad = false
        bracketedPaste = false; mouseTracking = MouseTracking.Off; mouseSgr = false; focusReporting = false
        g0LineDrawing = false; g1LineDrawing = false; glG1 = false
        pendingDesignation = -1
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

    /**
     * Пустая ячейка с текущим фоном — background-color-erase (BCE): стирание и прокрутка красят
     * ячейки ТЕКУЩИМ SGR-фоном, включая reverse-video (тогда «фоном» становится цвет текста).
     * Несём fg/bg/inverse: ncurses (nano/htop) дозаполняет reverse-полосы через `ESC[K`, полагаясь
     * на BCE — без флага inverse хвост строки рисовался бы обычным фоном. Глифовые атрибуты
     * (bold/underline/strike) у пустой ячейки не несём: xterm их при стирании не применяет.
     */
    private fun blankCell() = TermCell(' ', TermStyle(fg = style.fg, bg = style.bg, inverse = style.inverse))

    private fun blankRow() = MutableList(cols) { blankCell() }

    private fun blankLine(r: Int) {
        val row = grid[r]
        for (c in 0 until cols) row[c] = blankCell()
    }

    private fun parseArgs(raw: String): List<Int> {
        if (raw.isEmpty()) return emptyList()
        // Не-SGR CSI (курсор, DECSCUSR, режимы) colon не несут; на всякий случай сводим ':' к ';'.
        // SGR разбирает отдельный colon-aware parseSgrParams (субпараметры там значимы).
        return raw.replace(':', ';').split(';').map { it.toIntOrNull() ?: -1 }
    }

    /**
     * Разбор SGR-параметров с сохранением структуры субпараметров: каждый `;`-параметр → массив его
     * `:`-частей (modern colon-форма). Пустые части → 0 (например, поле colorspace в `58:2::r:g:b`).
     */
    private fun parseSgrParams(raw: String): List<IntArray> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { part ->
            part.split(':').map { it.toIntOrNull() ?: 0 }.toIntArray()
        }
    }

    private companion object {
        const val DEFAULT_MAX_SCROLLBACK = 5000

        /** Потолок длины OSC-строки (защита от OOM на недоверенном выводе); 4 MiB с запасом под OSC 52. */
        const val MAX_OSC_LEN = 4 * 1024 * 1024
        const val TAB = 8
        val ESC = 27.toChar().toString()

        /** Sentinel «у CSI не было intermediate-байта» (NUL) — отличаем от реального пробела (0x20) DECSCUSR. */
        // NUL через toChar(), а не char-литерал: правило проекта запрещает сырые управляющие
        // байты в исходнике, а \u-escape Edit-инструмент сворачивает в сырой байт. Сравнение
        // редкое (раз на CSI-последовательность), поэтому отсутствие const-инлайна несущественно.
        val NO_INTERMEDIATE = 0.toChar()

        /**
         * DEC Special Graphics (VT100 line-drawing): ASCII 0x60..0x7e → Unicode-глифы.
         * Индекс = code - 0x60. Уголки/тройники/линии (j..x) — то, чем tmux/mc/htop рисуют рамки;
         * прочие (диамант, затенение, скан-линии, ≤≥π≠£·) добиты для полноты набора.
         */
        const val DEC_SPECIAL_GRAPHICS =
            "◆▒␉␌␍␊°±" + // ` a b c d e f g
            "␤␋┘┐┌└┼⎺" + // h i j k l m n o
            "⎻─⎼⎽├┤┴┬" + // p q r s t u v w
            "│≤≥π≠£·"          // x y z { | } ~

        fun defaultTabStops(cols: Int) = BooleanArray(cols) { it % TAB == 0 && it != 0 }
    }
}
