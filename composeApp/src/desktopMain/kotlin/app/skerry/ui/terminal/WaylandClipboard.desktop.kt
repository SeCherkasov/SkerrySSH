package app.skerry.ui.terminal

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit

/**
 * Доступ к буферам Wayland через утилиты `wl-clipboard` (`wl-copy`/`wl-paste`). Нужен по двум причинам:
 *
 * 1. **PRIMARY на Wayland.** AWT `getSystemSelection()` под Wayland возвращает `null` (PRIMARY там вне
 *    XWayland), поэтому средний клик не видел выделение из других окон. `wl-paste --primary` читает
 *    настоящий Wayland-PRIMARY, `wl-copy --primary` его публикует.
 * 2. **Тишина в логах при вставке.** Чтение CLIPBOARD через AWT (`Clipboard.getContents`) при чужом
 *    сериализованном flavor'е (буфер, скопированный из IntelliJ) безусловно печатает стек JDK в
 *    System.err. `wl-paste` не трогает AWT вовсе — трасса не возникает.
 *
 * Активен только в Wayland-сессии при наличии обеих утилит в PATH; иначе вызывающий откатывается на
 * прежний AWT/Compose-путь (X11, Windows, macOS, headless). Пути к бинарям резолвятся в АБСОЛЮТНЫЕ
 * один раз — `ProcessBuilder` не ищет их по PATH во время работы (нет подмены через изменённый PATH).
 *
 * ВАЖНО: пользовательский/серверный текст НИКОГДА не попадает в аргументы процесса — только в stdin
 * (`wl-copy`) либо читается из stdout (`wl-paste`). Имена утилит — литералы из исходника.
 */
internal object WaylandClipboard {

    /** Потолок чтения буфера (8 MiB): защита от OOM, если владелец буфера отдаёт гигантский payload. */
    private const val MAX_PASTE_BYTES = 8 * 1024 * 1024

    private val isWaylandSession: Boolean
        get() = System.getenv("WAYLAND_DISPLAY")?.isNotBlank() == true ||
            System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true

    private val wlCopyPath: String? by lazy { if (isWaylandSession) resolveOnPath("wl-copy") else null }
    private val wlPastePath: String? by lazy { if (isWaylandSession) resolveOnPath("wl-paste") else null }

    /** Wayland-сессия и обе утилиты найдены в PATH — вычисляем один раз за процесс. */
    val available: Boolean by lazy { wlCopyPath != null && wlPastePath != null }

    /**
     * Прочитать текст из CLIPBOARD (или PRIMARY при [primary]). `--no-newline` снимает добавляемый
     * утилитой завершающий перевод строки. Пустой вывод, отсутствие утилиты или любой сбой (нет данных,
     * не текст, таймаут, превышение лимита) → `null`, чтобы вызывающий откатился на штатный путь.
     */
    fun paste(primary: Boolean): String? {
        val bin = wlPastePath ?: return null
        return runCatching {
            val args = buildList {
                add(bin)
                add("--no-newline")
                if (primary) add("--primary")
                // Текстовый тип явно: иначе при графическом содержимом буфера wl-paste отдал бы не-текст.
                add("--type"); add("text/plain")
            }
            // stderr → DISCARD: иначе непрочитанный pipe stderr может переполниться и подвесить процесс.
            val proc = ProcessBuilder(args).redirectError(Redirect.DISCARD).start()
            // Читаем не более лимита: при переполнении wl-paste заблокируется на записи, waitFor отвалится
            // по таймауту, процесс убьём — вернём null (лучше пустая вставка, чем OOM).
            val bytes = proc.inputStream.use { it.readNBytes(MAX_PASTE_BYTES) }
            if (!proc.waitFor(2, TimeUnit.SECONDS)) { proc.destroyForcibly(); return null }
            // wl-paste отдаёт код 1, когда буфер пуст или нет запрошенного типа — это не ошибка, просто null.
            if (proc.exitValue() != 0) null else bytes.toString(Charsets.UTF_8).ifEmpty { null }
        }.getOrNull()
    }

    /**
     * Записать [text] в CLIPBOARD (или PRIMARY при [primary]). `wl-copy` сам отвязывается (форкает
     * демон, держащий буфер), поэтому процесс завершается сразу. `true` — записали, `false` — сбой/нет утилиты.
     */
    fun copy(text: String, primary: Boolean): Boolean {
        val bin = wlCopyPath ?: return false
        return runCatching {
            val args = buildList {
                add(bin)
                if (primary) add("--primary")
                add("--type"); add("text/plain")
            }
            // stdout/stderr → DISCARD (нам нужен только stdin); непрочитанные pipe'ы иначе могут подвесить.
            val proc = ProcessBuilder(args)
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start()
            proc.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0
        }.getOrDefault(false)
    }

    /**
     * Найти исполняемый файл [name] в PATH и вернуть АБСОЛЮТНЫЙ путь (или null). Без `sh -c` — нет
     * shell-инъекции и нет subprocess'а ради проверки; путь фиксируется, чтобы во время работы
     * `ProcessBuilder` не перерезолвил имя по подменённому PATH.
     */
    private fun resolveOnPath(name: String): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
}
