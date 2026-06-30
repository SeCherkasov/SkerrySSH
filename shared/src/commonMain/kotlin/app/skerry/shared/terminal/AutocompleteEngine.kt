package app.skerry.shared.terminal

/**
 * Движок автодополнения терминала (модель inline-подсказки fish/zsh). Клиент не парсит удалённый
 * shell — он локально отслеживает строку, которую пользователь НАБИРАЕТ (по отправленным в PTY
 * байтам), и предлагает «призрачное» продолжение из истории команд и списка типовых команд/путей.
 *
 * Отслеживание строки грубое (клиент не знает реальную позицию курсора): обрабатываются печатные
 * ASCII/UTF-8 символы, Backspace/Delete, Ctrl-U/Ctrl-C (сброс) и Enter (коммит строки в историю).
 * Управляющие/ESC-последовательности (стрелки и пр.) сбрасывают подсказку, но строку не портят.
 * Этого достаточно для набора команды с нуля — самого частого сценария автодополнения.
 *
 * Использование из UI: [onUserInput] на каждый отправленный в сессию блок; [suggestionTail] —
 * что дорисовать серым после ввода; [acceptSuggestion] — байты, которые надо отправить, чтобы
 * принять подсказку (Tab/→), с обновлением внутренней строки.
 */
class AutocompleteEngine(
    private val history: CommandHistory = CommandHistory(),
    private val builtins: List<String> = COMMON_COMMANDS,
) {
    private val line = StringBuilder()

    /** Текущая набранная строка (для тестов/диагностики). */
    val currentLine: String get() = line.toString()

    /** Сбросить текущую отслеживаемую строку БЕЗ записи в историю (напр. на входе в режим без эха). */
    fun reset() {
        line.clear()
    }

    /**
     * Учесть отправленные пользователем в PTY [data] байты. Возвращает команду, если ввод содержал
     * Enter (её же движок кладёт в историю), иначе `null`. Несколько строк в одном блоке
     * обрабатываются по очереди — возвращается ПОСЛЕДНЯЯ закоммиченная.
     */
    fun onUserInput(data: ByteArray): String? {
        var committed: String? = null
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == CR || b == LF -> {
                    val cmd = line.toString().trim()
                    if (cmd.isNotEmpty()) {
                        history.record(cmd)
                        committed = cmd
                    }
                    line.clear()
                }
                b == BS || b == DEL -> if (line.isNotEmpty()) line.deleteAt(line.length - 1)
                b == CTRL_U || b == CTRL_C -> line.clear()
                b == ESC -> { line.clear(); i = skipEscapeSequence(data, i) } // стрелки/навигация — сбрасываем
                b == TAB -> { /* accept — обрабатывает UI через acceptSuggestion */ }
                b < 0x20 -> { /* прочие управляющие — игнор, строку не трогаем */ }
                else -> {
                    // Печатный символ: собираем как UTF-8 (многобайтовые последовательности целиком).
                    val (ch, next) = decodeUtf8(data, i)
                    if (ch != null) line.append(ch)
                    i = next
                    continue
                }
            }
            i++
        }
        return committed
    }

    /** Полное предложение для текущей строки (из истории приоритетно, затем типовые), либо `null`. */
    fun suggestion(): String? {
        val prefix = line.toString()
        if (prefix.isBlank() || prefix.endsWith(' ')) return null
        history.suggestion(prefix)?.let { return it }
        return builtins.firstOrNull { it.length > prefix.length && it.startsWith(prefix) }
    }

    /** «Хвост» подсказки — то, что дорисовать серым после уже набранного, либо `null`. */
    fun suggestionTail(): String? {
        val full = suggestion() ?: return null
        return full.substring(line.length)
    }

    /**
     * Принять подсказку: вернуть байты, которые надо отправить в сессию, чтобы дописать команду
     * (сам «хвост»), и обновить внутреннюю строку. `null`, если принимать нечего.
     */
    fun acceptSuggestion(): ByteArray? {
        val tail = suggestionTail() ?: return null
        line.append(tail)
        return tail.encodeToByteArray()
    }

    /** Пропустить ESC-последовательность (CSI/`ESC [ … final` или простой `ESC x`); вернуть индекс её конца. */
    private fun skipEscapeSequence(data: ByteArray, escIndex: Int): Int {
        if (escIndex + 1 >= data.size) return escIndex
        val next = data[escIndex + 1].toInt() and 0xFF
        if (next != '['.code && next != 'O'.code) return escIndex + 1 // простой ESC x
        var j = escIndex + 2
        while (j < data.size) {
            val c = data[j].toInt() and 0xFF
            if (c in 0x40..0x7E) return j // финальный байт CSI
            j++
        }
        return data.size - 1
    }

    /** Декодировать один UTF-8 символ, начиная с [i]; вернуть (символ|null, индекс следующего байта). */
    private fun decodeUtf8(data: ByteArray, i: Int): Pair<Char?, Int> {
        val b = data[i].toInt() and 0xFF
        val len = when {
            b < 0x80 -> 1
            b in 0xC0..0xDF -> 2
            b in 0xE0..0xEF -> 3
            b in 0xF0..0xF7 -> 4
            else -> 1 // недопустимый ведущий байт — пропускаем один
        }
        if (i + len > data.size) return null to (i + 1) // неполная последовательность в этом блоке
        val text = data.copyOfRange(i, i + len).decodeToString()
        return text.firstOrNull() to (i + len)
    }

    private companion object {
        const val CR = 13
        const val LF = 10
        const val BS = 8
        const val DEL = 127
        const val CTRL_C = 3
        const val CTRL_U = 21
        const val ESC = 27
        const val TAB = 9
    }
}

/**
 * Небольшой список частых команд/путей для автодополнения, когда история пуста. Намеренно короткий и
 * «безопасный» (ничего деструктивного не подсказываем как первое совпадение перед destructive-словами).
 */
val COMMON_COMMANDS: List<String> = listOf(
    "cd ", "ls -la", "ls -lah", "cat ", "grep -rn ", "tail -f ", "less ",
    "cd /etc/", "cd /var/log/", "cd /home/", "cd /usr/local/",
    "systemctl status ", "systemctl restart ", "journalctl -u ", "journalctl -xe",
    "docker ps", "docker logs ", "docker compose up -d", "docker compose down",
    "git status", "git pull", "git log --oneline",
    "df -h", "du -sh ", "free -h", "top", "htop", "ps aux | grep ",
    "sudo ", "exit", "clear",
)
