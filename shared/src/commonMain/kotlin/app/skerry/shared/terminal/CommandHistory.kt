package app.skerry.shared.terminal

/**
 * История введённых команд для автодополнения (модель fish/zsh-autosuggestion). Хранит команды
 * от новой к старой, схлопывает подряд идущие дубликаты и повторный ввод «поднимает» команду наверх,
 * ограничена [capacity]. Чистая логика без IO; сейчас только в памяти на сессию (не персистится).
 *
 * БЕЗОПАСНОСТЬ: сюда пишется всё, что пользователь набрал и подтвердил Enter. Ввод в режиме без эха
 * (пароли/passphrase) верхний слой отсекает ДО записи по сигналу [app.skerry.shared.ssh.ShellChannel.echoSuppressed]
 * (см. `TerminalScreenState.typeInput`). Для SSH эхо-статус недоступен, поэтому in-session пароли —
 * остаточный риск: НЕ добавлять дисковый персист истории, не решив детектирование эха для всех
 * транспортов, иначе секреты попадут на диск.
 */
class CommandHistory(private val capacity: Int = 500) {

    private val entries = ArrayDeque<String>() // index 0 — самая свежая

    /** Снимок истории от новой к старой. */
    val commands: List<String> get() = entries.toList()

    /** Заполнить историю готовым списком (напр. загруженным из стора); порядок — от новой к старой. */
    fun preload(history: List<String>) {
        entries.clear()
        history.asReversed().forEach { record(it) }
    }

    /**
     * Записать выполненную [command]. Пустые/пробельные игнорируются; если такая команда уже есть —
     * она перемещается наверх (без дублей), иначе добавляется в начало. Сверх [capacity] хвост
     * отбрасывается.
     */
    fun record(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        entries.remove(trimmed)
        entries.addFirst(trimmed)
        while (entries.size > capacity) entries.removeLast()
    }

    /**
     * Самая свежая команда, начинающаяся с [prefix] и строго длиннее его, либо `null`. Пустой/пробельный
     * [prefix] подсказок не даёт (не мешаем в начале строки).
     */
    fun suggestion(prefix: String): String? = matches(prefix).firstOrNull()

    /**
     * Все команды, начинающиеся с [prefix] и строго длиннее его, от новой к старой (для циклирования
     * альтернатив). Пустой/пробельный [prefix] — пустой список.
     */
    fun matches(prefix: String): List<String> {
        if (prefix.isBlank()) return emptyList()
        return entries.filter { it.length > prefix.length && it.startsWith(prefix) }
    }

    /**
     * Поиск по подстроке (reverse-search, как Ctrl-R в bash/zsh): команды, СОДЕРЖАЩИЕ [query],
     * от новой к старой. Пустой/пробельный [query] — пустой список.
     */
    fun search(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return entries.filter { it.contains(query) }
    }

    /** Забыть [command] (например, опечатку, упавшую с «command not found»). `true`, если была в истории. */
    fun forget(command: String): Boolean = entries.remove(command.trim())
}
