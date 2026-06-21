package app.skerry.ui.design

import app.skerry.ui.connection.ConnectionUiState

/**
 * Чистая логика мобильного терминал-экрана (`Skerry Mobile.html`, push-экран Terminal) — отделена
 * от Composable-вью ([MobileTerminalScreen]), чтобы покрываться юнит-тестами без Compose (как
 * хелперы [app.skerry.ui.connection.toTarget] и т.п.).
 */

/**
 * Текст статус-строки под именем хоста в шапке терминала по состоянию соединения активной сессии.
 * Цвет берётся отдельно через [sessionDotColor]. В макете строка «connected · 42ms» — суффикс
 * с пингом опущен (живой телеметрии RTT пока нет, как и в desktop-статусбаре).
 */
fun mobileTerminalStatusText(state: ConnectionUiState?): String = when (state) {
    is ConnectionUiState.Connected -> "connected"
    ConnectionUiState.Connecting -> "connecting…"
    is ConnectionUiState.Error -> "disconnected"
    else -> "no session"
}

/** Что делать при тапе Connect, когда у хоста уже есть открытая сессия. */
enum class MobileConnectAction {
    /** Сессия живая (подключена/подключается) — просто показать её, не плодя вкладки. */
    Resume,

    /** Сессии нет либо она мёртвая (ошибка/закрыта) — открыть новую (переподключение). */
    OpenFresh,
}

/**
 * Решение по последней сессии хоста: возобновить живую или открыть свежую. На телефоне (в отличие
 * от desktop-вкладок) показывается одна сессия за раз, поэтому повторный Connect к тому же хосту не
 * должен накапливать сокеты — живую переиспользуем, мёртвую заменяем.
 */
fun mobileConnectAction(existing: ConnectionUiState?): MobileConnectAction =
    if (existing is ConnectionUiState.Connected || existing == ConnectionUiState.Connecting) {
        MobileConnectAction.Resume
    } else {
        MobileConnectAction.OpenFresh
    }

/**
 * Control-последовательность для Ctrl+[c] клавишной панели терминала (sticky-ctrl): C0-код = код
 * символа в верхнем регистре, маскированный 0x1F. Так Ctrl+C → ETX (0x03), Ctrl+[ → ESC (0x1B).
 * Возвращает строку из одного символа для отправки в PTY ([app.skerry.ui.terminal.TerminalScreenState.send]).
 */
fun controlByte(c: Char): String = (c.uppercaseChar().code and 0x1F).toChar().toString()

/**
 * Применяет sticky-ctrl к строке, введённой с софт-клавиатуры (IME-путь терминала: текст снимается
 * скрытым полем мимо клавишной панели). Если ctrl армирован и ввод непустой — ПЕРВЫЙ символ кодируется
 * как Ctrl+<символ> ([controlByte]), остаток уходит как есть; модификатор действует на одно нажатие,
 * как на физической клавиатуре (снятие делает вызывающий, увидев тот же предикат). Без армирования
 * или на пустом вводе — строка без изменений.
 */
fun applyStickyCtrl(armed: Boolean, input: String): String =
    if (armed && input.isNotEmpty()) controlByte(input[0]) + input.substring(1) else input

/** Клавиша-стрелка клавишной панели терминала; [finalByte] — финальный символ её escape-кода. */
enum class ArrowKey(val finalByte: Char) { Up('A'), Down('B'), Right('C'), Left('D') }

/** ESC (0x1B) — задан кодом, чтобы не быть невидимым управляющим байтом в исходнике (Read/grep). */
private val ESC: String = 27.toChar().toString()

/**
 * Escape-последовательность клавиши-стрелки для PTY с учётом DECCKM (application-cursor-keys,
 * [app.skerry.ui.terminal.TerminalScreenState.applicationCursorKeys]). В нормальном режиме —
 * CSI (`ESC[A`); когда полноэкранная программа (vim/less) включила application-режим через `ESC[?1h` —
 * SS3 (`ESC O A`). Чистая и тестируемая: панель читает текущий режим сессии и зовёт эту функцию.
 */
fun arrowSequence(key: ArrowKey, applicationCursor: Boolean): String =
    (if (applicationCursor) ESC + "O" else ESC + "[") + key.finalByte
