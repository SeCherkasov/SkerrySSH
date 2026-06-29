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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private var client: SyncClient? = null
    private var session: SyncSession? = null

    // Подписка на серверные уведомления об изменениях (WS `/sync`): пока живёт — каждое чужое
    // изменение прилетает push-сигналом и тянет дельту, без ручного «Sync». Один на сессию; новое
    // подключение и disconnect его отменяют. null = live-pull не активен (синк только вручную).
    private var watchJob: kotlinx.coroutines.Job? = null

    // Подписка на ЛОКАЛЬНЫЕ изменения vault ([Vault.localChanges]): правка/добавление/удаление записи
    // на этом устройстве с дебаунсом запускает синк (push), чтобы изменение само улетело на сервер,
    // а оттуда WS-сигналом — на другие устройства (live-sync «как у популярных SSH-клиентов»). Один на сессию; отменяется
    // в disconnect и заменяется при реконнекте.
    private var pushJob: kotlinx.coroutines.Job? = null

    // Сериализует ВСЕ циклы синка: их запускают doConnect/restoreSession, ручной syncNow, WS-live-pull
    // (watchJob) и авто-push локальных правок (pushJob) — на Dispatchers.Default они иначе шли бы
    // параллельно и наперегонки писали бы курсор ([syncState]) и [_status] (kotlin-ревью MEDIUM-2:
    // два движка читают cursor=N, оба пишут cursor=M, статус отражает «последний добежавший»). LWW и
    // замок vault уберегли бы данные, но курсор/статус рассогласовались бы. Один синк за раз.
    private val syncMutex = Mutex()

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
        // Busy ставим СИНХРОННО, ещё до launch: онбординг-форма гасит «Skip» по статусу Busy. Поставь
        // мы Busy лишь первой строкой doConnect — остался бы dispatch-цикл, где статус ещё Disabled и
        // Skip активен: проскочив на enroll биометрии, пользователь обернул бы её под ключом, который
        // коннект затем заменит (гонка принятия ключа аккаунта; security-ревью, MEDIUM).
        _status.value = SyncStatus.Busy
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

            client = syncClient
            session = newSession
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
            if (adoptedKey) syncState.setCursor(accountId, 0)
            // keep-connected: запечатываем refresh-токен под АКТУАЛЬНЫМ dataKey vault (после возможного
            // принятия ключа аккаунта он мог смениться) — иначе restoreSession не сможет его открыть.
            val sealed = if (keepConnected) {
                vault.exportDataKey()?.let { dk -> try { sealToken(dk, newSession.refreshToken) } finally { dk.zeroize() } }
            } else null
            configStore.save(SyncConfig(serverUrl, accountId, deviceId, keepConnected, sealed))
            runSync()
            startWatch()
            startLocalPush()
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

    /** Прогнать один цикл синхронизации (pull/merge/push). No-op, если не подключены. */
    fun syncNow() {
        if (client == null || session == null) return
        scope.launch {
            _status.value = SyncStatus.Busy
            runSync()
        }
    }

    // Под [syncMutex]: параллельные вызовы (watchJob/pushJob/syncNow/connect) сериализуются, чтобы не
    // гонять курсор и статус наперегонки. withLock — точка отмены, так что cancel из disconnect/реконнекта
    // освобождает замок штатно (CancellationException пробрасывается).
    private suspend fun runSync() = syncMutex.withLock {
        val c = client ?: return@withLock
        val s = session ?: return@withLock
        try {
            val outcome = SyncEngine(c, vault, syncState).sync(s)
            _status.value = SyncStatus.Online(s.accountId, outcome.pushed, outcome.pulled)
            // Подтянули записи с сервера → обновить менеджеры списков, иначе синканутое не видно до перезахода.
            if (outcome.pulled > 0) runCatching { onSynced() }
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
     * Подписаться на серверные уведомления об изменениях (WS `/sync`) и тянуть дельту на каждый сигнал —
     * realtime live-pull вместо ручного «Sync». Отменяет прошлую подписку (реконнект). Best-effort:
     * обрыв/ошибка WS просто завершает подписку (ручной [syncNow] и переподключение остаются рабочими) —
     * статус НЕ роняем в Failed, иначе временная просадка связи гасила бы рабочий Online. [runSync] на
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
            try {
                c.changes(s).collect { runSync() }
            } catch (e: CancellationException) {
                throw e // отмену (disconnect/реконнект) не глушим
            } catch (e: Exception) {
                // WS оборвался (сеть/сервер/протух токен) — live-pull прекращаем тихо. Видимый статус
                // остаётся прежним (Online после последнего успешного синка); ручной Sync доступен.
            }
        }
    }

    /**
     * Подписаться на локальные изменения vault и автоматически пушить их (live-sync «как у популярных SSH-клиентов»):
     * правка/добавление/удаление записи запускает синк, без ручного «Sync». Дебаунс [PUSH_DEBOUNCE_MS]
     * коалесцирует пачку быстрых правок (массовый импорт, переименование с автосохранением) в один синк.
     * Отменяет прошлую подписку (реконнект). [runSync] делает pull+push: pull→merge НЕ эмитит
     * localChanges, поэтому входящие записи не порождают новый push — цикла нет.
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

    /** Отключить sync на этом устройстве: забыть сессию и сохранённую привязку. */
    fun disconnect() {
        scope.launch {
            // Сначала гасим обе live-подписки и ДОЖИДАЕМСЯ их остановки: cancel() лишь шлёт сигнал, а
            // их collect мог быть в середине runSync() — без join его уже-отработавший runSync записал бы
            // Online ПОСЛЕ выставленного ниже Disabled (статус залип бы на Online при client==null).
            watchJob?.cancel()
            pushJob?.cancel()
            watchJob?.join()
            pushJob?.join()
            watchJob = null
            pushJob = null
            // runCatching: сбой close() (I/O при teardown) не должен оставить привязку/курсор на месте —
            // иначе disconnect молча провалился бы, а статус застрял.
            runCatching { client?.close() }
            client = null
            session = null
            // Курсор синка тоже забываем: следующее подключение (к этому или другому аккаунту в том же
            // процессе) обязано сделать полный re-pull, а не продолжить с tip прошлой сессии.
            configStore.load()?.let { syncState.setCursor(it.accountId, 0) }
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
                startWatch()
                startLocalPush()
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

/**
 * Дебаунс авто-пуша локальных правок: пачка быстрых изменений (массовый импорт, переименование с
 * автосохранением каждой буквы) коалесцируется в один синк. Достаточно мал, чтобы правка долетала до
 * других устройств за ~секунду, и достаточно велик, чтобы не пушить на каждое нажатие.
 */
private const val PUSH_DEBOUNCE_MS = 1500L

/**
 * Криптослучайный 128-битный deviceId как hex. Берём 16 байт из CSPRNG libsodium через
 * [VaultCrypto.newSalt] (а не `kotlin.random.Random`, не криптостойкий): deviceId не секрет, но
 * входит в alias биометрического ключа и в LWW-tie-break, поэтому предсказуемость нежелательна.
 */
private fun randomDeviceId(crypto: VaultCrypto): String =
    crypto.newSalt().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
