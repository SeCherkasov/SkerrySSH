package app.skerry.ui

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.SshjTransport

/**
 * ВРЕМЕННЫЙ верификатор: принимает любой ключ хоста. Это дыра в безопасности (нет защиты
 * от MITM) и существует только чтобы дотянуть терминал до живого SSH на этом этапе.
 * Заменяется персистентным known-hosts + TOFU-подтверждением вместе с менеджером хостов —
 * это следующий шаг по плану в CLAUDE.md.
 */
private val trustAllHostKeys = HostKeyVerifier { _, _, _, _ -> true }

fun main() = application {
    val transport = SshjTransport(trustAllHostKeys)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Skerry",
    ) {
        App(transport)
    }
}
