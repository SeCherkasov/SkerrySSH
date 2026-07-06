package app.skerry.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persists simple desktop UI prefs: each value is a small file `name` under [dir]
 * (`~/.config/skerry`). One shared template instead of readX/writeX pairs: read returns the
 * default when the file is missing/unreadable; write is best-effort (a persist failure never
 * crashes the UI).
 */
internal class FilePrefs(private val dir: Path) {

    /**
     * `0`/`1` flag. When [default] is true, anything but "0" reads as true (`info_panel` etc.);
     * when false, only an explicit "1" reads as true (`terminal_show_title`).
     */
    fun bool(name: String, default: Boolean): Boolean =
        runCatching { if (default) raw(name) != "0" else raw(name) == "1" }.getOrDefault(default)

    /** Integer value; missing/unreadable falls back to [default]. Range checks are the caller's job. */
    fun int(name: String, default: Int): Int =
        runCatching { raw(name).toInt() }.getOrDefault(default)

    /** Value by stable string id: [parse] throwing on an unknown value falls back to [default]. */
    fun <T> id(name: String, default: T, parse: (String) -> T): T =
        runCatching { parse(raw(name)) }.getOrDefault(default)

    /** List of strings, one per file line (order matters); missing/unreadable returns empty. */
    fun lines(name: String): List<String> =
        runCatching {
            Files.readAllLines(dir.resolve(name), StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

    fun set(name: String, value: Boolean) = set(name, if (value) "1" else "0")

    fun set(name: String, value: Int) = set(name, value.toString())

    /**
     * Writes a list one value per line. Values containing newlines can't be stored line-by-line, so
     * they're excluded (readAllLines splits on \n, \r, and \r\n).
     */
    fun setLines(name: String, values: List<String>) =
        set(name, values.filterNot { it.contains('\n') || it.contains('\r') }.joinToString("\n"))

    /** Raw write of a value (id/number as string); persist failures are swallowed, UI wins over the file. */
    fun set(name: String, value: String) {
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(name), value)
        }
    }

    private fun raw(name: String): String = Files.readString(dir.resolve(name)).trim()
}
