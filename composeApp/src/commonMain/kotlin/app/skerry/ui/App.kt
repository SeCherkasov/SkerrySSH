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
import app.skerry.ui.connection.ConnectionScreen
import app.skerry.ui.host.HostManagerScreen
import app.skerry.ui.theme.SkerryTheme

/**
 * Корень приложения. Граф зависимостей подаётся платформенной точкой входа через [deps]: на
 * desktop — sshj-транспорт плюс файловый менеджер хостов (живой SSH через [HostManagerScreen]).
 * Если есть транспорт, но нет менеджера — фолбэк на ручную форму [ConnectionScreen]. Где SSH-
 * транспорта ещё нет (мобильные таргеты подают пустой [AppDependencies]), показывается
 * плейсхолдер — паритет придёт с мобильным транспортом.
 */
@Composable
fun App(deps: AppDependencies = AppDependencies()) {
    val transport = deps.transport
    val hosts = deps.hosts
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
