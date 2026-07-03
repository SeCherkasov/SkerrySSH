package app.skerry.ui.sync

import app.skerry.shared.platformName
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSettingsStore
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MasterKey
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

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

/**
 * То, что вошедшее устройство показывает для быстрого паринга: [payload] — строка [PairingPayload]
 * под QR/код, [expiresAt] — момент протухания pairing-сессии (epoch ms) для обратного отсчёта в UI.
 */
class PairingOffer(val payload: String, val expiresAt: Long)

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
 * Доступность sync-сервера по периодическому health-пробнику ([SyncClient.ping] → `GET /healthz`),
 * НЕЗАВИСИМО от состояния vault и наличия сессии. Питает индикатор «сервер работает и доступен» на
 * главных экранах desktop/mobile. [UNKNOWN] — sync не настроен (пинговать нечего) либо первой проверки
 * ещё не было; индикатор в этом состоянии прячется, чтобы не маячить у тех, кто sync не использует.
 */
enum class ServerReachable { UNKNOWN, REACHABLE, UNREACHABLE }

/**
 * Один цикл синхронизации (pull/merge/push) — абстракция над [SyncEngine.sync] для инъекции в
 * тестах: сам [SyncEngine] финальный и требует живой сети, поэтому фабрика координатора отдаёт
 * эту функцию, а не движок (см. `engineFactory` в [SyncCoordinator]).
 */
