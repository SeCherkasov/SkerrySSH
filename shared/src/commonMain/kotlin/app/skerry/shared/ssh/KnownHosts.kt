package app.skerry.shared.ssh

/**
 * Запись об известном ключе хоста. Идентичность ключа — тройка (host, port, keyType):
 * один хост может предъявлять ключи разных типов. [fingerprint] — формат OpenSSH
 * (`SHA256:` + base64 без паддинга), как и в [HostKeyVerifier].
 */
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
)

/** Персистентное хранилище известных ключей хостов. Платформенная реализация — файловая. */
interface KnownHostsStore {
    fun all(): List<KnownHost>

    /** Добавить новую запись. Вызывается только для ранее неизвестной тройки (host, port, keyType). */
    fun add(host: KnownHost)
}

/**
 * Trust-on-first-use поверх [KnownHostsStore]: первый ключ для тройки (host, port, keyType)
 * принимается и запоминается; при последующих подключениях принимается только совпадающий
 * fingerprint. Несовпадение → отказ (смена ключа / возможный MITM); доверенная запись при
 * этом не перезаписывается. Новый тип ключа для известного хоста трактуется как новый ключ.
 *
 * Интерактивного подтверждения отпечатка при первом подключении пока нет — придёт вместе
 * с UI менеджера хостов.
 */
class TofuHostKeyVerifier(private val store: KnownHostsStore) : HostKeyVerifier {
    override fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean {
        val existing = store.all().firstOrNull {
            it.host == host && it.port == port && it.keyType == keyType
        }
        return when (existing) {
            null -> {
                store.add(KnownHost(host, port, keyType, fingerprint))
                true
            }
            else -> existing.fingerprint == fingerprint
        }
    }
}
