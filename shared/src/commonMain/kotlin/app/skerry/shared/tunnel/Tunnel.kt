package app.skerry.shared.tunnel

import kotlinx.serialization.Serializable

/** Направление проброса сохранённого туннеля: `-L` / `-R` / `-D` (см. spec'и в ssh-ядре). */
@Serializable
enum class TunnelDirection { Local, Remote, Dynamic }

/**
 * Сохранённый туннель (port forwarding) в духе привычных SSH-клиентов: самостоятельный объект, а не эфемерная часть
 * открытой терминальной сессии. Идентичность — стабильный [id] (назначается при создании, не меняется
 * при правках). [label] — отображаемое имя; [hostId] ссылается на [app.skerry.shared.host.Host], через
 * который поднимается проброс (secret берётся из vault по [Host.credentialId] при активации).
 *
 * [bindHost]/[bindPort] — слушатель на этой машине (для `-L`/`-D`) либо на сервере (для `-R`); `0` —
 * порт выберет ОС/сервер. [destHost]/[destPort] — адрес назначения для `-L`/`-R`; для `-D` (SOCKS5)
 * назначения нет, поэтому оба `null`.
 *
 * Сам секрет тут НЕ хранится — туннель ссылается на хост, а хост на keychain-запись vault.
 */
@Serializable
data class Tunnel(
    val id: String,
    val label: String,
    val hostId: String,
    val direction: TunnelDirection,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
)