fun interface SyncRunner {
    suspend fun sync(session: SyncSession): SyncOutcome
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
    private val deviceIdProvider: () -> String = { randomDeviceId(crypto) },
    private val deviceName: String = "Skerry device",
    /**
     * Вызывается, когда при входе принят ОТЛИЧНЫЙ от локального ключ аккаунта ([Vault.adoptDataKey]
     * вернул true) — то есть dataKey vault сменился. Биометрический артефакт (`vault.bio`) обёрнут
     * под старым ключом и теперь даёт неверный ключ при разблокировке отпечатком, поэтому платформа
     * сбрасывает биометрию (пользователь включит её заново уже с новым ключом). Тихий re-wrap
     * невозможен — он требует системного промпта отпечатка. На устройстве без биометрии — no-op.
     *
     * Возвращает `true`, если биометрия БЫЛА включена и её пришлось сбросить — тогда координатор
     * поднимает [biometricResetNeeded], и UI просит перерегистрировать отпечаток (вне онбординга
     * биометрия включается раньше подключения, и сброс иначе прошёл бы молча). В онбординге биометрии
     * ещё нет — колбэк вернёт `false`, флаг не встанет.
     */
    private val onDataKeyAdopted: () -> Boolean = { false },
    /**
     * Вызывается после успешного синка, когда что-то подтянулось с сервера ([SyncOutcome.pulled] > 0).
     * Менеджеры списков (хосты/сниппеты/туннели/known-hosts) держат записи в памяти и не видят то, что
     * синк положил в vault напрямую — без этого колбэка синканутые данные не появляются на экране до
     * перезахода. Платформа проводит сюда reload менеджеров (на главном потоке).
     */
    private val onSynced: () -> Unit = {},
    /**
     * Фабрика одного цикла синка над активным клиентом — точка инъекции для тестов [runSync]
     * (см. [SyncRunner]). `null` (прод) — реальный [SyncEngine] над vault/курсором/настройками.
     */
    engineFactory: ((SyncClient) -> SyncRunner)? = null,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Disabled)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Поднят, когда подключение приняло ключ аккаунта и из-за этого сбросило ВКЛЮЧЁННУЮ биометрию
     * ([onDataKeyAdopted] вернул true). UI показывает приглашение перерегистрировать отпечаток и
     * гасит флаг через [acknowledgeBiometricReset]. Вне онбординга это единственный сигнал — иначе
     * пользователь молча остался бы без быстрой разблокировки.
     */
    private val _biometricResetNeeded = MutableStateFlow(false)
    val biometricResetNeeded: StateFlow<Boolean> = _biometricResetNeeded.asStateFlow()

    // «Что синхронизировать» (уровень аккаунта) — хранится записью SETTINGS в самом vault, едет тем же
    // синком (см. [SyncSettings]). Читаем лениво из vault: на залоченном vault стор отдаёт дефолт.
    private val settingsStore = SyncSettingsStore(vault)

    private val engineFactory: (SyncClient) -> SyncRunner = engineFactory
        ?: { c -> SyncRunner { s -> SyncEngine(c, vault, syncState, settings = { settingsStore.load() }).sync(s) } }

    /** Запечатывание refresh-токена под dataKey vault для «keep connected» (см. [SealedTokenCodec]). */
    private val tokens = SealedTokenCodec(crypto)

    /**
     * Текущее «что синхронизировать» для UI (секция WHAT SYNCS). Обновляется из vault через
     * [refreshSyncSettings] (вызывать после unlock и при показе экрана) и автоматически после каждого
     * успешного синка, подтянувшего записи (другое устройство могло поменять настройку). [setSyncSettings]
     * пишет её в vault — изменение уезжает на сервер тем же live-push, что и прочие правки.
     */
    private val _syncSettings = MutableStateFlow(SyncSettings())
    val syncSettings: StateFlow<SyncSettings> = _syncSettings.asStateFlow()

    // @Volatile: пишутся/читаются из независимых корутин на [scope] (Dispatchers.Default — пул потоков):
    // activateSession ставит, disconnect зануляет, startWatch/startLocalPush/runSync читают. Без
    // volatile запись на одном потоке не обязана быть видна чтению на другом (JMM) — например disconnect
    // ставит client=null, а startWatch видит устаревший ненулевой и стартует watch с мёртвым клиентом.
    @Volatile
    private var client: SyncClient? = null
    @Volatile
    private var session: SyncSession? = null

    // Собственный scope: сетевые операции НЕ должны зависеть от жизненного цикла composable.
    // На мобильном форма перерисовывается по [status]: как только connect() ставит Busy, форма
    // покидает композицию — и если бы запуск шёл от её rememberCoroutineScope, операция отменилась бы
    // на полпути («rememberCoroutineScope left the composition»). Запуск здесь это исключает; Argon2id
    // (тяжёлый) тоже уходит с main-потока на Dispatchers.Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Доступность сервера по health-пингу — выделенный поллер со своим клиентом (см. [ServerHealthMonitor]);
    // живёт всё время жизни координатора, пока цель null — держит UNKNOWN.
    private val health = ServerHealthMonitor(clientFactory, scope, initialTarget = configStore.load()?.serverUrl)

    /**
     * Доступность сервера по health-пингу (см. [ServerReachable]). Обновляется поллером [health]
     * независимо от сессии — индикатор честен и при заблокированном vault.
     */
    val serverReachable: StateFlow<ServerReachable> get() = health.reachable

    // Подписка на серверные уведомления об изменениях (WS `/sync`): пока живёт — каждое чужое
    // изменение прилетает push-сигналом и тянет дельту, без ручного «Sync». Один на сессию; новое
    // подключение и disconnect его отменяют. null = live-pull не активен (синк только вручную).
    // Замена cancel/join/новый job — только под [opMutex] (activateSession/disconnect).
    @Volatile
    private var watchJob: Job? = null

    // Подписка на ЛОКАЛЬНЫЕ изменения vault ([Vault.localChanges]): правка/добавление/удаление записи
    // на этом устройстве с дебаунсом запускает синк (push), чтобы изменение само улетело на сервер,
    // а оттуда WS-сигналом — на другие устройства (live-sync «как у популярных SSH-клиентов»). Один на сессию; отменяется
    // в disconnect и заменяется при реконнекте — тоже строго под [opMutex].
    @Volatile
    private var pushJob: Job? = null

    // Сериализует ВСЕ циклы синка: их запускают activateSession, ручной syncNow, WS-live-pull
    // (watchJob) и авто-push локальных правок (pushJob) — на Dispatchers.Default они иначе шли бы
    // параллельно и наперегонки писали бы курсор ([syncState]) и [_status] (kotlin-ревью MEDIUM-2:
    // два движка читают cursor=N, оба пишут cursor=M, статус отражает «последний добежавший»). LWW и
    // замок vault уберегли бы данные, но курсор/статус рассогласовались бы. Один синк за раз.
    private val syncMutex = Mutex()

    // Сериализует операции над жизненным циклом сессии: doConnect/doClaimPairing/restoreSession
    // (присвоение client/session + перезапуск watch/push) и disconnect (их остановка + close клиента).
    // Без него disconnect, проскочивший МЕЖДУ сетевым register и публикацией client, оставил бы живой
    // Ktor-клиент и запущенный watch после «отключения». Инвариант порядка замков: opMutex берётся
    // ПЕРВЫМ, syncMutex — только внутри него (runSync/disconnect) — иначе deadlock.
    private val opMutex = Mutex()

    // Сериализует startPairing: двойной тап «Link a device» не должен плодить несколько живых pairing-
    // сессий на сервере — каждая независимо валидна до TTL и расширяет окно атаки. tryLock: второй
    // параллельный вызов сразу возвращает null (UI просто не покажет второй код).
    private val pairMutex = Mutex()

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
     * Остановить фоновые работы координатора (health-поллер, watch/push, in-flight операции) —
     * teardown процесса/тестов. Сохранённую привязку НЕ трогает (это делает [disconnect]).
     */
    fun close() {
        scope.cancel()
    }

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
        // Guard от двойного запуска: повторный клик, пока предыдущий connect/claim в полёте, породил
        // бы второй Ktor-клиент (утечка пула/сокетов) и гонку статусов. Вызовы идут из UI-обработчиков
        // на главном потоке, так что check-then-set без CAS достаточен (как busy-флаги панелей).
        if (_status.value == SyncStatus.Busy) {
            masterPassword.fill(' ')
            return
        }
        // Busy ставим СИНХРОННО, ещё до launch: онбординг-форма гасит «Skip» по статусу Busy. Поставь
        // мы Busy лишь первой строкой doConnect — остался бы dispatch-цикл, где статус ещё Disabled и
        // Skip активен: проскочив на enroll биометрии, пользователь обернул бы её под ключом, который
        // коннект затем заменит (гонка принятия ключа аккаунта; security-ревью, MEDIUM).
        _status.value = SyncStatus.Busy
        // Копируем синхронно и затираем оригинал ДО launch: корутина стартует на Dispatchers.Default
        // не сразу, а вызывающий может затереть массив раньше — иначе deriveMasterKey получил бы
        // пустой пароль (TOCTOU). Копией владеет [doConnect] и затирает её в finally.
        val owned = masterPassword.copyOf()
        masterPassword.fill(' ')
        scope.launch { opMutex.withLock { doConnect(serverUrl, accountId, owned, keepConnected) } }
    }

    // Под [opMutex] (см. connect): активация сессии не должна гоняться с disconnect.
    private suspend fun doConnect(serverUrl: String, accountId: String, masterPassword: CharArray, keepConnected: Boolean) {
        _status.value = SyncStatus.Busy
        val dataKey = vault.exportDataKey()
        if (dataKey == null) {
            _status.value = SyncStatus.Failed("vault is locked")
            masterPassword.fill(' ')
            return
        }
        // Ключевой материал держим во внешних переменных, чтобы затереть его в finally (zero-knowledge:
        // masterKey — выход Argon2id, authKey — SRP-материал; держать их в heap до GC незачем).
        var masterKey: MasterKey? = null
        var authKey: ByteArray? = null
        try {
            // Argon2id внутри try: тяжёлый и может бросить (вплоть до OutOfMemoryError) — иначе пароль
            // не затёрся бы (finally) и статус навсегда застрял бы на Busy.
            val mk = crypto.deriveMasterKey(masterPassword, crypto.deriveSyncSalt(accountId)).also { masterKey = it }
            val ak = crypto.deriveAuthKey(mk).also { authKey = it }
            val deviceId = configStore.load()?.takeIf { it.accountId == accountId }?.deviceId ?: deviceIdProvider()
            val device = DeviceInfo(deviceId, deviceName, platformName)
            val syncClient = clientFactory(serverUrl)

            // Register-or-login: новый аккаунт публикует наш dataKey; существующий — входим и
            // принимаем его dataKey, иначе чужие записи не расшифруются. CONFLICT = аккаунт уже есть.
            var adoptedKey = false
            val newSession = try {
                syncClient.register(accountId, ak, crypto.wrapDataKey(mk, dataKey), device)
            } catch (e: SyncException) {
                if (e.kind != SyncException.Kind.CONFLICT) throw e
                val s = syncClient.login(accountId, ak, device)
                adoptedKey = adoptAccountDataKey(syncClient, s, mk, masterPassword.copyOf())
                s
            }

            // keep-connected: запечатываем refresh-токен под АКТУАЛЬНЫМ dataKey vault (после возможного
            // принятия ключа аккаунта он мог смениться) — иначе restoreSession не сможет его открыть.
            val sealed = if (keepConnected) {
                vault.exportDataKey()?.let { dk -> try { tokens.seal(dk, newSession.refreshToken) } finally { dk.zeroize() } }
            } else null
            // Полный re-pull (сброс курсора в 0) делаем ТОЛЬКО когда сменился dataKey, т.е. вход
            // принял ОТЛИЧНЫЙ ключ аккаунта (adoptedKey). Это ровно случай, ради которого фикс жил:
            // после reset/recreate vault локальный vault пуст и/или под новым ключом, а сохранённый
            // курсор ([SyncStateStore]) остался от прошлой сессии — без сброса `pull since tip`
            // проскочил бы серверные записи (баг «после Connected хосты пусты», pulled==0 ⇒ нет
            // onSynced). Recreate всегда даёт новый случайный dataKey, поэтому adoptedKey его ловит.
            // Обычный реконнект своим же ключом (adoptedKey=false) идёт инкрементально: иначе КАЖДЫЙ
            // connect форсил бы полный re-pull всей истории — лишняя нагрузка и усилитель ретрансляции
            // старых тромбстоунов на все устройства. Курсор теперь персистентный
            // ([FileSyncStateStore]), так что и перезапуск процесса продолжает инкрементально; путь
            // reset покрыт двойной защитой — сбросом курсора в [disconnect] (onVaultReset) и adoptedKey.
            activateSession(
                syncClient,
                newSession,
                SyncConfig(serverUrl, accountId, deviceId, keepConnected, sealed),
                resetCursor = adoptedKey,
            )
        } catch (e: CancellationException) {
            throw e // не глушим отмену — иначе порвём structured concurrency
        } catch (e: SyncException) {
            _status.value = SyncStatus.Failed(syncErrorMessage(e))
        } catch (e: Exception) {
            // Непредвиденное (напр. vault.unlockWithDataKey при принятии ключа кинул I/O) — иначе
            // исключение ушло бы в SupervisorJob тихо, а статус навсегда застрял бы на Busy.
            _status.value = SyncStatus.Failed("Не удалось подключиться: ${e.message}")
        } finally {
            // Затираем весь выведенный ключевой материал и пароль (zero-knowledge): masterKey/authKey —
            // субключи, dataKey — копия из exportDataKey (живой ключ остаётся у vault). Идемпотентно.
            masterPassword.fill(' ')
            masterKey?.zeroize()
            authKey?.fill(0)
            dataKey.zeroize()
        }
    }

    /**
     * Общий хвост включения активной сессии (из [doConnect]/[doClaimPairing]/[restoreSession]):
     * опубликовать client/session, при необходимости сбросить курсор ([resetCursor] — случаи полного
     * re-pull: принятый ключ аккаунта, свежеспаренное устройство), сохранить привязку, навести
     * health-пинг на её сервер и запустить начальный синк + live-подписки (watch/push).
     *
     * Вызывать ТОЛЬКО под [opMutex]: присвоения client/session и cancel/join/replace подписок
     * гоняются с [disconnect] — мьютекс сериализует активацию и teardown целиком. Для
     * [restoreSession] наведение health-цели — no-op (URL не менялся; StateFlow дедуплицирует
     * равные значения, пинг-цикл не перезапускается).
     */
    private suspend fun activateSession(
        syncClient: SyncClient,
        newSession: SyncSession,
        config: SyncConfig,
        resetCursor: Boolean,
    ) {
        client = syncClient
        session = newSession
        if (resetCursor) syncState.setCursor(config.accountId, 0)
        configStore.save(config)
        health.setTarget(config.serverUrl)
        runSync()
        startWatch()
        startLocalPush()
    }

    /**
     * Принять dataKey аккаунта на входящем устройстве: скачать обёртку, развернуть её мастер-ключом и
     * **персистентно** принять ключ в локальный vault ([Vault.adoptDataKey] — переобёртка под
     * [password] + перезапись файла), чтобы записи с других устройств расшифровывались и после
     * перезаписка, без повторного входа. Если обёртка не разворачивается (другой пароль) — оставляем
     * локальный ключ как есть. adoptDataKey затирает [password] и забирает [accountDataKey].
     * Возвращает `true`, если ключ был принят (сменился) — вызывающий по этому форсит полный re-pull.
     */
    private suspend fun adoptAccountDataKey(syncClient: SyncClient, s: SyncSession, masterKey: MasterKey, password: CharArray): Boolean {
        val wrapped = syncClient.fetchWrappedDataKey(s)
        val accountDataKey = crypto.unwrapDataKey(masterKey, wrapped)
        if (accountDataKey == null) {
            password.fill(' ')
            return false
        }
        // Ключ сменился → биометрия обёрнута под старым ключом и стала бы давать неверный dataKey
        // при разблокировке отпечатком: просим платформу сбросить её (runCatching — сбой биометрии
        // не должен валить подключение). Если биометрия БЫЛА включена — поднимаем флаг, чтобы UI
        // предложил перерегистрировать отпечаток уже под новым ключом.
        val adopted = vault.adoptDataKey(accountDataKey, password)
        if (adopted) {
            if (runCatching { onDataKeyAdopted() }.getOrDefault(false)) _biometricResetNeeded.value = true
        }
        return adopted
    }

    /** Сбросить приглашение перерегистрировать отпечаток (пользователь перерегистрировал или отклонил). */
    fun acknowledgeBiometricReset() {
        _biometricResetNeeded.value = false
    }

    /**
     * Начать быстрый паринг на ВОШЕДШЕМ устройстве (вариант B): сгенерировать одноразовый transferKey,
     * запечатать им живой dataKey и отдать конверт серверу ([SyncClient.startPairing]); вернуть
     * [PairingOffer] — строку для QR/кода ([PairingPayload]) и срок жизни. transferKey уезжает только в
     * QR, на сервер идёт лишь конверт, поэтому серверный шифротекст без QR бесполезен. Требует активной
     * сессии и разблокированного vault; `null` при сбое (статус → [SyncStatus.Failed]). suspend —
     * вызывается из корутины UI (короткий POST); transferKey/копию dataKey затираем в finally.
     */
    suspend fun startPairing(): PairingOffer? {
        val c = client ?: return null
        val s = session ?: return null
        val cfg = configStore.load() ?: return null
        // tryLock сериализует паринг: повторный вызов, пока предыдущий не завершился, сразу даёт null.
        if (!pairMutex.tryLock()) return null
        // НЕ трогаем глобальный _status: паринг запускается, когда устройство уже Online, и разовый сбой
        // POST'а не должен ронять статус в Failed (это схлопнуло бы всю Online-секцию вместе с самой
        // карточкой паринга). Об ошибке сигналим только возвратом null — UI покажет её локально.
        val dataKey = vault.exportDataKey()
        if (dataKey == null) {
            pairMutex.unlock()
            return null
        }
        val transferKey = crypto.newTransferKey()
        return try {
            val envelope = crypto.sealDataKeyForTransfer(dataKey, transferKey)
            val ticket = c.startPairing(s, envelope)
            // encode() копирует transferKey в base64-строку до finally, где сырой массив затирается.
            PairingOffer(PairingPayload(cfg.serverUrl, ticket.code, transferKey).encode(), ticket.expiresAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            dataKey.zeroize()
            transferKey.fill(0)
            pairMutex.unlock()
        }
    }

    /**
     * Завершить быстрый паринг на НОВОМ устройстве (вариант B): принять строку из QR/ручного ввода,
     * claim'нуть сессию по коду ([SyncClient.claimPairing]), развернуть dataKey аккаунта transferKey'ем
     * (в обход сервера) и записать локальный vault под [localPassword] — этим паролем устройство будет
     * разблокироваться дальше, мастер-пароль аккаунта вводить не нужно. Если vault ещё не создан
     * (онбординг-join) — создаём его этим паролем; если создан, но заблокирован — разблокируем им.
     * Запуск fire-and-forget, прогресс/итог — через [status]; хвост активации общий с [doConnect]
     * ([activateSession]).
     */
    fun claimPairing(payload: String, localPassword: CharArray, keepConnected: Boolean = false) {
        // Guard от двойного запуска — как в [connect] (двойной сабмит утекал бы Ktor-клиентом).
        if (_status.value == SyncStatus.Busy) {
            localPassword.fill(' ')
            return
        }
        // Busy синхронно (как connect): онбординг-форма гасит «Skip»/двойной сабмит по статусу Busy.
        _status.value = SyncStatus.Busy
        // Копируем и затираем оригинал ДО launch (TOCTOU): корутина стартует не сразу, вызывающий мог бы
        // затереть массив раньше, чем vault.create/unlock его прочтёт. Копией владеет doClaimPairing.
        val owned = localPassword.copyOf()
        localPassword.fill(' ')
        scope.launch { opMutex.withLock { doClaimPairing(payload, owned, keepConnected) } }
    }

    // Под [opMutex] (см. claimPairing): активация сессии не должна гоняться с disconnect.
    private suspend fun doClaimPairing(payload: String, localPassword: CharArray, keepConnected: Boolean) {
        _status.value = SyncStatus.Busy
        val parsed = PairingPayload.decode(payload)
        if (parsed == null) {
            _status.value = SyncStatus.Failed("Не похоже на код связывания")
            localPassword.fill(' ')
            return
        }
        // Развёрнутый ключ аккаунта держим во внешней переменной, чтобы затереть в finally, ПОКА им не
        // завладел adoptDataKey (после успешного adopt обнуляем ссылку — иначе затёрли бы живой ключ).
        var accountDataKey: DataKey? = null
        // Клиент, который мы открыли, но ещё не сделали активным [client]: при ошибке до присвоения его
        // нужно закрыть (Ktor-пул/сокеты/диспетчер), иначе утечёт на весь процесс (kotlin-ревью).
        var openedClient: SyncClient? = null
        try {
            val syncClient = clientFactory(parsed.serverUrl).also { openedClient = it }
            val deviceId = deviceIdProvider() // новое устройство для аккаунта — всегда свежий id
            val device = DeviceInfo(deviceId, deviceName, platformName)
            val result = syncClient.claimPairing(parsed.code, device)

            val decoded = crypto.openTransferredDataKey(parsed.transferKey, result.encryptedDataKey)
            if (decoded == null) {
                // transferKey не подошёл к конверту — битый/подменённый код. claimPairing уже сжёг
                // одноразовый код на сервере, повтор не поможет: пусть пользователь начнёт паринг заново.
                _status.value = SyncStatus.Failed("Код связывания недействителен")
                return
            }
            accountDataKey = decoded

            // Привести локальный vault к разблокированному состоянию под [localPassword], затем принять
            // ключ аккаунта (переобёртка под этот пароль + перезапись файла). Существующие локальные
            // записи под старым ключом станут нечитаемы — синканутые придут заново полным re-pull ниже.
            if (!vault.exists()) {
                vault.create(localPassword.copyOf())
            } else if (!vault.isUnlocked) {
                when (vault.unlock(localPassword.copyOf())) {
                    UnlockResult.Success -> {}
                    UnlockResult.WrongPassword -> {
                        _status.value = SyncStatus.Failed("Неверный пароль этого устройства")
                        return
                    }
                    UnlockResult.Corrupted -> {
                        _status.value = SyncStatus.Failed("Локальное хранилище повреждено")
                        return
                    }
                }
            }
            val adopted = vault.adoptDataKey(decoded, localPassword.copyOf())
            // adoptDataKey забирает владение ключом ТОЛЬКО при принятии (true). Если он отверг ключ
            // (false — совпал с текущим; для нового устройства практически невозможно) — владение
            // осталось у нас, держим ссылку, чтобы finally затёр (security-ревью). Иначе обнуляем.
            if (adopted) accountDataKey = null
            if (adopted && runCatching { onDataKeyAdopted() }.getOrDefault(false)) {
                _biometricResetNeeded.value = true
            }

            val sealed = if (keepConnected) {
                vault.exportDataKey()?.let { dk -> try { tokens.seal(dk, result.session.refreshToken) } finally { dk.zeroize() } }
            } else null
            // Владение клиентом передаётся полю [client] первым же присвоением активации.
            openedClient = null
            // Новое устройство: локально записей нет — полный re-pull всей истории аккаунта
            // (resetCursor, как adoptedKey в doConnect).
            activateSession(
                syncClient,
                result.session,
                SyncConfig(parsed.serverUrl, result.accountId, deviceId, keepConnected, sealed),
                resetCursor = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            _status.value = SyncStatus.Failed(syncErrorMessage(e))
        } catch (e: Exception) {
            // Без e.message: оно может нести внутренние детали крипто/Ktor наружу в UI (security-ревью).
            _status.value = SyncStatus.Failed("Не удалось связать устройство. Проверьте код и попробуйте снова.")
        } finally {
            localPassword.fill(' ')
            accountDataKey?.zeroize() // если до adopt не дошли (или ключ отвергнут) — затираем развёрнутый ключ
            parsed.transferKey.fill(0)
            runCatching { openedClient?.close() } // открыли, но не сделали активным — закрываем
        }
    }

    /**
     * Перечитать «что синхронизировать» из vault в [syncSettings]. На залоченном vault стор отдаёт
     * дефолт. Зовётся автоматически после синка с pull'ом и вручную при показе экрана настроек (после
     * unlock значение в vault уже доступно, а flow мог стартовать с дефолта на залоченном старте).
     * Чтение vault (диск + AEAD) уводим с UI-потока на [scope] (Dispatchers.Default) — ANR-риск.
     */
    fun refreshSyncSettings() {
        scope.launch { _syncSettings.value = settingsStore.load() }
    }

    /**
     * Сохранить «что синхронизировать» (уровень аккаунта). [syncSettings] обновляем сразу (оптимистично,
     * для отзывчивости тумблера), а запись в vault — на [scope] (диск/atomicWrite не на UI-потоке).
     * Запись настроек — обычная запись vault, поэтому [Vault.put] эмитит localChanges, и live-push сам
     * отправит её на сервер (а оттуда на другие устройства).
     *
     * Re-enable backfill (привычная логика SSH-клиентов): при ВКЛЮЧЕНИИ ранее выключенного типа сбрасываем курсор в 0
     * ДО сохранения — пока тип был OFF, курсор проскочил serverSeq чужих записей этого типа, и без
     * сброса повторное включение их бы не подтянуло (security-ревью MEDIUM: «re-enable не делает
     * backfill»). Полный re-pull идемпотентен ([Vault.mergeRemote] по LWW), лишние дубли не страшны.
     */
    fun setSyncSettings(settings: SyncSettings) {
        val previous = _syncSettings.value
        _syncSettings.value = settings
        scope.launch {
            try {
                val reEnabled = (settings.syncHosts && !previous.syncHosts) ||
                    (settings.syncSnippets && !previous.syncSnippets)
                if (reEnabled) configStore.load()?.let { syncState.setCursor(it.accountId, 0) }
                // save ПОСЛЕ сброса курсора: его localChanges разбудит pushJob→runSync, который должен
                // увидеть уже сброшенный курсор и сделать полный re-pull (debounce даёт достаточный зазор).
                settingsStore.save(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // save в vault упал (vault залочен, диск переполнен) — иначе ошибка ушла бы в SupervisorJob
                // тихо, а оптимистично переключённый тумблер «отскочил» бы в прежнее значение при следующем
                // refreshSyncSettings без объяснения (silent-ревью). Откатываем UI к previous и сигналим.
                _syncSettings.value = previous
                _status.value = SyncStatus.Failed("Не удалось сохранить настройки синхронизации: ${e.message}")
            }
        }
    }

    /** Прогнать один цикл синхронизации (pull/merge/push). No-op, если не подключены. */
    fun syncNow() {
        if (client == null || session == null) return
        scope.launch {
            syncMutex.withLock {
                // Busy — только ПОД мьютексом и ПОСЛЕ проверки клиента: раньше статус взводился до
                // захвата замка, и disconnect, проскочивший в эту щель, оставлял ранний return
                // runSync без записи статуса — вечный Busy-спиннер.
                val c = client ?: return@withLock
                val s = session ?: return@withLock
                _status.value = SyncStatus.Busy
                runSyncLocked(c, s)
            }
        }
    }

    // Под [syncMutex]: параллельные вызовы (watchJob/pushJob/syncNow/connect) сериализуются, чтобы не
    // гонять курсор и статус наперегонки. withLock — точка отмены, так что cancel из disconnect/реконнекта
    // освобождает замок штатно (CancellationException пробрасывается).
    private suspend fun runSync() = syncMutex.withLock {
        val c = client ?: return@withLock
        val s = session ?: return@withLock
        runSyncLocked(c, s)
    }

    // Тело одного цикла синка; вызывается ТОЛЬКО с уже захваченным [syncMutex] (runSync/syncNow).
    private suspend fun runSyncLocked(c: SyncClient, s: SyncSession) {
        try {
            val outcome = engineFactory(c).sync(s)
            _status.value = SyncStatus.Online(s.accountId, outcome.pushed, outcome.pulled)
            // Подтянули записи с сервера → обновить менеджеры списков, иначе синканутое не видно до перезахода.
            if (outcome.pulled > 0) {
                refreshSyncSettings() // другое устройство могло сменить «что синхронизировать»
                runCatching { onSynced() }
            }
        } catch (e: CancellationException) {
            throw e // отмену не глушим — иначе порвём structured concurrency
        } catch (e: SyncException) {
            _status.value = SyncStatus.Failed(syncErrorMessage(e))
        } catch (e: Exception) {
            // Непредвиденное (сериализация, OOM, баг в движке) — иначе ушло бы в SupervisorJob тихо,
            // а статус навсегда застрял бы на Busy (вечный спиннер). syncNow/restoreSession зовут это.
            _status.value = SyncStatus.Failed("Ошибка синхронизации: ${e.message}")
        }
    }

    /**
     * Должен ли WS-сигнал с курсором [remoteCursor] запустить дельта-pull. `true` только когда сервер
     * ушёл ВПЕРЁД нашего сохранённого курсора (= появились чужие изменения). Равный/отставший курсор —
     * это эхо нашего собственного push'а, тянуть нечего: гасим, чтобы не крутить петлю push→WS→push.
     * `internal` (а не private) — точка для модульного теста [SyncCoordinatorWatchGuardTest].
     */
    internal fun signalAdvancesCursor(accountId: String, remoteCursor: Long): Boolean =
        remoteCursor > syncState.cursor(accountId)

    /**
     * Подписаться на серверные уведомления об изменениях (WS `/sync`) и тянуть дельту на каждый сигнал —
     * realtime live-pull вместо ручного «Sync». Отменяет прошлую подписку (реконнект); cancel/join/replace
     * атомарны относительно [disconnect] — вызывается только под [opMutex]. Best-effort: обрыв/ошибка WS
     * не убивает live-pull навсегда — переподключаемся с экспоненциальным backoff
     * ([WATCH_RETRY_MIN_MS]…[WATCH_RETRY_MAX_MS]), пока корутину не отменят (disconnect/реконнект). Без
     * этого временная просадка сети молча гасила бы live-pull, а статус оставался бы Online (пассивное
     * устройство переставало бы получать чужие изменения без единого сигнала). Статус в Failed НЕ роняем —
     * иначе моргание связи гасило бы рабочий Online; ручной [syncNow] всё время доступен. [runSync] на
     * каждый сигнал выполняется последовательно (collect не параллелит) и сам не мигает Busy.
     */
    private suspend fun startWatch() {
        val c = client ?: return
        val s = session ?: return
        // cancel + join старой подписки ДО запуска новой (реконнект без disconnect): иначе прошлый
        // collect мог бы крутить runSync уже с новой сессией под старым курсором (kotlin-ревью MEDIUM-1).
        watchJob?.cancel()
        watchJob?.join()
        watchJob = scope.launch {
            var backoff = WATCH_RETRY_MIN_MS
            while (true) {
                try {
                    c.changes(s).collect { remoteCursor ->
                        backoff = WATCH_RETRY_MIN_MS // живой сигнал — сбрасываем задержку до минимума
                        // Тянем дельту ТОЛЬКО если сервер ушёл вперёд нашего курсора. Иначе наш же push,
                        // вернувшийся WS-сигналом с уже известным курсором, запускал бы лишний синк —
                        // второй разрыватель петли push→WS→push (defense-in-depth к серверному guard'у).
                        if (signalAdvancesCursor(s.accountId, remoteCursor)) runSync()
                    }
                    // collect завершился без исключения = сервер штатно закрыл поток; переподключимся ниже.
                } catch (e: CancellationException) {
                    throw e // отмену (disconnect/реконнект) не глушим
                } catch (e: Exception) {
                    // WS оборвался (сеть/сервер/протух токен) — не сдаёмся, ждём backoff и переподключаемся.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(WATCH_RETRY_MAX_MS)
            }
        }
    }

    /**
     * Подписаться на локальные изменения vault и автоматически пушить их (live-sync «как у популярных SSH-клиентов»):
     * правка/добавление/удаление записи запускает синк, без ручного «Sync». Дебаунс [PUSH_DEBOUNCE_MS]
     * коалесцирует пачку быстрых правок (массовый импорт, переименование с автосохранением) в один синк.
     * Отменяет прошлую подписку (реконнект); как и [startWatch], вызывается только под [opMutex].
     * [runSync] делает pull+push: pull→merge НЕ эмитит localChanges, поэтому входящие записи не
     * порождают новый push — цикла нет.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun startLocalPush() {
        if (client == null || session == null) return
        pushJob?.cancel()
        pushJob?.join() // как в startWatch: дождаться остановки старой подписки до запуска новой
        pushJob = scope.launch {
            vault.localChanges
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { if (client != null && session != null) runSync() }
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

    /**
     * Отозвать чужое устройство по [deviceId] (Settings → Account). No-op без активной сессии.
     * Возвращает `true`, если сервер подтвердил отзыв — UI по этому перечитывает список устройств.
     * Отзыв ТЕКУЩЕГО устройства UI не предлагает (для этого есть [disconnect]).
     */
    suspend fun revokeDevice(deviceId: String): Boolean {
        val c = client ?: return false
        val s = session ?: return false
        return try {
            c.revokeDevice(s, deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Отзыв — security-action (реакция на потерянное устройство): тихий false неотличим в UI от
            // «устройства нет», пользователь не знает, отозвалось ли (silent-ревью). Сигналим ошибку через
            // статус, чтобы секция Sync показала сбой, и возвращаем false (список не перечитывается).
            _status.value = SyncStatus.Failed("Не удалось отозвать устройство: ${e.message}")
            false
        }
    }

    /** Отключить sync на этом устройстве: забыть сессию и сохранённую привязку. */
    fun disconnect() {
        // Под [opMutex]: teardown не должен гоняться с активацией (connect/claim/restore) — иначе
        // disconnect, проскочивший между сетевым register и публикацией client, оставил бы живой
        // Ktor-клиент и работающий watch «после отключения». withLock дождётся завершения активации,
        // затем снесёт её результат целиком (инвариант: после disconnect живого клиента нет).
        scope.launch {
            opMutex.withLock {
                // Сначала гасим обе live-подписки и ДОЖИДАЕМСЯ их остановки: cancel() лишь шлёт сигнал, а
                // их collect мог быть в середине runSync() — без join его уже-отработавший runSync записал бы
                // Online ПОСЛЕ выставленного ниже Disabled (статус залип бы на Online при client==null).
                watchJob?.cancel()
                pushJob?.cancel()
                watchJob?.join()
                pushJob?.join()
                watchJob = null
                pushJob = null
                // close() и зануление client/session — под syncMutex: ручной syncNow() запускает runSync() и
                // нигде не трекается/не отменяется, поэтому без замка disconnect мог бы закрыть Ktor-клиент
                // во время его же in-flight запроса (kotlin-ревью HIGH). withLock дождётся завершения текущего
                // runSync; следующий после зануления увидит client==null и сделает ранний возврат.
                syncMutex.withLock {
                    // runCatching: сбой close() (I/O при teardown) не должен оставить привязку/курсор на месте —
                    // иначе disconnect молча провалился бы, а статус застрял.
                    runCatching { client?.close() }
                    client = null
                    session = null
                }
                // Курсор синка тоже забываем: следующее подключение (к этому или другому аккаунту в том же
                // процессе) обязано сделать полный re-pull, а не продолжить с tip прошлой сессии. runCatching:
                // сбой записи курсора (диск переполнен) не должен оставить configStore/healthTarget/статус в
                // полу-отвязанном состоянии (silent-ревью: «Disconnect отработал, но устройство снова привязано»).
                runCatching { configStore.load()?.let { syncState.setCursor(it.accountId, 0) } }
                configStore.clear()
                health.setTarget(null) // отвязались — гасим health-пинг (поллер закроет клиент, статус → UNKNOWN)
                _status.value = SyncStatus.Disabled
            }
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
            opMutex.withLock {
                // Повторная проверка под замком: параллельный connect/claim мог успеть активировать
                // сессию, пока мы ждали opMutex — тогда восстанавливать нечего.
                if (session != null) return@withLock
                val dataKey = vault.exportDataKey() ?: return@withLock
                _status.value = SyncStatus.Busy
                try {
                    val refreshToken = tokens.open(dataKey, cfg.sealedRefreshToken)
                    if (refreshToken == null) {
                        _status.value = SyncStatus.Configured(cfg.serverUrl, cfg.accountId)
                        return@withLock
                    }
                    val syncClient = clientFactory(cfg.serverUrl)
                    val newSession = syncClient.refresh(SyncSession(cfg.accountId, "", refreshToken))
                    // refresh ротирует токен — пересохраняем запечатанным под dataKey (внутри активации).
                    activateSession(
                        syncClient,
                        newSession,
                        cfg.copy(sealedRefreshToken = tokens.seal(dataKey, newSession.refreshToken)),
                        resetCursor = false,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Любой сбой восстановления (протух токен, нет связи, иное) — откат в Configured, привязку
                    // не стираем (переподключение паролем). Ловим Exception, не только SyncException: иначе
                    // непредвиденное застряло бы на Busy (вечный спиннер).
                    _status.value = SyncStatus.Configured(cfg.serverUrl, cfg.accountId)
                } finally {
                    dataKey.zeroize() // копия dataKey — затираем, живой ключ остаётся у vault
                }
            }
        }
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

/**
 * Дебаунс авто-пуша локальных правок: пачка быстрых изменений (массовый импорт, переименование с
 * автосохранением каждой буквы) коалесцируется в один синк. Достаточно мал, чтобы правка долетала до
 * других устройств за ~секунду, и достаточно велик, чтобы не пушить на каждое нажатие.
 */
private const val PUSH_DEBOUNCE_MS = 1500L

/**
 * Backoff переподключения WS-подписки live-pull ([SyncCoordinator.startWatch]): после обрыва ждём
 * [WATCH_RETRY_MIN_MS] и удваиваем до потолка [WATCH_RETRY_MAX_MS]. Минимум мал, чтобы короткая просадка
 * сети восстанавливалась быстро; потолок ограничивает частоту попыток при долгой недоступности сервера
 * (или мёртвом токене) — ~раз в минуту, без разряда батареи. Живой сигнал сбрасывает задержку к минимуму.
 */
private const val WATCH_RETRY_MIN_MS = 1_000L
private const val WATCH_RETRY_MAX_MS = 60_000L

/**
 * Криптослучайный 128-битный deviceId как hex. Берём 16 байт из CSPRNG libsodium через
 * [VaultCrypto.newSalt] (а не `kotlin.random.Random`, не криптостойкий): deviceId не секрет, но
 * входит в alias биометрического ключа и в LWW-tie-break, поэтому предсказуемость нежелательна.
 */
private fun randomDeviceId(crypto: VaultCrypto): String = crypto.newSalt().toHex()
