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

    // Курсор циклирования альтернатив (Shift+Tab): индекс в списке [candidates]. Сбрасывается на 0
    // при любом изменении строки, чтобы после нового символа снова показывалась лучшая подсказка.
    private var cycleIndex = 0

    /** Текущая набранная строка (для тестов/диагностики). */
    val currentLine: String get() = line.toString()

    /** История команд (для reverse-search из UI). */
    val commandHistory: CommandHistory get() = history

    /** Сбросить текущую отслеживаемую строку БЕЗ записи в историю (напр. на входе в режим без эха). */
    fun reset() {
        line.clear()
        cycleIndex = 0
    }

    /**
     * Учесть отправленные пользователем в PTY [data] байты. Возвращает команду, если ввод содержал
     * Enter (её же движок кладёт в историю), иначе `null`. Несколько строк в одном блоке
     * обрабатываются по очереди — возвращается ПОСЛЕДНЯЯ закоммиченная.
     */
    fun onUserInput(data: ByteArray): String? {
        cycleIndex = 0 // строка меняется — циклирование начинается заново с лучшего кандидата
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

    /**
     * Упорядоченный список полных предложений для текущей строки (для циклирования). Приоритет:
     * история → типовые команды → (при уже начатом аргументе) известные подкоманды и пути/токены,
     * встреченные в истории этой сессии. Дубликаты схлопнуты, сохранён порядок первого появления.
     * Пустой список, если подсказывать нечего (пустая строка / завершается пробелом).
     */
    fun candidates(): List<String> {
        val prefix = line.toString()
        if (prefix.isBlank() || prefix.endsWith(' ')) return emptyList()
        val out = LinkedHashSet<String>()
        history.matches(prefix).forEach { out.add(it) }
        builtins.forEach { if (it.length > prefix.length && it.startsWith(prefix)) out.add(it) }
        if (prefix.contains(' ')) {
            subcommandCandidates(prefix).forEach { out.add(it) }
            tokenCandidates(prefix).forEach { out.add(it) }
        }
        return out.filter { it.length > prefix.length && it.startsWith(prefix) }.toList()
    }

    /** Полное предложение для текущей строки — кандидат под курсором циклирования, либо `null`. */
    fun suggestion(): String? {
        val c = candidates()
        if (c.isEmpty()) return null
        return c[cycleIndex.mod(c.size)]
    }

    /** «Хвост» подсказки — то, что дорисовать серым после уже набранного, либо `null`. */
    fun suggestionTail(): String? {
        val full = suggestion() ?: return null
        return full.substring(line.length)
    }

    /**
     * Переключить подсказку на следующую альтернативу (Shift+Tab). Циклирует по [candidates] с
     * заворотом; при одном/нуле кандидатов — no-op. Строку не меняет — только выбираемый «призрак».
     */
    fun cycleSuggestion() {
        val size = candidates().size
        if (size > 1) cycleIndex = (cycleIndex + 1).mod(size)
    }

    /**
     * Принять подсказку: вернуть байты, которые надо отправить в сессию, чтобы дописать команду
     * (сам «хвост»), и обновить внутреннюю строку. `null`, если принимать нечего.
     */
    fun acceptSuggestion(): ByteArray? {
        val tail = suggestionTail() ?: return null
        line.append(tail)
        cycleIndex = 0
        return tail.encodeToByteArray()
    }

    /**
     * Подсказки известных подкоманд: для строки `cmd partial` (ровно два слова, где `cmd` — команда
     * из [SUBCOMMANDS]) вернуть `cmd sub` для каждой подкоманды, начинающейся с `partial`.
     */
    private fun subcommandCandidates(prefix: String): List<String> {
        val words = prefix.split(' ')
        if (words.size != 2) return emptyList()
        val (cmd, partial) = words
        val subs = SUBCOMMANDS[cmd] ?: return emptyList()
        return subs.filter { it != partial && it.startsWith(partial) }.map { "$cmd $it" }
    }

    /**
     * Дополнение последнего слова путём/токеном, встреченным как аргумент в истории этой сессии
     * (пути, имена файлов/юнитов и пр.). Токены собираются из истории на лету, от новых к старым.
     */
    private fun tokenCandidates(prefix: String): List<String> {
        val lastSpace = prefix.lastIndexOf(' ')
        val head = prefix.substring(0, lastSpace + 1)
        val partial = prefix.substring(lastSpace + 1)
        if (partial.isEmpty()) return emptyList()
        return sessionTokens()
            .filter { it.length > partial.length && it.startsWith(partial) }
            .map { head + it }
    }

    /** Различимые аргументы (не первое слово) из истории команд, от новых к старым, без дублей. */
    private fun sessionTokens(): List<String> {
        val seen = LinkedHashSet<String>()
        for (cmd in history.commands) {
            val parts = cmd.split(' ')
            for (i in 1 until parts.size) {
                val t = parts[i]
                if (t.length >= 2) seen.add(t)
            }
        }
        return seen.toList()
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

/**
 * Известные подкоманды частых CLI для дополнения второго слова (`git pus` → `git push`). Намеренно
 * компактно и без деструктивных подсказок первыми. Работает и с пустой историей.
 */
val SUBCOMMANDS: Map<String, List<String>> = mapOf(
    "git" to listOf(
        "status", "add", "commit", "push", "pull", "fetch", "checkout", "switch", "branch",
        "log", "diff", "stash", "merge", "rebase", "clone", "remote", "reset", "tag", "restore",
    ),
    "docker" to listOf(
        "ps", "images", "logs", "exec", "run", "build", "pull", "push", "stop", "start",
        "restart", "rm", "rmi", "compose", "inspect", "stats", "network", "volume", "system",
    ),
    "systemctl" to listOf(
        "status", "start", "stop", "restart", "reload", "enable", "disable", "list-units",
        "daemon-reload", "is-active", "is-enabled",
    ),
    "kubectl" to listOf(
        "get", "describe", "logs", "apply", "delete", "exec", "rollout", "scale",
        "port-forward", "config", "cluster-info",
    ),
    "apt" to listOf("update", "upgrade", "install", "remove", "search", "show", "list", "autoremove"),
    "brew" to listOf("install", "update", "upgrade", "list", "search", "info", "uninstall", "services"),
    "npm" to listOf("install", "run", "start", "test", "build", "update", "list", "ci"),
    "cargo" to listOf("build", "run", "test", "check", "add", "update", "clippy", "fmt"),
)
