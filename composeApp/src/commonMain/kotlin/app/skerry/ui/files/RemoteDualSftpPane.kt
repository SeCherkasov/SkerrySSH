package app.skerry.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.shared.sftp.SftpClient
import app.skerry.ui.theme.SkerryColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Этап открытия SFTP-канала для двухпанельного режима. Переходы ОДНОНАПРАВЛЕННЫЕ
 * (`Opening` → `Ready`/`Failed`, назад не возвращается) — поэтому `paneScope` и `DisposableEffect`
 * живут в ветке `Ready` без риска утечки: из `Ready` композиция уходит только при выходе панели.
 */
private sealed interface DualOpen {
    data object Opening : DualOpen
    data class Ready(val client: SftpClient) : DualOpen
    data class Failed(val message: String) : DualOpen
}

/**
 * Двухпанельная SFTP-панель активной сессии: открывает SFTP-канал через [openSftp] и держит над ним
 * [TransferCoordinator] с локальной ([platformLocalBrowser]) и удалённой ([SftpFileBrowser]) панелями.
 * Канал — собственность панели: закрывается в [DisposableEffect] при уходе из композиции (как
 * [app.skerry.ui.sftp.RemoteSftpPane]). [scope] — долгоживущий scope экрана: операции контроллеров и
 * фоновое закрытие канала под [NonCancellable] переживают уход панели из композиции. [hostLabel] —
 * метка удалённой панели (имя/подзаголовок хоста).
 */
@Composable
fun RemoteDualSftpPane(
    openSftp: suspend () -> SftpClient,
    hostLabel: String,
    scope: CoroutineScope,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    val opened by produceState<DualOpen>(DualOpen.Opening, openSftp) {
        value = try {
            DualOpen.Ready(openSftp())
        } catch (e: CancellationException) {
            throw e // тихая отмена (ушли с панели до открытия канала)
        } catch (e: Exception) {
            DualOpen.Failed(e.message ?: "Не удалось открыть SFTP")
        }
    }

    when (val state = opened) {
        DualOpen.Opening -> PaneCentered(modifier) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text("Открываем SFTP…", color = SkerryColors.textDim, fontSize = 13.sp)
        }

        is DualOpen.Failed -> PaneCentered(modifier) {
            Text("SFTP недоступен", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            Text(state.message, color = SkerryColors.textDim, fontSize = 12.sp)
        }

        is DualOpen.Ready -> {
            val client = state.client
            // Дочерний scope панели: операции контроллеров живут на нём, в onDispose мы его отменяем
            // (висящие list/передачи не упадут на закрытом канале), затем закрываем сам канал на
            // долгоживущем [scope] под NonCancellable.
            val paneScope = remember(client) {
                CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            }
            // Ключ — ТОЛЬКО client (идентичность канала): [hostLabel] это display-метка, а не ресурс.
            // Включи её в ключ — и смена строки подзаголовка пересоздала бы координатор поверх старого
            // [paneScope] (тот keyed только на client), потеряв путь/выделение панелей и пересоздав start().
            val coordinator = remember(client) {
                TransferCoordinator(
                    sftp = client,
                    local = FilePaneController(platformLocalBrowser(), paneScope),
                    remote = FilePaneController(SftpFileBrowser(client, hostLabel), paneScope),
                    scope = paneScope,
                )
            }
            LaunchedEffect(coordinator) {
                coordinator.local.start()
                coordinator.remote.start()
            }
            DisposableEffect(client) {
                onDispose {
                    paneScope.cancel()
                    scope.launch(NonCancellable) { runCatching { client.close() } }
                }
            }
            DualPaneSftpScreen(coordinator, mono, modifier)
        }
    }
}

@Composable
private fun PaneCentered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(SkerryColors.nightSea), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}
