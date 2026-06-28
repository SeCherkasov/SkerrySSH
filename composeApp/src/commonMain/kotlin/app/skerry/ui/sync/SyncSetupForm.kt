package app.skerry.ui.sync

/**
 * Чистая (тестируемая) валидация формы настройки self-hosted sync — модалка-онбординг, которой нет
 * в макете: подключение требует адреса сервера, идентификатора аккаунта и мастер-пароля. Сам пароль
 * в модель не кладём (он живёт CharArray-ом в composable и затирается после отправки) — здесь только
 * его длина. Поля нормализуются (trim) так же, как их потом получит [SyncCoordinator].
 */
data class SyncSetupForm(
    val serverUrl: String = "",
    val accountId: String = "",
) {
    /** URL сервера без хвостовых пробелов (как уйдёт в [SyncCoordinator]). */
    val normalizedServerUrl: String get() = serverUrl.trim()

    /** Идентификатор аккаунта без хвостовых пробелов. */
    val normalizedAccountId: String get() = accountId.trim()

    /**
     * Готова ли форма к отправке: непустой http(s)-адрес сервера, непустой accountId и непустой
     * пароль. Схема URL проверяется явно — чтобы не отправлять SRP на заведомо неверный endpoint
     * (ssh://, голый хост) и дать осмысленную ошибку до сетевого вызова.
     */
    fun canSubmit(passwordLength: Int): Boolean =
        passwordLength > 0 && normalizedAccountId.isNotEmpty() && isHttpUrl(normalizedServerUrl)

    private fun isHttpUrl(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) && url.length > "https://".length
}
