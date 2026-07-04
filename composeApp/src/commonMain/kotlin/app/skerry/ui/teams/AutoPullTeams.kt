package app.skerry.ui.teams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.sync.SyncStatus

/**
 * Тянет общие хосты/сниппеты команд, КОГДА sync переходит в [SyncStatus.Online].
 *
 * Общие записи команд ходят team-каналом, а не аккаунтным: их шеринг на другом устройстве НЕ
 * будит аккаунтный WS-сигнал, поэтому без явного pull секции КОМАНДЫ оставались бы пустыми до
 * ручного захода на вкладку Teams. Триггерим по ПЕРЕХОДУ в Online, а не на mount: сразу после
 * мастер-пароля сессия ещё восстанавливается, и ранний `refresh()` вышел бы по NotConnected без
 * повтора. Ошибки синка гасит сам координатор ([TeamsCoordinator.markError]).
 *
 * Один вызов на экран, где показаны team-секции (мобильный список хостов, desktop-сайдбар) —
 * держит один паттерн в одном месте.
 */
@Composable
fun AutoPullTeamsOnOnline() {
    val teams = LocalTeams.current ?: return
    val online = LocalSync.current?.status?.collectAsState()?.value is SyncStatus.Online
    LaunchedEffect(online) { if (online) { teams.refresh(); teams.syncAll() } }
}
