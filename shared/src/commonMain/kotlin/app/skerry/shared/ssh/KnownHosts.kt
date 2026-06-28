package app.skerry.shared.ssh

import kotlinx.serialization.Serializable

/**
 * Запись об известном ключе хоста. Идентичность ключа — тройка (host, port, keyType):
 * один хост может предъявлять ключи разных типов. [fingerprint] — формат OpenSSH
 * (`SHA256:` + base64 без паддинга), как и в [HostKeyVerifier].
 *
 * [firstSeen] — отметка времени первого доверия ключу (ISO-8601, как её отдают инъектированные
 * часы [TofuHostKeyVerifier]); пусто для записей, импортированных из старого формата без даты.
 *
 * `@Serializable` — запись синкается в vault ([app.skerry.shared.ssh.VaultKnownHostsStore],
 * Phase A): доверие к ключам хостов переезжает на другие устройства, как в Termius.
 */
@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val firstSeen: String = "",
)

/** Персистентное хранилище известных ключей хостов. Платформенная реализация — файловая. */
interface KnownHostsStore {
    fun all(): List<KnownHost>

    /** Добавить новую запись. Вызывается только для ранее неизвестной тройки (host, port, keyType). */
    fun add(host: KnownHost)

    /**
     * Атомарно заменить доверенный ключ той же идентичности (host, port, keyType) на [host]
     * (новый отпечаток/время) — для принятия сменившегося ключа без окна, в котором запись
     * отсутствует и [TofuHostKeyVerifier] мог бы пере-TOFU'ить произвольный ключ.
     */
    fun replace(host: KnownHost)

    /** Забыть доверенный ключ по идентичности (host, port, keyType). Нет записи — no-op. */
    fun remove(host: String, port: Int, keyType: String)
}

/**
 * Зафиксированное событие смены ключа хоста: при подключении предъявлен [offeredFingerprint],
 * отличный от доверенного [recordedFingerprint] для тройки (host, port, keyType). Persisted, чтобы
 * менеджер known-hosts мог показать предупреждение и дать пользователю принять/отклонить новый ключ
 * уже после того, как соединение было отклонено [TofuHostKeyVerifier].
 */
data class HostKeyMismatch(
    val host: String,
    val port: Int,
    val keyType: String,
    val recordedFingerprint: String,
    val offeredFingerprint: String,
    val observedAt: String = "",
)

/** Персистентное хранилище незакрытых событий смены ключа. Платформенная реализация — файловая. */
interface HostKeyMismatchStore {
    fun all(): List<HostKeyMismatch>

    /**
     * Зафиксировать смену ключа. На тройку (host, port, keyType) хранится не более одной записи —
     * повторное событие перезаписывает предыдущее (актуален последний предъявленный ключ).
     */
    fun record(mismatch: HostKeyMismatch)

    /** Снять событие по идентичности (host, port, keyType) — после принятия/отклонения. Нет записи — no-op. */
    fun clear(host: String, port: Int, keyType: String)
}

/** Стор смены ключей, выключенный в no-op: TOFU без журналирования (тесты, минимальный граф). */
object NoopHostKeyMismatchStore : HostKeyMismatchStore {
    override fun all(): List<HostKeyMismatch> = emptyList()
    override fun record(mismatch: HostKeyMismatch) {}
    override fun clear(host: String, port: Int, keyType: String) {}
}

/**
 * Trust-on-first-use поверх [KnownHostsStore]: первый ключ для тройки (host, port, keyType)
 * принимается и запоминается; при последующих подключениях принимается только совпадающий
 * fingerprint. Несовпадение → отказ (смена ключа / возможный MITM); доверенная запись при
 * этом не перезаписывается, а само событие фиксируется в [mismatches] для последующего разбора
 * в менеджере known-hosts. Новый тип ключа для известного хоста трактуется как новый ключ.
 *
 * [now] штампует [KnownHost.firstSeen]/[HostKeyMismatch.observedAt] (ISO-8601); по умолчанию пусто —
 * для тестов и графов без часов.
 *
 * Интерактивного подтверждения отпечатка при первом подключении пока нет — придёт вместе с
 * интерактивным TOFU; принятие/отклонение уже зафиксированной смены ключа — в менеджере known-hosts.
 */
class TofuHostKeyVerifier(
    private val store: KnownHostsStore,
    private val mismatches: HostKeyMismatchStore = NoopHostKeyMismatchStore,
    private val now: () -> String = { "" },
) : HostKeyVerifier {
    override fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean {
        val existing = store.all().firstOrNull {
            it.host == host && it.port == port && it.keyType == keyType
        }
        return when (existing) {
            null -> {
                store.add(KnownHost(host, port, keyType, fingerprint, now()))
                true
            }
            else -> {
                if (existing.fingerprint == fingerprint) {
                    true
                } else {
                    mismatches.record(
                        HostKeyMismatch(host, port, keyType, existing.fingerprint, fingerprint, now()),
                    )
                    false
                }
            }
        }
    }
}

/**
 * Верификатор ключа хоста для разовой проверки связи («Test connection»): read-only по отношению к
 * [store]. Доверенный совпадающий ключ принимается; несовпадение у уже известного хоста отвергается
 * (защита от MITM на сохранённом хосте); ранее неизвестный хост принимается, но в [store] НЕ
 * записывается — проба не должна оставлять следов в known_hosts и фиксировать постоянное доверие.
 * Постоянное доверие ключу закрепляется только при реальном подключении ([TofuHostKeyVerifier]).
 * Зафиксирован [app.skerry.shared.ssh.ProbeHostKeyVerifierTest].
 */
class ProbeHostKeyVerifier(
    private val store: KnownHostsStore,
) : HostKeyVerifier {
    override fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean {
        val existing = store.all().firstOrNull {
            it.host == host && it.port == port && it.keyType == keyType
        }
        return existing == null || existing.fingerprint == fingerprint
    }
}
