package app.skerry.ui.sync

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.stail_local_vault
import app.skerry.ui.generated.resources.stail_encrypted_on_device
import app.skerry.ui.generated.resources.stail_syncing
import app.skerry.ui.generated.resources.stail_synced_host
import app.skerry.ui.generated.resources.stail_synced
import app.skerry.ui.generated.resources.stail_linked_locked
import app.skerry.ui.generated.resources.stail_sync_error
import org.jetbrains.compose.resources.stringResource

/**
 * Чистая (тестируемая) проекция состояния sync на карточку профиля/аккаунта — desktop Settings →
 * Account и mobile More. Заменяет статический мок «Local vault / Maya Kovac»: реальная модель —
 * self-hosted zero-knowledge sync, аккаунт без биллинга. Не настроен → локальный vault с приглашением
 * настроить синхронизацию; есть привязка, но заперто → «linked, locked» (предложить переподключение);
 * активная сессия → accountId + хост сервера (и список устройств показывает уже UI).
 */
data class AccountCardModel(
    /** До двух символов для аватара. */
    val initials: String,
    val title: String,
    val subtitle: String,
    /** [SyncStatus.Online] — есть активная сессия: показываем устройства и «Disconnect». */
    val connected: Boolean,
    /** [SyncStatus.Configured] — привязка есть, но сессии нет (vault заперт): предлагаем «Reconnect». */
    val linked: Boolean,
) {
    /** Sync не настроен (или превью/ошибка) — карточка показывает локальный vault и «Set up sync». */
    val localOnly: Boolean get() = !connected && !linked
}

/**
 * Свести [SyncStatus] (и, для подзаголовка, URL сервера из сохранённой привязки) к [AccountCardModel].
 * [status] == null — sync-бэкенда нет (превью/офскрин): трактуем как локальный vault.
 */
fun accountCardModel(status: SyncStatus?, serverUrl: String? = null): AccountCardModel = when (status) {
    null, SyncStatus.Disabled -> localVaultCard("Encrypted on this device")
    SyncStatus.Busy -> localVaultCard("Syncing…")
    is SyncStatus.Online -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = serverHost(serverUrl)?.let { "Synced · $it" } ?: "Synced",
        connected = true,
        linked = false,
    )
    is SyncStatus.Configured -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = "Linked · locked",
        connected = false,
        linked = true,
    )
    // Ошибка синка показывается отдельной секцией Sync; карточка откатывается к локальному vault.
    is SyncStatus.Failed -> localVaultCard("Sync error")
}

private fun localVaultCard(subtitle: String) =
    AccountCardModel(initials = "S", title = "Local vault", subtitle = subtitle, connected = false, linked = false)

/**
 * UI-версия [accountCardModel] с локализованными title/subtitle. Чистая версия оставлена для тестов и
 * не-composable кода; здесь строки резолвятся через [stringResource]. accountId/host — данные, не мок,
 * поэтому не переводятся; переводятся статические подписи и «Local vault».
 */
@Composable
fun accountCardModelLocalized(status: SyncStatus?, serverUrl: String? = null): AccountCardModel = when (status) {
    null, SyncStatus.Disabled -> localizedLocalVaultCard(stringResource(Res.string.stail_encrypted_on_device))
    SyncStatus.Busy -> localizedLocalVaultCard(stringResource(Res.string.stail_syncing))
    is SyncStatus.Online -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = serverHost(serverUrl)?.let { stringResource(Res.string.stail_synced_host, it) }
            ?: stringResource(Res.string.stail_synced),
        connected = true,
        linked = false,
    )
    is SyncStatus.Configured -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = stringResource(Res.string.stail_linked_locked),
        connected = false,
        linked = true,
    )
    is SyncStatus.Failed -> localizedLocalVaultCard(stringResource(Res.string.stail_sync_error))
}

@Composable
private fun localizedLocalVaultCard(subtitle: String) = AccountCardModel(
    initials = "S",
    title = stringResource(Res.string.stail_local_vault),
    subtitle = subtitle,
    connected = false,
    linked = false,
)

/** Инициалы аватара: до двух ведущих букв/цифр локальной части accountId, в верхнем регистре. */
fun accountInitials(accountId: String): String {
    val local = accountId.substringBefore('@')
    val letters = local.filter { it.isLetterOrDigit() }
    return if (letters.isEmpty()) "S" else letters.take(2).uppercase()
}

/** Хост из URL сервера для подзаголовка (без схемы/порта/пути). null, если разобрать не вышло. */
fun serverHost(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val trimmed = url.trim()
    val authority = trimmed.substringAfter("://", trimmed).substringBefore('/').substringBefore('?').trim()
    // IPv6-литерал записывается в скобках (http://[::1]:8080) — наивный substringBefore(':') оставил бы
    // от него одну «[». Берём содержимое скобок как хост, порт после «]» игнорируем.
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        return if (close > 1) authority.substring(1, close) else null
    }
    val host = authority.substringBefore(':').trim()
    return host.ifEmpty { null }
}
