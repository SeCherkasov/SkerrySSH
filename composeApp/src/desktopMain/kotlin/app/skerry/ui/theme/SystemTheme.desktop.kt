package app.skerry.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Desktop reactive system-dark-theme read. Compose Multiplatform freezes the desktop value at
 * composition, so we detect the OS color scheme ourselves and track changes.
 *
 * On Linux the XDG portal pushes a `SettingChanged` signal, so we watch `gdbus monitor` and react
 * the instant the OS flips — no periodic subprocesses, easy on the battery. Windows/macOS have no
 * uniform push channel, so they fall back to a short poll. The initial value is read synchronously
 * (one subprocess at startup) so the first frame already matches the OS with no dark→light flash.
 */
@Composable
actual fun systemInDarkTheme(): Boolean {
    val initial = remember { detectSystemDark() ?: true }
    val dark by produceState(initial) {
        val os = System.getProperty("os.name", "").lowercase()
        val linux = !(os.contains("win") || os.contains("mac") || os.contains("darwin"))
        if (linux) watchLinuxColorScheme { value = it } else pollColorScheme { value = it }
    }
    return dark
}

private const val SYSTEM_THEME_POLL_MS = 1_500L

/**
 * Linux: react to XDG portal `SettingChanged` signals via `gdbus monitor`. The blocking reader runs
 * on a daemon thread; cancelling the composition destroys the process, which unblocks the read. If
 * `gdbus` is missing or the monitor exits on its own (e.g. portal restart), fall back to polling.
 */
private suspend fun CoroutineScope.watchLinuxColorScheme(onDark: (Boolean) -> Unit) {
    val process = runCatching {
        ProcessBuilder(
            "gdbus", "monitor", "--session",
            "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop",
        ).redirectErrorStream(true).start()
    }.getOrNull()

    if (process == null) {
        pollColorScheme(onDark)
        return
    }

    // Re-sync once the monitor is attached, in case the OS flipped between the initial read and now.
    withContext(Dispatchers.IO) { detectSystemDark() }?.let(onDark)

    try {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { process.destroyForcibly() }
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            // SettingChanged for org.freedesktop.appearance color-scheme → re-read the value.
                            if (line.contains("color-scheme")) detectSystemDark()?.let(onDark)
                        }
                    }
                }
                if (continuation.isActive) continuation.resume(Unit)
            }.apply { isDaemon = true; name = "skerry-theme-watch" }.start()
        }
    } finally {
        process.destroyForcibly()
    }

    // Monitor ended without cancellation (e.g. portal restart) → keep tracking via polling.
    if (isActive) pollColorScheme(onDark)
}

/** Fallback for platforms without a push channel: re-detect on a short interval. */
private suspend fun CoroutineScope.pollColorScheme(onDark: (Boolean) -> Unit) {
    while (isActive) {
        delay(SYSTEM_THEME_POLL_MS)
        withContext(Dispatchers.IO) { detectSystemDark() }?.let(onDark)
    }
}

/** Current OS dark-mode state, or null if it can't be determined (caller keeps its last value). */
internal fun detectSystemDark(): Boolean? {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("win") -> runCmd(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        )?.let { parseWindowsDark(it.first, it.second) }

        os.contains("mac") || os.contains("darwin") ->
            runCmd("defaults", "read", "-g", "AppleInterfaceStyle")?.let { parseMacDark(it.first, it.second) }

        else -> linuxDark()
    }
}

/** Linux: the XDG desktop portal is host-accurate and Flatpak-safe; gsettings is a non-portal fallback. */
private fun linuxDark(): Boolean? {
    runCmd(
        "gdbus", "call", "--session",
        "--dest", "org.freedesktop.portal.Desktop",
        "--object-path", "/org/freedesktop/portal/desktop",
        "--method", "org.freedesktop.portal.Settings.Read",
        "org.freedesktop.appearance", "color-scheme",
    )?.let { parsePortalColorScheme(it.first, it.second)?.let { dark -> return dark } }

    runCmd("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
        ?.let { parseGsettingsColorScheme(it.first, it.second)?.let { dark -> return dark } }

    return null
}

// --- Pure parsers (unit-tested; the subprocess plumbing above is the only impure part) ---

/** Windows `AppsUseLightTheme` DWORD: 0 = dark, 1 = light. */
internal fun parseWindowsDark(exitCode: Int, output: String): Boolean? {
    if (exitCode != 0) return null
    val hex = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").find(output) ?: return null
    return hex.groupValues[1].toInt(16) == 0
}

/** macOS `AppleInterfaceStyle` is "Dark" only in dark mode; in light mode the key is absent (non-zero exit). */
internal fun parseMacDark(exitCode: Int, output: String): Boolean =
    exitCode == 0 && output.trim().equals("Dark", ignoreCase = true)

/** XDG portal `org.freedesktop.appearance color-scheme`: 1 = prefer dark, 2 = prefer light, 0 = no preference. */
internal fun parsePortalColorScheme(exitCode: Int, output: String): Boolean? {
    if (exitCode != 0) return null
    val value = Regex("uint32\\s+(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return when (value) {
        1 -> true
        2 -> false
        else -> null // 0 = no preference → let the caller fall back
    }
}

/** GNOME `color-scheme`: `'prefer-dark'` → dark; `'default'` / `'prefer-light'` → light. */
internal fun parseGsettingsColorScheme(exitCode: Int, output: String): Boolean? {
    if (exitCode != 0) return null
    return output.contains("dark", ignoreCase = true)
}

/** Runs a command with a short timeout; returns (exitCode, stdout) or null if it can't run. */
private fun runCmd(vararg command: String): Pair<Int, String>? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(false).start()
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    process.exitValue() to process.inputStream.bufferedReader().readText()
}.getOrNull()
