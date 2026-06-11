package app.skerry.ui

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.skerry.shared.ssh.FileKnownHostsStore
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.TofuHostKeyVerifier
import java.nio.file.Path

/**
 * Каталог конфигурации Skerry. По умолчанию `~/.config/skerry`; уважает XDG_CONFIG_HOME.
 */
private fun configDir(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".config")
    return base.resolve("skerry")
}

fun main() = application {
    // TOFU: первый ключ хоста запоминается в known_hosts, при смене ключа — отказ.
    // Интерактивное подтверждение отпечатка появится с UI менеджера хостов.
    val knownHosts = FileKnownHostsStore(configDir().resolve("known_hosts"))
    val transport = SshjTransport(TofuHostKeyVerifier(knownHosts))
    Window(
        onCloseRequest = ::exitApplication,
        title = "Skerry",
    ) {
        App(transport)
    }
}
