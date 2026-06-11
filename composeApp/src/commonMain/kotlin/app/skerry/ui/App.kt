package app.skerry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.skerry.shared.platformName
import app.skerry.shared.vault.Vault
import app.skerry.ui.connection.ConnectionScreen
import app.skerry.ui.host.HostManagerScreen
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.vault.VaultGate

/**
 * Корень приложения. Граф зависимостей подаётся платформенной точкой входа через [deps]: на
 * desktop — sshj-транспорт плюс файловый менеджер хостов (живой SSH через [HostManagerScreen]).
 * Если есть транспорт, но нет менеджера — фолбэк на ручную форму [ConnectionScreen]. Где SSH-
 * транспорта ещё нет (мобильные таргеты подают пустой [AppDependencies]), показывается
 * плейсхолдер — паритет придёт с мобильным транспортом.
 *
 * Если в графе есть [Vault] (desktop), весь контент закрыт гейтом мастер-пароля ([VaultGate]):
 * создать/разблокировать хранилище перед доступом к хостам и сессиям (zero-knowledge).
 */
@Composable
fun App(deps: AppDependencies = AppDependencies()) {
    SkerryTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val vault = deps.vault
            if (vault != null) {
                VaultGate(vault) { onLock -> MainContent(deps, onLock) }
            } else {
                MainContent(deps, onLock = null)
            }
        }
    }
}

@Composable
private fun MainContent(deps: AppDependencies, onLock: (() -> Unit)?) {
    // Сюда попадаем уже за гейтом (vault разблокирован) — подгружаем секреты из vault.
    LaunchedEffect(deps.identities) { deps.identities?.reload() }
    val transport = deps.transport
    val hosts = deps.hosts
    if (transport != null && hosts != null) {
        HostManagerScreen(transport, hosts, identities = deps.identities, onLock = onLock)
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
