package app.skerry.ui.sync

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
