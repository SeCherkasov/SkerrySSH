package app.skerry.ui.sync

import app.skerry.shared.platformName
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MasterKey
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Где приложение хранит настройку sync (URL сервера, accountId, deviceId) между запусками. */
interface SyncConfigStore {
    fun load(): SyncConfig?
    fun save(config: SyncConfig)
    fun clear()
}

/**
 * Сохранённая привязка к серверу. По умолчанию токены НЕ храним (переавторизация по паролю).
 * Если пользователь включил «keep connected» ([keepConnected]), храним refresh-токен —
 * но **запечатанным под dataKey vault** ([sealedRefreshToken], hex шифротекста): без разблокировки
 * vault он бесполезен, так что кража файла конфигурации не даёт доступ к данным (zero-knowledge).
 */
data class SyncConfig(
    val serverUrl: String,
    val accountId: String,
    val deviceId: String,
    val keepConnected: Boolean = false,
    val sealedRefreshToken: String? = null,
)

class InMemorySyncConfigStore : SyncConfigStore {
    private var config: SyncConfig? = null
    override fun load(): SyncConfig? = config
    override fun save(config: SyncConfig) { this.config = config }
    override fun clear() { config = null }
}

/** Видимое UI состояние подключения к sync. */
sealed interface SyncStatus {
    /** Sync не настроен на этом устройстве (нет сохранённой привязки). */
    data object Disabled : SyncStatus
    data object Busy : SyncStatus

    /**
     * Привязка к серверу есть (пережила перезапуск), но активной сессии нет — токены не персистятся
     * (zero-knowledge, design §4). Нужен повторный ввод мастер-пароля; сервер/аккаунт уже известны.
     */
    data class Configured(val serverUrl: String, val accountId: String) : SyncStatus
    data class Online(val accountId: String, val lastPushed: Int, val lastPulled: Int) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * App-level склейка self-hosted sync (`docs/skerry-sync-design.md`): связывает [SyncClient],
 * [VaultCrypto] и локальный [Vault] в операции register/login/sync для UI. Zero-knowledge —
 * мастер-пароль и dataKey не покидают устройство; на сервер уходят SRP-верификатор и шифроблобы.
 *
 * Соль деривации masterKey выводится из accountId ([VaultCrypto.deriveSyncSalt]) — design §1,
 * чтобы другое устройство могло войти одним мастер-паролем. Требует разблокированного vault
 * (нужен dataKey для серверной обёртки). [clientFactory] строит сетевой клиент под URL —
 * платформенная реализация (KtorSyncClient на JVM/Android).
 */
class SyncCoordinator(
    private val clientFactory: (serverUrl: String) -> SyncClient,
    private val crypto: VaultCrypto,
    private val vault: Vault,
    private val configStore: SyncConfigStore = InMemorySyncConfigStore(),
    private val syncState: SyncStateStore = InMemorySyncStateStore(),
    private val deviceIdProvider: () -> String = { randomDeviceId() },
    private val deviceName: String = "Skerry device",
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Disabled)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var client: SyncClient? = null
    private var session: SyncSession? = null

