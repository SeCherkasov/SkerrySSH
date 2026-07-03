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

/**
 * Одна строка сетки: ячейки + флаг [wrapped]. `wrapped == true` означает «мягкий перенос» — строку
 * оборвал автоперенос (DECAWM) и она логически продолжается на следующей; honest `\n` оставляет
 * `false`. Reflow при ресайзе склеивает соседние wrapped-строки в логическую и переразбивает её по
 * новой ширине. Делегирует [MutableList], поэтому весь код сетки работает со строкой как со списком
 * ячеек, не зная про флаг.
 */
class TermRow(
    private val cells: MutableList<TermCell>,
    var wrapped: Boolean = false,
) : MutableList<TermCell> by cells

/** Режим репортинга мыши в приложение (DEC private modes). Кодировку выбирает [TerminalEmulator.mouseSgr]. */
enum class MouseTracking { Off, X10, Normal, ButtonEvent, AnyEvent }

/** Форма курсора (DECSCUSR `CSI Ps SP q`): блок, подчёркивание или вертикальная черта. */
enum class CursorShape { Block, Underline, Bar }

/** Дефолтная глубина scrollback эмулятора (строк) — базовое значение/страховка, когда настройка не задана. */
const val DEFAULT_MAX_SCROLLBACK = 5000

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
 * (EL/ED; `ED 2`/`ED 3` уносят экран в scrollback, историю не вытирают — clear/Ctrl+L её хранят);
 * вставка/удаление (ICH/DCH/ECH/IL/DL/SU/SD);
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
    maxScrollback: Int = DEFAULT_MAX_SCROLLBACK,
    // Форма/мигание курсора ПО УМОЛЧАНИЮ (пользовательская настройка «Стиль курсора»). Действуют, пока
    // приложение не задало своё через DECSCUSR; RIS (полный сброс) возвращает именно к ним, а не к
    // жёсткому xterm-дефолту. Значения по умолчанию (блок + мигание) сохраняют прежнее поведение.
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

    // Глубина scrollback (настройка «Буфер прокрутки»). Не val: её можно сменить на лету у уже
    // открытой сессии через [applyMaxScrollback] — при уменьшении лишние старые строки сразу
    // обрезаются, при увеличении новые строки просто дольше держатся.
    private var maxScrollback: Int = maxScrollback.coerceAtLeast(0)

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

    // Пользовательский дефолт курсора (настройка «Стиль курсора»): к нему возвращает RIS и его же
    // можно сменить на лету у открытой сессии через [applyCursorDefault]. Отдельно от текущих
    // [cursorShape]/[cursorBlink], которые приложение может перебить своим DECSCUSR.
    private var defaultCursorShape: CursorShape = initialCursorShape
    private var defaultCursorBlink: Boolean = initialCursorBlink

    /** Форма курсора (DECSCUSR). Стартует с настройки [initialCursorShape] (дефолт — блок, как у xterm). */
    var cursorShape: CursorShape = initialCursorShape
        private set

    /** Должен ли курсор мигать (DECSCUSR steady/blink). Стартует с настройки [initialCursorBlink] (дефолт — мигает). */
    var cursorBlink: Boolean = initialCursorBlink
        private set

    /**
     * Сменить пользовательский дефолт курсора на лету (настройка изменилась при уже открытой сессии):
     * применяется и к текущему курсору немедленно, и к значению, к которому вернёт RIS. Приложение всё
     * ещё может перебить форму своим DECSCUSR позже. Вызывать из корутины-владельца эмулятора.
     */
    fun applyCursorDefault(shape: CursorShape, blink: Boolean) {
        defaultCursorShape = shape
        defaultCursorBlink = blink
        cursorShape = shape
        cursorBlink = blink
    }

    /**
     * Сменить глубину scrollback на лету (настройка «Буфер прокрутки» изменилась при уже открытой
     * сессии). При уменьшении лишние старые строки сразу обрезаются с начала истории. Вызывать из
     * корутины-владельца эмулятора.
     */
    fun applyMaxScrollback(lines: Int) {
        maxScrollback = lines.coerceAtLeast(0)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
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

    /** Focus reporting (DEC 1004): когда включён, UI шлёт ESC[I при фокусе окна и ESC[O при потере. */
    var focusReporting: Boolean = false
        private set
    var title: String = ""
        private set

    // Стек заголовка окна (XTWINOPS CSI 22/23 t): vim/tmux сохраняют заголовок при входе и
    // восстанавливают при выходе. Кап от недоверенного сервера, который мог бы пушить без pop.
    private val titleStack = ArrayDeque<String>()

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

    // --- Парсер ------------------------------------------------------------

    private enum class State { Ground, Esc, Csi, Osc, OscEsc, Consume, Utf8, StrSeq, StrSeqEsc }

    private var parser = State.Ground
    private val params = StringBuilder()

    // Промежуточный байт CSI (0x20..0x2f). NUL = «не было»: отличаем от РЕАЛЬНОГО пробельного
    // intermediate (0x20) у DECSCUSR (`CSI Ps SP q`) — иначе форму курсора не отличить от обычного CSI.
    private var csiIntermediate = NO_INTERMEDIATE
    private val osc = StringBuilder()

    // Тело строковой последовательности (DCS/APC/PM/SOS) — копим до ST (ESC\) или BEL. [strSeqIsDcs]
    // отличает DCS (его разбираем: XTGETTCAP) от APC/PM/SOS (поглощаем целиком: kitty graphics и пр.).
    private val strSeq = StringBuilder()
    private var strSeqIsDcs = false

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
            'P' -> { strSeq.clear(); strSeqIsDcs = true; parser = State.StrSeq }       // DCS (sixel/DECRQSS/XTGETTCAP)
            'X', '^', '_' -> { strSeq.clear(); strSeqIsDcs = false; parser = State.StrSeq } // SOS/PM/APC (kitty graphics)
            else -> parser = State.Ground
        }
    }

    private fun csi(b: Int) {
        when {
            // Кап длины: сверх лимита цифры/разделители роняем, но парсим до финального байта (хвост
            // не утечёт в Ground как текст). Реальные CSI — десятки байт, так что усечение безвредно.
            b in 0x30..0x3f -> if (params.length < MAX_CSI_PARAMS_LEN) params.append(b.toChar()) // цифры, ';', ':', маркеры ?<=>
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
            // C0/C1/DEL из заголовка вырезаем: сервер не должен искажать UI вкладки или
            // протаскивать управляющие байты в потребителей строки (логи и т.п.).
            0, 1, 2 -> title = rest.filter { it.code in 0x20..0x7e || it.code >= 0xa0 }
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
        if (text.length > MAX_CLIPBOARD_LEN) return // анти-флуд: сервер не льёт мегабайты в системный буфер
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
        // Кап длины: URI тиражируется в каждую печатаемую клетку, мегабайтный URI раздул бы grid.
        val uri = rest.substringAfter(';', "").trim().take(MAX_HYPERLINK_LEN)
        currentHyperlink = uri.ifEmpty { null }
    }

    // --- Строковые последовательности (DCS / APC / PM / SOS) ----------------

    private fun strSeqByte(b: Int) {
        when (b) {
            // BEL как терминатор — нестандартное послабление (по ECMA-48 DCS/APC/PM/SOS терминирует
            // только ST), удобно для приложений, шлющих BEL. ВНИМАНИЕ: при Phase-3 sixel это убрать —
            // 0x07 может встретиться в бинарных graphics-данных и оборвать тело раньше времени.
            0x07 -> { finishStrSeq(); parser = State.Ground }
            0x1b -> parser = State.StrSeqEsc                        // возможно ST (ESC \)
            // Кап длины переиспользует MAX_OSC_LEN (4 MiB) — с запасом под будущий потоковый sixel;
            // для текущего XTGETTCAP хватило бы килобайтов. Защита от бесконечного DCS → OOM.
            else -> if (strSeq.length < MAX_OSC_LEN) strSeq.append((b and 0xff).toChar())
        }
    }

    private fun strSeqEsc(b: Int) {
        finishStrSeq()
        parser = State.Ground
        // process(b) безопасен даже при b==ESC: strSeq уже очищен в finishStrSeq, повторного flush не будет.
        if (b != '\\'.code) process(b) // не ST — ESC начинает новую последовательность
    }

    /**
     * Завершение строковой последовательности. APC/PM/SOS поглощаем целиком (тело экран не трогает —
     * так kitty graphics, оконные сообщения и т.п. не текут мусором). DCS дополнительно разбираем на
     * XTGETTCAP (`+q`); прочие DCS (sixel `q…`, DECRQSS `$q…`) сейчас просто поглощаются — рендер
     * изображений отложен в Phase 3.
     */
    private fun finishStrSeq() {
        if (strSeqIsDcs) {
            val s = strSeq.toString()
            if (s.startsWith("+q")) replyXtGetTcap(s.substring(2))
        }
        strSeq.clear()
    }

    /**
     * XTGETTCAP (`DCS + q <hex-name>[;…] ST`): на каждое запрошенное имя отвечаем DCS-ответом —
     * `DCS 1 + r <name>=<hex-value> ST` для известных, `DCS 0 + r <name> ST` для неизвестных
     * (имена/значения в hex, как в terminfo). Заявляем себя как xterm-256color, чтобы приложения
     * включали 256 цветов / truecolor.
     *
     * Безопасность: [hexName] вставляется в строку ответа как есть, но `hexDecode(hexName) ?: continue`
     * пропускает дальше ТОЛЬКО валидный hex (чётная длина, цифры 0-9a-fA-F), поэтому в ответ не попадут
     * ESC/`\`/`;` — инъекция управляющих последовательностей в поток к серверу невозможна.
     */
    private fun replyXtGetTcap(hexNames: String) {
        var replies = 0
        for (hexName in hexNames.split(';')) {
            if (replies >= MAX_XTGETTCAP_REPLIES) break // анти-амплификация: один DCS не плодит тысячи ответов
            if (hexName.isEmpty()) continue
            val name = hexDecode(hexName) ?: continue // также гарантирует, что hexName — чистый hex (см. выше)
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

    /** Декодирует строку из пар hex-цифр в ASCII; нечётная длина или не-hex → null. */
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

    /** Кодирует ASCII-строку в пары hex-цифр (верхний регистр, как в terminfo/xterm). */
    private fun hexEncode(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (ch in s) {
            val code = ch.code and 0xff
            sb.append(HEX_DIGITS[code ushr 4]).append(HEX_DIGITS[code and 0xf])
        }
        return sb.toString()
    }

    // --- Диспетчеризация CSI ----------------------------------------------

    private fun dispatchCsi(final: Char) {
        val raw = params.toString()
        val privateMarker = raw.firstOrNull()?.takeIf { it == '?' || it == '<' || it == '=' || it == '>' }
        // DECRQM (CSI [?] Ps $ p): запрос текущего состояния режима — отвечаем DECRPM.
        if (csiIntermediate == '$' && final == 'p') {
            val body = if (privateMarker == '?') raw.substring(1) else raw
            reportMode(parseArgs(body).firstOrNull() ?: 0, private = privateMarker == '?')
            return
        }
        if (privateMarker == '?') { privateMode(final, parseArgs(raw.substring(1))); return }
        if (privateMarker != null) {
            // Вторичная DA (CSI > c) и прочие — отвечаем минимально, остальное поглощаем.
            if (privateMarker == '>' && final == 'c') respond("$ESC[>0;10;0c")
            // XTVERSION (CSI > q): сообщаем имя/версию терминала через DCS.
            if (privateMarker == '>' && final == 'q') respond("${ESC}P>|Skerry(0.1)$ESC\\")
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
            // Счётчик капнут числом колонок: курсор всё равно упрётся в границу, поэтому больший повтор
            // бессмыслен. Без капа `ESC[2147483647I` дал бы ~2 млрд итераций в некооперативном цикле —
            // сессия/UI зависли бы без возможности прервать (сервер недоверенный, см. другие repeat-команды).
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
            'c' -> if ((args.getOrNull(0) ?: 0) == 0) respond("$ESC[?1;2c") // DA: VT100 с AVO
            't' -> windowOp(args)
        }
    }

    /**
     * XTWINOPS (CSI Ps ; Ps ; Ps t). Поддерживаем только стек заголовка окна — 22 (push) и 23 (pop);
     * второй параметр выбирает цель: 0 = icon + window title, 2 = window title (оба моделируем как
     * заголовок), 1 = только icon name. В xterm icon- и window-title — РАЗДЕЛЬНЫЕ стеки; icon-only
     * (`;1`) не трогает window-стек ни на push, ни на pop, поэтому мы такие операции игнорируем целиком
     * — наш единственный (window) стек видит лишь сбалансированные `{0,2}`-пары и остаётся согласован.
     * Прочие операции (ресайз/перемещение/запрос геометрии окна) намеренно игнорируем: они либо
     * неуместны для встроенного терминала, либо утечка отпечатка (xterm их по умолчанию тоже отключает).
     */
    private fun windowOp(args: List<Int>) {
        val op = args.getOrNull(0) ?: return
        val target = args.getOrNull(1) ?: 0 // отсутствует -> 0 (icon + window)
        if (target != 0 && target != 2) return // только icon (1) или иное -> заголовок окна не затронут
        when (op) {
            22 -> {
                if (titleStack.size >= MAX_TITLE_STACK) titleStack.removeFirst() // кап против пуша без pop
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
     * DECRQM → DECRPM: отвечает состоянием режима [code]. Pm: 1 = установлен, 2 = сброшен,
     * 0 = не распознан. [private] выбирает DEC-private (`?`-маркер) или ANSI-набор.
     */
    private fun reportMode(code: Int, private: Boolean) {
        val set: Boolean? = if (private) privateModeSet(code) else ansiModeSet(code)
        val pm = when (set) { true -> 1; false -> 2; null -> 0 }
        val marker = if (private) "?" else ""
        respond("$ESC[$marker$code;$pm\$y")
    }

    /** Текущее состояние DEC-private-режима для DECRQM, или `null` если режим не распознан. */
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

    /** Текущее состояние ANSI-режима для DECRQM, или `null` если режим не распознан. */
    private fun ansiModeSet(code: Int): Boolean? = when (code) {
        4 -> insertMode // IRM
        else -> null
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
        if (isCombining(cp) && appendCombining(cp)) return // прицепили к базе — курсор не двигаем
        val w = charWidth(cp)
        if (pendingWrap) {
            // Мягкий перенос: покидаемая строка логически продолжается на следующей — помечаем её
            // wrapped (для reflow) ДО lineFeed, пока cy ещё указывает на неё.
            grid[cy].wrapped = true
            cx = 0
            lineFeed()
            pendingWrap = false
        }
        // Широкий символ не помещается в последнюю колонку: при автопереносе уходим на новую строку,
        // иначе размещаем его как одиночный в последней клетке (нет места под continuation).
        if (w == 2 && cx >= cols - 1 && autoWrap) { grid[cy].wrapped = true; cx = 0; lineFeed() }

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

    /**
     * Прицепляет комбинируемый знак (диакритика, ZWJ, вариационный селектор) к тексту предыдущей
     * базовой клетки, не двигая курсор и не меняя её ширину — так "e"+U+0301 рендерится как одна
     * клетка, а ZWJ-emoji-цепочки не разваливаются. Возвращает false, если базы слева нет (курсор в
     * колонке 0) — тогда знак печатается как обычная клетка (фолбэк). Длину кластера ограничиваем
     * (защита от недоверенного сервера, льющего знаки в одну клетку).
     */
    private fun appendCombining(cp: Int): Boolean {
        val row = grid[cy]
        val baseCol = when {
            pendingWrap -> cx                                                    // курсор не сдвинулся: cx == cols-1 (>=0), это последняя напечатанная клетка
            cx == 0 -> return false                                              // слева пусто — крепить не к чему
            row[cx - 1].width == CellWidth.Continuation && cx >= 2 -> cx - 2     // под Wide-символом — берём саму Wide-клетку
            else -> cx - 1
        }
        val base = row[baseCol]
        if (base.width == CellWidth.Continuation) return false                   // защита: на голую континуацию не крепим
        if (base.text.length >= MAX_GRAPHEME_LEN) return true                    // кластер переполнен — знак глотаем
        row[baseCol] = base.copy(text = base.text + codePointToString(cp))
        return true
    }

    /**
     * Комбинируемый знак нулевой ширины (упрощённая таблица, как [charWidth]): диакритика, ZWJ,
     * вариационные селекторы, комбинируемые знаки для символов. Полная категория Mn/Me — позже.
     * Hangul Jamo (L/V/T-композиция) намеренно НЕ здесь: его ширину держит [charWidth] (как ncurses).
     */
    private fun isCombining(cp: Int): Boolean =
        cp == 0x200D ||                  // ZWJ (склейка emoji)
        cp in 0x0300..0x036F ||          // combining diacritical marks
        cp in 0x0483..0x0489 ||          // комбинируемые (кириллица и пр.)
        cp in 0x0591..0x05BD ||          // иврит (огласовки, часть)
        cp in 0x0610..0x061A ||          // арабский (часть)
        cp in 0x064B..0x065F ||          // арабская диакритика
        cp == 0x0670 ||
        cp in 0x06D6..0x06DC ||
        cp in 0x0E31..0x0E3A ||          // тайский (часть)
        cp in 0x1AB0..0x1AFF ||          // combining diacritical marks extended
        cp in 0x1DC0..0x1DFF ||          // combining diacritical marks supplement
        cp in 0x20D0..0x20FF ||          // combining marks for symbols
        cp in 0xFE00..0xFE0F ||          // variation selectors
        cp in 0xFE20..0xFE2F ||          // combining half marks
        cp in 0xE0100..0xE01EF           // variation selectors supplement

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

    private fun pushScrollback(row: TermRow) {
        scrollback.addLast(row)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    // --- Стирание / вставка / удаление ------------------------------------

    private fun eraseLine(mode: Int) {
        val row = grid[cy]
        when (mode) {
            // Стирание хвоста (0) или всей строки (2) убирает её продолжение — снимаем wrapped,
            // чтобы reflow не приклеил к ней следующую. Стирание головы (1) хвост не трогает.
            0 -> { for (c in cx until cols) row[c] = blankCell(); row.wrapped = false }
            1 -> for (c in 0..cx.coerceAtMost(cols - 1)) row[c] = blankCell()
            2 -> { for (c in 0 until cols) row[c] = blankCell(); row.wrapped = false }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cy + 1 until rows) blankLine(r) }
            1 -> { for (r in 0 until cy) blankLine(r); eraseLine(1) }
            // ED 2/3 гасят весь экран. На основном буфере прежний экран уносим в scrollback, чтобы
            // `clear`/`Ctrl+L` оставляли вывод прокручиваемым вверх (как gnome-terminal/VTE), а не
            // теряли его. ED 3 («erase saved lines») историю НАМЕРЕННО не вытираем — clear шлёт его
            // следом за ED 2, к этому моменту экран уже пуст, поэтому лишних строк в историю не уйдёт.
            2, 3 -> clearScreenToScrollback()
        }
    }

    /**
     * Погасить видимый экран, перенеся его в scrollback (только основной буфер) — прежний вывод
     * остаётся прокручиваемым вверх. Хвостовые пустые строки в историю не уносим, иначе каждый
     * `clear` плодил бы экран пустых строк. На альт-экране scrollback'а нет — чистим на месте.
     */
    private fun clearScreenToScrollback() {
        if (altScreen) {
            for (r in 0 until rows) blankLine(r)
            return
        }
        // Хвостовые ВИЗУАЛЬНО пустые строки в историю не уносим (иначе каждый clear плодил бы экран
        // пустых строк). «Пусто» = пробелы без фонового цвета: строку с BCE-фоном (цветная полоса)
        // считаем содержимым и сохраняем.
        var last = rows - 1
        while (last >= 0 && grid[last].all { it.text == " " && it.style.bg == TermColor.Default && !it.style.inverse }) last--
        // Граница перенесённого экрана и свежей пустой сетки не должна склеиться при reflow: снимаем
        // wrapped с последней уносимой строки, иначе ресайз приклеит к ней пустую строку сетки.
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
        cursorShape = defaultCursorShape; cursorBlink = defaultCursorBlink
        applicationCursorKeys = false; applicationKeypad = false
        bracketedPaste = false; mouseTracking = MouseTracking.Off; mouseSgr = false; mousePixels = false; focusReporting = false
        g0LineDrawing = false; g1LineDrawing = false; glG1 = false
        pendingDesignation = -1
        strSeq.clear(); strSeqIsDcs = false; parser = State.Ground // RIS прерывает любую частичную последовательность
        title = ""; titleStack.clear() // RIS возвращает заголовок к дефолту (вкладка падает на host.label)
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

    /**
     * Изменить размер сетки. Основной буфер переукладывается (reflow): мягко-перенесённые строки
     * склеиваются в логические и переразбиваются по новой ширине, scrollback участвует, курсор едет
     * со своим текстом. Alt-буфер НЕ reflow'ится (приложение перерисует) — обрезка/дополнение.
     * Сбрасывает регион прокрутки и табстопы.
     */
    fun resize(newCols: Int, newRows: Int) {
        // Кап сверху: исключает переполнение Int в cols*rows (REP) и безумный объём работы на ресайз;
        // 2000 с запасом покрывает любой реальный дисплей при крошечном шрифте.
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
            // Если курсор был в pending-wrap и после reflow снова сел на последнюю колонку — сохраняем
            // режим (следующий символ перенесётся, а не перезапишет). Иначе сбрасываем.
            pendingWrap = wasPendingWrap && cx == nc - 1
        }
    }

    /**
     * Переукладка основного буфера (scrollback + [primaryGrid]) под ширину [nc]/высоту [nr]. Логические
     * строки (цепочки мягко-перенесённых физических) переразбиваются по [nc]; нижние [nr] строк идут в
     * новый [primaryGrid], остальные — в scrollback (с обрезкой по maxScrollback). Возвращает новую
     * позицию курсора `(cy, cx)` в координатах нового grid (имеет смысл лишь при [trackCursor]).
     */
    private fun reflowPrimary(nc: Int, nr: Int, trackCursor: Boolean): Pair<Int, Int> {
        val blank = TermCell(" ")
        val cursorAbs = scrollback.size + cy

        // 1. Поток всех физических строк основного буфера.
        val src = ArrayList<TermRow>(scrollback.size + primaryGrid.size).apply {
            addAll(scrollback); addAll(primaryGrid)
        }

        // 2. Группируем в логические строки (цепочки по wrapped) и находим логический индекс/колонку курсора.
        val logicals = ArrayList<MutableList<TermCell>>()
        var curLogIndex = 0
        var curLogCol = cx
        run {
            var i = 0
            var abs = 0
            while (i < src.size) {
                val cells = ArrayList<TermCell>()
                while (true) {
                    val row = src[i]
                    if (trackCursor && abs == cursorAbs) {
                        curLogIndex = logicals.size
                        curLogCol = cells.size + cx
                    }
                    cells.addAll(row)
                    val wrapped = row.wrapped
                    i++; abs++
                    if (!wrapped || i >= src.size) break
                }
                logicals.add(cells)
            }
        }

        // 3. Каждую логическую тримим по хвостовым дефолтным пробелам и переразбиваем по nc.
        val out = ArrayList<TermRow>(logicals.size)
        var cursorAbsNew = 0
        var cursorColNew = 0
        logicals.forEachIndexed { idx, cells ->
            var len = cells.size
            while (len > 0 && cells[len - 1] == blank) len--
            val isCursorLine = trackCursor && idx == curLogIndex
            // Гарантируем, что колонка курсора достижима после трима (курсор может стоять за текстом).
            if (isCursorLine) len = maxOf(len, curLogCol + 1)
            val logical = if (len == cells.size) cells else cells.subList(0, len.coerceAtMost(cells.size))
            val base = out.size
            // Позицию курсора берём из реального прохода разбиения (а не из curLogCol/nc): wide-символы
            // могут давать строки с nc-1 видимыми ячейками, и арифметика бы съезжала.
            val cursorOut = IntArray(2) { -1 }
            out.addAll(splitLogical(logical, nc, blank, if (isCursorLine) curLogCol else -1, cursorOut))
            if (isCursorLine && cursorOut[0] >= 0) {
                cursorAbsNew = base + cursorOut[0]
                cursorColNew = cursorOut[1]
            }
        }

        // 4. Отбрасываем незначимый пустой хвост (неиспользованное место внизу экрана не должно уходить
        //    в scrollback): держим строки до последней непустой и до строки курсора включительно. Но
        //    пустое пространство ВИДИМОГО экрана под курсором — это содержимое (после `clear`/`Ctrl+L`
        //    под prompt'ом лежит пустой экран, а история уже в scrollback): его сохраняем, иначе reflow
        //    схлопнет хвост и втянет историю обратно на видимый экран. Кап nr-1 — курсор не уедет за низ.
        var significant = out.size
        while (significant > 0 && out[significant - 1].all { it == blank }) significant--
        if (trackCursor) {
            val belowCursorInScreen = (rows - 1 - cy).coerceIn(0, nr - 1)
            significant = maxOf(significant, cursorAbsNew + 1 + belowCursorInScreen)
        }
        significant = significant.coerceAtLeast(1).coerceAtMost(out.size)
        val kept = out.subList(0, significant)

        // 5. Делим на grid (нижние nr) и scrollback (верх), дополняя пустыми при нехватке.
        val gridStart: Int
        val newGrid: MutableList<TermRow>
        if (kept.size >= nr) {
            gridStart = kept.size - nr
            newGrid = ArrayList(kept.subList(gridStart, kept.size))
        } else {
            gridStart = 0
            newGrid = ArrayList(kept)
            repeat(nr - kept.size) { newGrid.add(TermRow(MutableList(nc) { blank })) }
        }
        val newScroll = if (gridStart > 0) kept.subList(0, gridStart) else emptyList()

        scrollback.clear()
        val drop = (newScroll.size - maxScrollback).coerceAtLeast(0)
        for (r in drop until newScroll.size) scrollback.addLast(newScroll[r])
        primaryGrid = newGrid

        val newCy = cursorAbsNew - gridStart
        return Pair(newCy, cursorColNew)
    }

    /**
     * Разбивает логическую строку [cells] на физические шириной [nc], не разрывая широкие символы
     * (Wide+Continuation не должны попадать в разные строки). Все, кроме последней, помечаются
     * `wrapped = true`; каждая дополняется до [nc] нейтральным [pad]. Пустая логическая → одна пустая строка.
     *
     * Если [cursorCol] >= 0, в [cursorOut] кладётся позиция курсора в результате: `[0]` — индекс строки
     * (внутри возвращённого списка), `[1]` — колонка в ней. Позиция берётся прямым проходом, поэтому
     * корректна и при early-break на широком символе (когда строка несёт nc-1 видимых ячеек).
     */
    private fun splitLogical(
        cells: List<TermCell>,
        nc: Int,
        pad: TermCell,
        cursorCol: Int = -1,
        cursorOut: IntArray? = null,
    ): List<TermRow> {
        if (cells.isEmpty()) {
            if (cursorCol >= 0 && cursorOut != null) { cursorOut[0] = 0; cursorOut[1] = cursorCol.coerceIn(0, nc - 1) }
            return listOf(TermRow(MutableList(nc) { pad }))
        }
        val out = ArrayList<TermRow>()
        var idx = 0
        while (idx < cells.size) {
            val chunk = ArrayList<TermCell>(nc)
            while (idx < cells.size && chunk.size < nc) {
                val cell = cells[idx]
                // Широкий символ не делим: если пара не влезает в остаток непустого chunk — оборвать раньше.
                if (cell.width == CellWidth.Wide && chunk.isNotEmpty() && chunk.size + 2 > nc) break
                if (idx == cursorCol && cursorOut != null) { cursorOut[0] = out.size; cursorOut[1] = chunk.size }
                chunk.add(cell); idx++
            }
            while (chunk.size < nc) chunk.add(pad)
            out.add(TermRow(chunk, wrapped = idx < cells.size))
        }
        return out
    }

    /** Ресайз сетки БЕЗ reflow (alt-screen): обрезка/дополнение строк и рядов. */
    private fun resizeGrid(g: MutableList<TermRow>, nc: Int, nr: Int, activePrimary: Boolean) {
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
            while (g.size < nr) g.add(TermRow(MutableList(nc) { TermCell(' ') }))
        }
    }

    // --- Фабрики ячеек -----------------------------------------------------

    private fun freshScreen(): MutableList<TermRow> =
        MutableList(rows) { TermRow(MutableList(cols) { TermCell(' ') }) }

    /**
     * Пустая ячейка с текущим фоном — background-color-erase (BCE): стирание и прокрутка красят
     * ячейки ТЕКУЩИМ SGR-фоном, включая reverse-video (тогда «фоном» становится цвет текста).
     * Несём fg/bg/inverse: ncurses (nano/htop) дозаполняет reverse-полосы через `ESC[K`, полагаясь
     * на BCE — без флага inverse хвост строки рисовался бы обычным фоном. Глифовые атрибуты
     * (bold/underline/strike) у пустой ячейки не несём: xterm их при стирании не применяет.
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
        /** Потолок длины OSC-строки (защита от OOM на недоверенном выводе); 4 MiB с запасом под OSC 52. */
        const val MAX_OSC_LEN = 4 * 1024 * 1024

        /** Потолок длины буфера параметров CSI (защита от OOM: сервер льёт цифры без финального байта). */
        const val MAX_CSI_PARAMS_LEN = 1024

        /** Потолок размера текста OSC 52 для записи в системный буфер обмена (анти-флуд буфера). */
        const val MAX_CLIPBOARD_LEN = 64 * 1024

        /** Потолок числа ответов на один XTGETTCAP-запрос (анти-амплификация исходящего PTY-трафика). */
        const val MAX_XTGETTCAP_REPLIES = 64

        /** Потолок длины OSC 8 URI (URI копируется в каждую клетку — защита от раздувания grid). */
        const val MAX_HYPERLINK_LEN = 2048

        /** Потолок ширины/высоты сетки: против Int-overflow в cols*rows и чрезмерной работы на ресайз. */
        const val MAX_DIMENSION = 2000

        /** Потолок глубины стека заголовка окна (CSI 22 t без парного 23 t) — против раздувания. */
        const val MAX_TITLE_STACK = 128

        /** Потолок длины grapheme-кластера в клетке (база + комбинируемые) — против раздувания. */
        const val MAX_GRAPHEME_LEN = 32
        const val TAB = 8
        val ESC = 27.toChar().toString()
        private const val HEX_DIGITS = "0123456789ABCDEF"

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
