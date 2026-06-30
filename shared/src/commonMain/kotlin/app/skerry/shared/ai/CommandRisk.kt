package app.skerry.shared.ai

/** Уровень риска предложенной AI shell-команды. */
enum class CommandRisk { None, Warn, Danger }

/**
 * Оценка команды: уровень + человекочитаемая причина ([reason] `null` только для [CommandRisk.None]).
 * [destructive] — команда теряет/затирает данные (удаление, перезапись, форматирование): UI красит её
 * красным даже на уровне [CommandRisk.Warn] (не деструктивные Warn вроде sudo/kill — янтарные).
 */
data class CommandAssessment(val risk: CommandRisk, val reason: String?, val destructive: Boolean = false) {
    companion object {
        val SAFE = CommandAssessment(CommandRisk.None, null)
    }
}

/**
 * Эвристический классификатор потенциально опасных команд для терминального AI-бара.
 *
 * Принцип «AI under policy»: вывод модели — недоверенный источник, поэтому перед подтверждением
 * деструктивные команды подсвечиваются. [CommandRisk.Danger] в UI требует отдельного арм-подтверждения
 * («Run anyway» → «Confirm run»), [CommandRisk.Warn] показывает предупреждение с причиной. Danger
 * проверяется раньше Warn; первое совпадение выигрывает.
 *
 * Это НЕ песочница и не полный парсер шелла — намеренно консервативная эвристика, ловящая классические
 * footgun'ы. Последнее слово всегда за пользователем; ложных срабатываний допускаем больше, чем пропусков.
 */
object CommandRiskClassifier {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    fun assess(command: String): CommandAssessment {
        val raw = command.trim()
        if (raw.isEmpty()) return CommandAssessment.SAFE
        // Нормализуем строку так, как её увидит шелл, чтобы закрыть класс обходов «regex ≠ парсер»:
        // снимаем экранирующие бэкслеши (r\m -\r\f → rm -rf), раскрываем $IFS/${'$'}{IFS} в пробел
        // (rm${'$'}{IFS}-rf → rm -rf) и убираем кавычки (rm -r "/" == rm -r /).
        val cmd = normalize(raw)
        val lower = cmd.lowercase()
        val compact = cmd.replace(" ", "")

        // ---------- DANGER ----------

        // Fork-бомба :(){ :|:& };: — а также переименованная (обратная ссылка на то же имя функции).
        if (compact.contains(":(){:|:&};:") ||
            rx(""":\s*\(\s*\)\s*\{[^}]*\|[^}]*&""").containsMatchIn(cmd) ||
            rx("""\b(\w+)\s*\(\s*\)\s*\{[^}]*\|[^}]*&[^}]*}\s*;\s*\1\b""").containsMatchIn(cmd)
        ) {
            return danger("Fork bomb — will exhaust system resources.")
        }

        // rm: рекурсивно + принудительно, либо рекурсивно по широкому пути (/, ~, $HOME, *)
        if (rx("""\brm\b""").containsMatchIn(lower)) {
            val recursive = rx("""\s-\w*r|\s--recursive""").containsMatchIn(lower)
            val force = rx("""\s-\w*f|\s--force""").containsMatchIn(lower)
            val broadTarget = rx("""\s(/|/\*|~|~/\*|${'$'}home|\*)(\s|;|&|\||$)""").containsMatchIn(lower)
            if (recursive && force) return danger("Recursive force delete — irreversible data loss.")
            if (recursive && broadTarget) return danger("Recursive delete of a broad path — irreversible data loss.")
        }

        // Прямая запись на дисковое устройство / форматирование
        if (rx("""\bdd\b.*\bof=/dev/""").containsMatchIn(lower) ||
            rx("""\b(mkfs\S*|wipefs|fdisk|parted|shred|blkdiscard)\b""").containsMatchIn(lower) ||
            rx(""">\s*/dev/(sd|nvme|vd|hd|mmcblk|disk|xvd)""").containsMatchIn(lower)
        ) {
            return danger("Writes directly to a disk device — can destroy the filesystem.")
        }

        // Скачать и сразу отдать в шелл (удалённый неверифицированный код)
        if (rx("""\b(curl|wget|fetch)\b.*\|\s*(sudo\s+)?(sh|bash|zsh|ksh|dash|python\d?|perl|ruby|node)\b""").containsMatchIn(lower)) {
            return danger("Pipes a downloaded script straight into a shell — runs unverified remote code.")
        }

        // Любой пайп в интерпретатор (шелл ИЛИ python/perl/ruby/node, в т.ч. base64 -d | python3) —
        // исполнение сконструированного/декодированного кода. Список интерпретаторов совпадает с curl|… выше.
        if (rx("""\|\s*(sudo\s+)?(sh|bash|zsh|ksh|dash|python\d?|perl|ruby|node)\b""").containsMatchIn(lower)) {
            return danger("Pipes output into an interpreter — runs constructed or remote code.")
        }