    // Собственный scope: сетевые операции НЕ должны зависеть от жизненного цикла composable.
    // На мобильном форма перерисовывается по [status]: как только connect() ставит Busy, форма
    // покидает композицию — и если бы запуск шёл от её rememberCoroutineScope, операция отменилась бы
    // на полпути («rememberCoroutineScope left the composition»). Запуск здесь это исключает; Argon2id
    // (тяжёлый) тоже уходит с main-потока на Dispatchers.Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Восстанавливаем привязку после перезапуска: сессии/токенов в памяти нет, но сохранённый
        // сервер/аккаунт показываем как Configured — UI предложит «переподключиться» одним паролем,
        // без перенабора. Disconnect стирает конфиг → снова Disabled.
        configStore.load()?.let { _status.value = SyncStatus.Configured(it.serverUrl, it.accountId) }
    }

    val isConfigured: Boolean get() = configStore.load() != null

    /** Сохранённая привязка (для предзаполнения формы переподключения сервером/аккаунтом). */
    val savedConfig: SyncConfig? get() = configStore.load()

    /**
     * Подключить устройство к аккаунту мастер-паролем — одно действие вместо раздельных
     * «регистрация»/«вход» (никаких тупиков «аккаунт уже существует» против «нет аккаунта»):
     * сначала пробуем зарегистрировать новый аккаунт, при коллизии (`CONFLICT`) — входим в
     * существующий. Запуск fire-and-forget, прогресс/итог через [status]. [keepConnected] — хранить
     * refresh-токен (запечатанным под dataKey) для бесшумного восстановления после перезапуска.
     *
     * Регистрация делает локальный dataKey ключом аккаунта; вход в существующий аккаунт, наоборот,
     * **принимает** ключ аккаунта (см. [doConnect]).
     */
    fun connect(serverUrl: String, accountId: String, masterPassword: CharArray, keepConnected: Boolean = false) {
        // Копируем синхронно и затираем оригинал ДО launch: корутина стартует на Dispatchers.Default
        // не сразу, а вызывающий может затереть массив раньше — иначе deriveMasterKey получил бы
        // пустой пароль (TOCTOU). Копией владеет [doConnect] и затирает её в finally.
        val owned = masterPassword.copyOf()
        masterPassword.fill(' ')
        scope.launch { doConnect(serverUrl, accountId, owned, keepConnected) }
    }

    private suspend fun doConnect(serverUrl: String, accountId: String, masterPassword: CharArray, keepConnected: Boolean) {
        _status.value = SyncStatus.Busy
        val dataKey = vault.exportDataKey()
        if (dataKey == null) {
            _status.value = SyncStatus.Failed("vault is locked")
            masterPassword.fill(' ')
            return
        }
        try {
            // Argon2id внутри try: тяжёлый и может бросить (вплоть до OutOfMemoryError) — иначе пароль
            // не затёрся бы (finally) и статус навсегда застрял бы на Busy.
            val masterKey = crypto.deriveMasterKey(masterPassword, crypto.deriveSyncSalt(accountId))
            val authKey = crypto.deriveAuthKey(masterKey)
            val deviceId = configStore.load()?.takeIf { it.accountId == accountId }?.deviceId ?: deviceIdProvider()
            val device = DeviceInfo(deviceId, deviceName, platformName)
            val syncClient = clientFactory(serverUrl)

            // Register-or-login: новый аккаунт публикует наш dataKey; существующий — входим и
            // принимаем его dataKey, иначе чужие записи не расшифруются. CONFLICT = аккаунт уже есть.
            val newSession = try {
                syncClient.register(accountId, authKey, crypto.wrapDataKey(masterKey, dataKey), device)
            } catch (e: SyncException) {
                if (e.kind != SyncException.Kind.CONFLICT) throw e
                val s = syncClient.login(accountId, authKey, device)
                adoptAccountDataKey(syncClient, s, masterKey, masterPassword.copyOf())
                s
            }

            client = syncClient
            session = newSession
            // keep-connected: запечатываем refresh-токен под АКТУАЛЬНЫМ dataKey vault (после возможного
            // принятия ключа аккаунта он мог смениться) — иначе restoreSession не сможет его открыть.
            val sealed = if (keepConnected) vault.exportDataKey()?.let { sealToken(it, newSession.refreshToken) } else null
            configStore.save(SyncConfig(serverUrl, accountId, deviceId, keepConnected, sealed))
            runSync()
        } catch (e: CancellationException) {
            throw e // не глушим отмену — иначе порвём structured concurrency
        } catch (e: SyncException) {
            _status.value = SyncStatus.Failed(syncErrorMessage(e))
        } catch (e: Exception) {
            // Непредвиденное (напр. vault.unlockWithDataKey при принятии ключа кинул I/O) — иначе
            // исключение ушло бы в SupervisorJob тихо, а статус навсегда застрял бы на Busy.
            _status.value = SyncStatus.Failed("Не удалось подключиться: ${e.message}")
        } finally {
            // Затирание байтов masterKey/dataKey — ответственность кода shared (их `bytes` internal);
            // здесь лишь не удерживаем ссылки дольше необходимого и чистим сам пароль.
            masterPassword.fill(' ')
        }
    }

    /**
     * Принять dataKey аккаунта на входящем устройстве: скачать обёртку, развернуть её мастер-ключом и
     * **персистентно** принять ключ в локальный vault ([Vault.adoptDataKey] — переобёртка под
     * [password] + перезапись файла), чтобы записи с других устройств расшифровывались и после
     * перезаписка, без повторного входа. Если обёртка не разворачивается (другой пароль) — оставляем
     * локальный ключ как есть. adoptDataKey затирает [password] и забирает [accountDataKey].
     */
    private suspend fun adoptAccountDataKey(syncClient: SyncClient, s: SyncSession, masterKey: MasterKey, password: CharArray) {
        val wrapped = syncClient.fetchWrappedDataKey(s)
        val accountDataKey = crypto.unwrapDataKey(masterKey, wrapped)
        if (accountDataKey == null) {
            password.fill(' ')
            return
        }
        vault.adoptDataKey(accountDataKey, password)
    }

    /** Прогнать один цикл синхронизации (pull/merge/push). No-op, если не подключены. */
    fun syncNow() {
        if (client == null || session == null) return
        scope.launch {
            _status.value = SyncStatus.Busy
            runSync()
        }
    }

    private suspend fun runSync() {
        val c = client ?: return
        val s = session ?: return
        try {
            val outcome = SyncEngine(c, vault, syncState).sync(s)
            _status.value = SyncStatus.Online(s.accountId, outcome.pushed, outcome.pulled)
        } catch (e: SyncException) {
            _status.value = SyncStatus.Failed(syncErrorMessage(e))
        }
    }

    suspend fun listDevices(): List<RemoteDevice> {
        val c = client ?: return emptyList()
        val s = session ?: return emptyList()
        // НЕ runCatching: он поглотил бы CancellationException и порвал structured concurrency.
        return try {
            c.listDevices(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Отключить sync на этом устройстве: забыть сессию и сохранённую привязку. */
    fun disconnect() {
        scope.launch {
            client?.close()
            client = null
            session = null
            configStore.clear()
            _status.value = SyncStatus.Disabled
        }
    }

    /**
     * Бесшумно восстановить сессию после перезапуска, если включён «keep connected»: расшифровать
     * сохранённый refresh-токен под dataKey и обновить сессию через сервер. Вызывать ПОСЛЕ
     * разблокировки vault (нужен dataKey) — обычно из `onVaultUnlocked`. Vault заблокирован/нет
     * токена → no-op (остаётся [SyncStatus.Configured]); токен протух/нет связи → откат в Configured
     * (привязку НЕ стираем, пользователь переподключится паролем). Уже подключены → no-op.
     */
    fun restoreSession() {
        val cfg = configStore.load() ?: return
        if (!cfg.keepConnected || cfg.sealedRefreshToken == null || session != null) return
        scope.launch {
            val dataKey = vault.exportDataKey() ?: return@launch
            _status.value = SyncStatus.Busy
            try {
                val refreshToken = openToken(dataKey, cfg.sealedRefreshToken)
                if (refreshToken == null) {
                    _status.value = SyncStatus.Configured(cfg.serverUrl, cfg.accountId)
                    return@launch
                }
                val syncClient = clientFactory(cfg.serverUrl)
                val newSession = syncClient.refresh(SyncSession(cfg.accountId, "", refreshToken))
                client = syncClient
                session = newSession
                // refresh ротирует токен — пересохраняем запечатанным под dataKey.
                configStore.save(cfg.copy(sealedRefreshToken = sealToken(dataKey, newSession.refreshToken)))
                runSync()
            } catch (e: SyncException) {
                _status.value = SyncStatus.Configured(cfg.serverUrl, cfg.accountId)
            }
        }
    }

    private val tokenAad = "skerry-sync-refresh-token".encodeToByteArray()
    private fun sealToken(dataKey: DataKey, token: String): String =
        crypto.seal(dataKey, token.encodeToByteArray(), tokenAad).toHex()
    private fun openToken(dataKey: DataKey, hex: String): String? =
        hex.hexToBytesOrNull()?.let { crypto.open(dataKey, it, tokenAad) }?.decodeToString()

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching { ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    private fun syncErrorMessage(e: SyncException): String = when (e.kind) {
        SyncException.Kind.UNAUTHORIZED -> "Неверный мастер-пароль или аккаунт"
        SyncException.Kind.NOT_FOUND -> "Аккаунт не найден"
        SyncException.Kind.CONFLICT -> "Аккаунт уже существует"
        SyncException.Kind.GONE -> "Код паринга истёк"
        SyncException.Kind.NETWORK -> "Нет связи с сервером: ${e.message}"
        SyncException.Kind.PROTOCOL -> "Ошибка протокола синхронизации: ${e.message}"
    }
}

/** Случайный 128-битный deviceId как hex — без платформенных API (общий commonMain). */
private fun randomDeviceId(): String =
    (0 until 16).joinToString("") { "0123456789abcdef"[Random.nextInt(16)].toString() }
