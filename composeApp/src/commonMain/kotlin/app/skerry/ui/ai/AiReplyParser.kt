package app.skerry.ui.ai

/**
 * Чистый разбор ответа модели терминального AI-бара ([TerminalAiController.commandPrompt]) в
 * предложение команды или сообщение. Security-критичный слой: [sanitizeCommand]/[isSafeInputChar]
 * гарантируют, что подтверждаемая пользователем команда — одна строка без управляющих и
 * bidi/zero-width символов (инвариант «подтверждение перед выполнением»). Вынесен в объект без
 * состояния, чтобы тестироваться напрямую, без контроллера/провайдера.
 */
internal object AiReplyParser {

    /** Результат разбора ответа модели. */
    sealed interface Reply {
        /** Есть команда к подтверждению; [info] — краткое пояснение (может отсутствовать). */
        data class Command(val command: String, val info: String?) : Reply

        /** Уточнение/отказ (ASK-строка или `#`-комментарий); `null` [text] — просить уточнить запрос. */
        data class Ask(val text: String?) : Reply

        /** Ответ — проза без маркеров (не команда); показать сообщением. */
        data class Prose(val text: String) : Reply

        /** Модель не вернула ничего пригодного. */
        data object NoCommand : Reply
    }

    /**
     * Разобрать ответ модели: либо `CMD:`+`INFO:` (команда), либо `ASK:` (уточнение/отказ — НЕ
     * команда). Если маркеров нет — толерантный фолбэк: первая строка как команда, но если она
     * похожа на прозу (кириллица/вопрос/фраза-уточнение) — это тоже сообщение, а не команда (иначе
     * «уточните запрос…» попадало бы в слот с кнопкой Run).
     */
    fun parse(raw: String): Reply {
        val cmdLine = lineAfter(raw, "CMD:")
        val askLine = lineAfter(raw, "ASK:")
        val command = cmdLine?.let { sanitizeCommand(it) }
        return when {
            command != null && !command.startsWith("#") && !looksLikeProse(command) ->
                Reply.Command(command, lineAfter(raw, "INFO:")?.let { cleanLine(it) })
            askLine != null -> Reply.Ask(cleanLine(askLine))
            else -> {
                val first = sanitizeCommand(raw)
                when {
                    first == null -> Reply.NoCommand
                    first.startsWith("#") -> Reply.Ask(first.trimStart('#').trim().ifEmpty { null })
                    looksLikeProse(first) -> Reply.Prose(first)
                    else -> Reply.Command(first, extractDescription(raw))
                }
            }
        }
    }

    /**
     * Привести сырой вывод модели к ОДНОЙ строке ввода без управляющих символов и markdown-обёрток.
     * Критично для инварианта «подтверждение перед выполнением»: вставляемая по confirm команда
     * физически не может нести перевод строки (иначе `send` авто-исполнил бы её), даже если модель
     * вернула многострочный текст или CR/LF-инъекцию.
     *
     * Шаги: снимаем ```-заборчик (с возможным языковым тегом), берём первую непустую строку, режем
     * control-байты (кроме таба), затем срезаем одиночные inline-бэктики вокруг команды — иначе bash
     * воспримет `` `free -h` `` как подстановку команды (выполнит и попробует запустить её вывод).
     * `null` — команды нет.
     */
    fun sanitizeCommand(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```") && text.length > 6) {
            text = text.substring(3, text.length - 3)
            val firstTok = text.substringBefore('\n').trim()
            // ```bash / ```sh — языковой тег на первой строке заборчика, отбрасываем.
            if (firstTok.isNotEmpty() && firstTok.none { it.isWhitespace() } &&
                firstTok.all { it.isLetterOrDigit() || it == '-' }
            ) {
                text = text.substringAfter('\n', "")
            }
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val cleaned = firstLine.filter { isSafeInputChar(it) }.trim().trim('`').trim()
        return cleaned.ifEmpty { null }
    }

    /**
     * Разрешён ли символ в команде/пояснении. Кроме control-байтов (< 0x20, кроме таба) режем ещё и
     * Unicode-форматные/двунаправленные символы: RTL/LTR-override и isolate, zero-width, BOM, soft
     * hyphen. Иначе (Trojan-Source) ответ модели с `U+202E` мог бы отрисоваться в баре подтверждения
     * одной последовательностью, а уйти в PTY — другой: пользователь подтвердил бы не то, что видит.
     * Бар подтверждения — единственный гейт безопасности AI-фичи, поэтому чистим агрессивно.
     */
    fun isSafeInputChar(c: Char): Boolean {
        if (c != '\t' && c.code < 0x20) return false
        val code = c.code
        val unsafeFormat = code == 0x00AD ||          // soft hyphen
            code == 0x061C ||                          // arabic letter mark
            code in 0x200B..0x200F ||                  // ZWSP/ZWNJ/ZWJ/LRM/RLM
            code == 0x2028 || code == 0x2029 ||        // line/paragraph separator
            code == 0x2060 ||                          // word joiner
            code in 0x202A..0x202E ||                  // bidi embeddings/overrides
            code in 0x2066..0x2069 ||                  // bidi isolates
            code == 0xFEFF                             // ZWNBSP / BOM
        return !unsafeFormat
    }

    /**
     * Вторая непустая строка ответа модели — краткое пояснение, что делает команда. Снимаем маркеры
     * списков/`#`/бэктики, режем до 120 символов. `null` — пояснения нет.
     */
    fun extractDescription(raw: String): String? {
        val lines = raw.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val desc = lines.getOrNull(1) ?: return null
        val cleaned = desc.trimStart('#', '-', '*', '•', '>').trim().trim('`').trim()
            .filter { isSafeInputChar(it) }.trim()
        return cleaned.ifEmpty { null }?.take(120)
    }

    /**
     * Похоже ли на естественный язык (уточнение/отказ), а не на shell-команду. Эвристика-предохранитель
     * на случай, когда модель не проставила `ASK:`: кириллица, финальный «?» или типичные фразы-уточнения.
     */
    fun looksLikeProse(s: String): Boolean {
        if (s.endsWith("?")) return true
        if (s.any { it in 'Ѐ'..'ӿ' }) return true
        val lower = s.lowercase()
        return PROSE_STARTERS.any { lower.startsWith(it) }
    }

    /** Первая строка, начинающаяся на [prefix] (регистронезависимо); возвращает остаток; `null` — нет. */
    private fun lineAfter(raw: String, prefix: String): String? {
        raw.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith(prefix, ignoreCase = true)) {
                return t.substring(prefix.length).trim().ifEmpty { null }
            }
        }
        return null
    }

    /** Вычистить служебную строку (INFO/ASK): бэктики, маркеры списков, control-байты; до 160 символов. */
    private fun cleanLine(s: String): String? {
        val c = s.trim().trim('`').trimStart('#', '-', '*', '•', '>').trim()
            .filter { isSafeInputChar(it) }.trim()
        return c.ifEmpty { null }?.take(160)
    }

    private val PROSE_STARTERS = listOf(
        "please", "sorry", "could you", "can you", "which ", "what ", "i cannot", "i can't",
        "i'm ", "i am ", "unable", "clarify", "specify", "you need", "the request", "to run this",
    )
}