        // rsync --delete — зеркальное удаление, стирает всё в приёмнике, чего нет в источнике
        if (rx("""\brsync\b.*(--delete|--del)\b""").containsMatchIn(lower)) {
            return danger("Mirror delete — wipes files in the destination that are absent from the source.")
        }

        // Выключение / перезагрузка
        if (rx("""\b(shutdown|reboot|poweroff|halt)\b""").containsMatchIn(lower) ||
            rx("""\binit\s+[06]\b""").containsMatchIn(lower) ||
            rx("""\bsystemctl\s+(poweroff|reboot|halt)\b""").containsMatchIn(lower)
        ) {
            return danger("Powers off or reboots the machine.")
        }

        // Рекурсивная смена прав/владельца по системному пути
        if (rx("""\bch(mod|own)\b.*\s-\w*r""").containsMatchIn(lower) &&
            rx("""\s(/|~|${'$'}home)(\s|$)""").containsMatchIn(lower)
        ) {
            return danger("Recursive permission/ownership change on a system path.")
        }

        // Перезапись security-критичных файлов (через > или tee)
        if (rx(""">\s*\S*authorized_keys|>\s*/etc/(passwd|shadow|sudoers)""").containsMatchIn(lower) ||
            rx("""\btee\b\s+\S*(authorized_keys|/etc/(passwd|shadow|sudoers))""").containsMatchIn(lower)
        ) {
            return danger("Overwrites a security-critical file.")
        }

        // Сброс фаервола — можно отрезать себе доступ
        if (rx("""\biptables\b.*\s(-f|--flush)\b|\bufw\s+disable\b|\bnft\s+flush\b""").containsMatchIn(lower)) {
            return danger("Flushes firewall rules — may cut off remote access.")
        }

        // ---------- WARN ----------

        if (rx("""\brm\b\s+.*-\w*[rf]|\brm\b\s+[^-]""").containsMatchIn(lower)) {
            return warn("Deletes files.", destructive = true)
        }
        if (rx("""\b(kill|killall|pkill)\b""").containsMatchIn(lower)) {
            return warn("Terminates running processes.")
        }
        if (rx("""\b(apt|apt-get|dnf|yum|pacman|zypper|brew|pip\d?|npm)\b.*\b(remove|uninstall|purge|erase|autoremove)\b""").containsMatchIn(lower) ||
            rx("""\bpacman\s+-r\w*""").containsMatchIn(lower)
        ) {
            return warn("Uninstalls packages.")
        }
        if (rx("""\bgit\b.*\b(reset\s+--hard|clean\s+-\w*f|push\s+.*(--force|-f)\b)""").containsMatchIn(lower)) {
            return warn("Discards local changes or rewrites history.", destructive = true)
        }
        if (rx("""\bchmod\s+(-\w+\s+)?[0-7]*777\b""").containsMatchIn(lower)) {
            return warn("Grants world-writable permissions.")
        }
        if (rx("""\bsystemctl\s+(stop|disable|mask)\b|\bservice\s+\S+\s+stop\b""").containsMatchIn(lower)) {
            return warn("Stops or disables a service.")
        }
        if (rx("""\bfind\b.*-delete|\bfind\b.*-exec\s+rm""").containsMatchIn(lower)) {
            return warn("Bulk-deletes matched files.", destructive = true)
        }
        if (rx("""\btruncate\b|\bmv\b.*\s/dev/null\b|:\s*>\s*\S""").containsMatchIn(lower)) {
            return warn("Discards file contents.", destructive = true)
        }
        if (rx("""\bsudo\b|\bsu\s""").containsMatchIn(lower)) {
            return warn("Runs with elevated privileges.")
        }

        return CommandAssessment.SAFE
    }

    /**
     * Привести команду к тому виду, в котором её разберёт шелл, — чтобы простое экранирование не
     * протаскивало опасную команду мимо literal-регэкспов. Известный не покрытый обход: косвенность
     * через переменные (`T=/dev/sda; dd of=$T`) — требует отслеживания присваиваний; тут сознательно
     * не ловим (основной гейт — подтверждение пользователя перед выполнением).
     */
    private fun normalize(command: String): String {
        var s = command
        // $IFS / ${'$'}{IFS} шелл раскрывает в разделитель — подставляем пробел.
        s = s.replace("${'$'}{IFS}", " ").replace("${'$'}IFS", " ")
        // Снять одиночные экранирующие бэкслеши: \x → x (как это делает шелл вне кавычек).
        s = s.replace(Regex("""\\(.)""")) { it.groupValues[1] }
        // Кавычки не меняют исполнение команды/пути — убираем, чтобы `rm -r "/"` == `rm -r /`.
        s = s.replace("\"", "").replace("'", "")
        // Схлопнуть повторные пробелы (после раскрытия $IFS их может стать много).
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun danger(reason: String) = CommandAssessment(CommandRisk.Danger, reason, destructive = true)
    private fun warn(reason: String, destructive: Boolean = false) = CommandAssessment(CommandRisk.Warn, reason, destructive)
}
