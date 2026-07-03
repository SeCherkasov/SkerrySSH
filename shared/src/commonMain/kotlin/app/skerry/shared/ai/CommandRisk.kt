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

    // Все регэкспы скомпилированы один раз при инициализации объекта: assess() зовётся на каждый
    // ввод/предложение AI, перекомпиляция ~25 паттернов на вызов была бы чистой потерей.

    // ---------- DANGER ----------
    private val FORK_BOMB_ANON = rx(""":\s*\(\s*\)\s*\{[^}]*\|[^}]*&""")
    private val FORK_BOMB_NAMED = rx("""\b(\w+)\s*\(\s*\)\s*\{[^}]*\|[^}]*&[^}]*}\s*;\s*\1\b""")
    private val RM_WORD = rx("""\brm\b""")
    private val RM_RECURSIVE = rx("""\s-\w*r|\s--recursive""")
    private val RM_FORCE = rx("""\s-\w*f|\s--force""")
    private val RM_BROAD_TARGET = rx("""\s(/|/\*|~|~/\*|${'$'}home|\*)(\s|;|&|\||$)""")
    private val DD_TO_DEVICE = rx("""\bdd\b.*\bof=/dev/""")
    private val DISK_TOOLS = rx("""\b(mkfs\S*|wipefs|fdisk|parted|shred|blkdiscard)\b""")
    private val REDIRECT_TO_DISK = rx(""">\s*/dev/(sd|nvme|vd|hd|mmcblk|disk|xvd)""")
    private val DOWNLOAD_TO_SHELL = rx("""\b(curl|wget|fetch)\b.*\|\s*(sudo\s+)?(sh|bash|zsh|ksh|dash|python\d?|perl|ruby|node)\b""")
    private val PIPE_TO_INTERPRETER = rx("""\|\s*(sudo\s+)?(sh|bash|zsh|ksh|dash|python\d?|perl|ruby|node)\b""")
    private val RSYNC_DELETE = rx("""\brsync\b.*(--delete|--del)\b""")
    private val POWER_OFF = rx("""\b(shutdown|reboot|poweroff|halt)\b""")
    private val INIT_0_6 = rx("""\binit\s+[06]\b""")
    private val SYSTEMCTL_POWER = rx("""\bsystemctl\s+(poweroff|reboot|halt)\b""")
    private val CHMOD_CHOWN_RECURSIVE = rx("""\bch(mod|own)\b.*\s-\w*r""")
    private val SYSTEM_PATH = rx("""\s(/|~|${'$'}home)(\s|$)""")
    private val REDIRECT_TO_SECURITY_FILE = rx(""">\s*\S*authorized_keys|>\s*/etc/(passwd|shadow|sudoers)""")
    private val TEE_TO_SECURITY_FILE = rx("""\btee\b\s+\S*(authorized_keys|/etc/(passwd|shadow|sudoers))""")
    private val FIREWALL_FLUSH = rx("""\biptables\b.*\s(-f|--flush)\b|\bufw\s+disable\b|\bnft\s+flush\b""")

    // ---------- WARN ----------
    private val RM_ANY = rx("""\brm\b\s+.*-\w*[rf]|\brm\b\s+[^-]""")
    private val KILL_PROCESS = rx("""\b(kill|killall|pkill)\b""")
    private val PKG_REMOVE = rx("""\b(apt|apt-get|dnf|yum|pacman|zypper|brew|pip\d?|npm)\b.*\b(remove|uninstall|purge|erase|autoremove)\b""")
    private val PACMAN_REMOVE = rx("""\bpacman\s+-r\w*""")
    private val GIT_DESTRUCTIVE = rx("""\bgit\b.*\b(reset\s+--hard|clean\s+-\w*f|push\s+.*(--force|-f)\b)""")
    private val CHMOD_777 = rx("""\bchmod\s+(-\w+\s+)?[0-7]*777\b""")
    private val SERVICE_STOP = rx("""\bsystemctl\s+(stop|disable|mask)\b|\bservice\s+\S+\s+stop\b""")
    private val FIND_DELETE = rx("""\bfind\b.*-delete|\bfind\b.*-exec\s+rm""")
    private val TRUNCATE_CONTENT = rx("""\btruncate\b|\bmv\b.*\s/dev/null\b|:\s*>\s*\S""")
    private val ELEVATED = rx("""\bsudo\b|\bsu\s""")

    // ---------- normalize ----------
    private val ESCAPED_CHAR = Regex("""\\(.)""")
    private val WHITESPACE_RUN = Regex("""\s+""")

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
            FORK_BOMB_ANON.containsMatchIn(cmd) ||
            FORK_BOMB_NAMED.containsMatchIn(cmd)
        ) {
            return danger("Fork bomb — will exhaust system resources.")
        }

        // rm: рекурсивно + принудительно, либо рекурсивно по широкому пути (/, ~, $HOME, *)
        if (RM_WORD.containsMatchIn(lower)) {
            val recursive = RM_RECURSIVE.containsMatchIn(lower)
            val force = RM_FORCE.containsMatchIn(lower)
            val broadTarget = RM_BROAD_TARGET.containsMatchIn(lower)
            if (recursive && force) return danger("Recursive force delete — irreversible data loss.")
            if (recursive && broadTarget) return danger("Recursive delete of a broad path — irreversible data loss.")
        }

        // Прямая запись на дисковое устройство / форматирование
        if (DD_TO_DEVICE.containsMatchIn(lower) ||
            DISK_TOOLS.containsMatchIn(lower) ||
            REDIRECT_TO_DISK.containsMatchIn(lower)
        ) {
            return danger("Writes directly to a disk device — can destroy the filesystem.")
        }

        // Скачать и сразу отдать в шелл (удалённый неверифицированный код)
        if (DOWNLOAD_TO_SHELL.containsMatchIn(lower)) {
            return danger("Pipes a downloaded script straight into a shell — runs unverified remote code.")
        }

        // Любой пайп в интерпретатор (шелл ИЛИ python/perl/ruby/node, в т.ч. base64 -d | python3) —
        // исполнение сконструированного/декодированного кода. Список интерпретаторов совпадает с curl|… выше.
        if (PIPE_TO_INTERPRETER.containsMatchIn(lower)) {
            return danger("Pipes output into an interpreter — runs constructed or remote code.")
        }

        // rsync --delete — зеркальное удаление, стирает всё в приёмнике, чего нет в источнике
        if (RSYNC_DELETE.containsMatchIn(lower)) {
            return danger("Mirror delete — wipes files in the destination that are absent from the source.")
        }

        // Выключение / перезагрузка
        if (POWER_OFF.containsMatchIn(lower) ||
            INIT_0_6.containsMatchIn(lower) ||
            SYSTEMCTL_POWER.containsMatchIn(lower)
        ) {
            return danger("Powers off or reboots the machine.")
        }

        // Рекурсивная смена прав/владельца по системному пути
        if (CHMOD_CHOWN_RECURSIVE.containsMatchIn(lower) &&
            SYSTEM_PATH.containsMatchIn(lower)
        ) {
            return danger("Recursive permission/ownership change on a system path.")
        }

        // Перезапись security-критичных файлов (через > или tee)
        if (REDIRECT_TO_SECURITY_FILE.containsMatchIn(lower) ||
            TEE_TO_SECURITY_FILE.containsMatchIn(lower)
        ) {
            return danger("Overwrites a security-critical file.")
        }

        // Сброс фаервола — можно отрезать себе доступ
        if (FIREWALL_FLUSH.containsMatchIn(lower)) {
            return danger("Flushes firewall rules — may cut off remote access.")
        }

        // ---------- WARN ----------

        if (RM_ANY.containsMatchIn(lower)) {
            return warn("Deletes files.", destructive = true)
        }
        if (KILL_PROCESS.containsMatchIn(lower)) {
            return warn("Terminates running processes.")
        }
        if (PKG_REMOVE.containsMatchIn(lower) ||
            PACMAN_REMOVE.containsMatchIn(lower)
        ) {
            return warn("Uninstalls packages.")
        }
        if (GIT_DESTRUCTIVE.containsMatchIn(lower)) {
            return warn("Discards local changes or rewrites history.", destructive = true)
        }
        if (CHMOD_777.containsMatchIn(lower)) {
            return warn("Grants world-writable permissions.")
        }
        if (SERVICE_STOP.containsMatchIn(lower)) {
            return warn("Stops or disables a service.")
        }
        if (FIND_DELETE.containsMatchIn(lower)) {
            return warn("Bulk-deletes matched files.", destructive = true)
        }
        if (TRUNCATE_CONTENT.containsMatchIn(lower)) {
            return warn("Discards file contents.", destructive = true)
        }
        if (ELEVATED.containsMatchIn(lower)) {
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
        s = s.replace(ESCAPED_CHAR) { it.groupValues[1] }
        // Кавычки не меняют исполнение команды/пути — убираем, чтобы `rm -r "/"` == `rm -r /`.
        s = s.replace("\"", "").replace("'", "")
        // Схлопнуть повторные пробелы (после раскрытия $IFS их может стать много).
        return s.replace(WHITESPACE_RUN, " ").trim()
    }

    private fun danger(reason: String) = CommandAssessment(CommandRisk.Danger, reason, destructive = true)
    private fun warn(reason: String, destructive: Boolean = false) = CommandAssessment(CommandRisk.Warn, reason, destructive)
}
