package app.skerry.shared.terminal

/** Кнопка/колесо мыши для репортинга в приложение. */
enum class MouseButton { Left, Middle, Right, WheelUp, WheelDown }

/** Тип события мыши, отправляемого в PTY. */
enum class MouseEventType {
    /** Нажатие кнопки (а также «тик» колеса). */
    Press,

    /** Отпускание кнопки. */
    Release,

    /** Движение с зажатой кнопкой (репортится в ButtonEvent/AnyEvent). */
    Drag,

    /** Движение без зажатых кнопок (только AnyEvent). */
    Move,
}

private val ESC = 27.toChar()

/** Базовый код кнопки в протоколе мыши: левая=0, средняя=1, правая=2, колесо вверх/вниз=64/65. */
private fun MouseButton.code(): Int = when (this) {
    MouseButton.Left -> 0
    MouseButton.Middle -> 1
    MouseButton.Right -> 2
    MouseButton.WheelUp -> 64
    MouseButton.WheelDown -> 65
}

/**
 * Кодирует событие мыши в байты для PTY согласно активному режиму [tracking] и кодировке:
 *  - `sgr=true` — расширенный SGR-формат (DEC 1006): `ESC [ < Cb ; col ; row M|m`, координаты
 *    1-based в десятичном виде, без 223-предела; отпускание идёт строчной `m`, кнопка сохраняется;
 *  - `sgr=false` — классический X11-формат (DEC 1000): `ESC [ M Cb Cx Cy`, где каждый байт = значение+32,
 *    координаты `col+1`/`row+1`, кнопка при отпускании — неразличимый код 3; байты клампятся в 0..255.
 *  - `pixels=true` — SGR-Pixels (DEC 1016): тот же `ESC [ <` -формат, что и SGR, но координаты —
 *    пиксельные [pixelX]/[pixelY] (0-based, в протоколе 1-based) вместо клеточных. Подразумевает
 *    SGR-кодировку независимо от [sgr].
 *
 * Cb битами: младшие 2 — кнопка (или 3 = «нет/release»), 4 — Shift, 8 — Meta(Alt), 16 — Ctrl,
 * 32 — движение (Drag/Move), 64 — колесо. Возвращает `null`, если событие в данном режиме не
 * репортится (например, движение в Normal или отпускание в X10).
 *
 * [col]/[row] — 0-based индексы ячейки; в протоколе они становятся 1-based.
 */
fun encodeMouseReport(
    tracking: MouseTracking,
    sgr: Boolean,
    button: MouseButton,
    type: MouseEventType,
    col: Int,
    row: Int,
    shift: Boolean = false,
    alt: Boolean = false,
    ctrl: Boolean = false,
    pixels: Boolean = false,
    pixelX: Int = 0,
    pixelY: Int = 0,
): ByteArray? {
    val wheel = button == MouseButton.WheelUp || button == MouseButton.WheelDown
    val motion = type == MouseEventType.Drag || type == MouseEventType.Move

    // Гейтинг по режиму: какие события вообще репортятся.
    when (tracking) {
        MouseTracking.Off -> return null
        MouseTracking.X10 -> if (type != MouseEventType.Press || wheel) return null // только нажатия кнопок
        MouseTracking.Normal -> if (motion) return null // без движения
        MouseTracking.ButtonEvent -> if (type == MouseEventType.Move) return null // движение только с кнопкой
        MouseTracking.AnyEvent -> {} // всё
    }

    val isRelease = type == MouseEventType.Release
    // Базовый код кнопки. В legacy отпускание кодируется неразличимым 3; в SGR кнопка сохраняется.
    // Движение без кнопки (Move) — тоже младшие биты 3 («кнопки не зажаты»).
    var cb = when {
        wheel -> button.code()
        type == MouseEventType.Move -> 3
        // Только legacy (X11) кодирует отпускание неразличимым 3; SGR и SGR-Pixels сохраняют кнопку
        // (release различается строчной `m`).
        isRelease && !sgr && !pixels -> 3
        else -> button.code()
    }
    if (motion) cb += 32
    // Модификаторы (X10 их не кодирует).
    if (tracking != MouseTracking.X10) {
        if (shift) cb += 4
        if (alt) cb += 8
        if (ctrl) cb += 16
    }

    return when {
        pixels -> {
            val final = if (isRelease) 'm' else 'M'
            "$ESC[<$cb;${pixelX + 1};${pixelY + 1}$final".encodeToByteArray()
        }
        sgr -> {
            val final = if (isRelease) 'm' else 'M'
            "$ESC[<$cb;${col + 1};${row + 1}$final".encodeToByteArray()
        }
        else -> {
            fun enc(v: Int): Byte = (v + 32).coerceIn(0, 255).toByte()
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), enc(cb), enc(col + 1), enc(row + 1))
        }
    }
}

/**
 * Оборачивает вставляемый текст маркерами bracketed-paste (`ESC[200~` … `ESC[201~`), когда режим
 * включён приложением (DEC 2004) — тогда shell/редактор отличает вставку от ввода и не исполняет
 * переводы строк как команды. При выключенном режиме текст возвращается как есть.
 *
 * Из содержимого вырезается закрывающий маркер `ESC[201~`: иначе вставка текста, в который недоверенный
 * SSH-сервер «подсадил» этот маркер (через вывод, попавший в выделение), завершилась бы раньше времени,
 * и хвост ушёл бы в приложение как набранные команды (классическая paste-инъекция).
 *
 * При ВЫКЛЮЧЕННОМ режиме приложение не отличает вставку от ввода, поэтому управляющие байты из буфера
 * (например `CR` = Enter) исполнились бы как команды. Поэтому из сырой вставки режем C0-управляющие
 * (`0x00`–`0x1F`) и `DEL` (`0x7F`), кроме `Tab` и `LF` — легитимных разделителей. В bracketed-режиме
 * управляющие байты, наоборот, оставляем литералами: приложение знает, что это вставка, и не исполнит их.
 */
fun bracketedPasteWrap(text: String, bracketed: Boolean): String {
    if (!bracketed) return text.filterNot { it.code < 0x20 && it != '\t' && it != '\n' || it.code == 0x7f }
    val sanitized = text.replace("$ESC[201~", "")
    return "$ESC[200~$sanitized$ESC[201~"
}
