package app.skerry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.skerry.shared.platformName
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.connection.ConnectionScreen
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.HostManagerScreen
import app.skerry.ui.theme.SkerryTheme

/**
 * Корень приложения. [transport] и [hosts] подаются платформенной точкой входа: на desktop —
 * sshj-реализация плюс файловый менеджер хостов (живой SSH через [HostManagerScreen]). Если
 * есть транспорт, но нет менеджера — фолбэк на ручную форму [ConnectionScreen]. Где SSH-
 * транспорта ещё нет (мобильные таргеты), показывается плейсхолдер — паритет придёт с
 * мобильным транспортом.
 */
@Composable
fun App(transport: SshTransport? = null, hosts: HostManagerController? = null) {
    SkerryTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (transport != null && hosts != null) {
                HostManagerScreen(transport, hosts)
            } else if (transport != null) {
                ConnectionScreen(transport)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Skerry · $platformName",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
    }
}
