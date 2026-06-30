package app.skerry.shared.ssh

import kotlinx.serialization.Serializable

/**
 * Транспорт профиля подключения. [SSH] — интерактивный shell поверх SSH (полный набор возможностей:
 * SFTP, проброс портов, метрики). [TELNET] — сырой TCP-стрим с Telnet-неготиацией опций (RFC 854),
 * без аутентификации/шифрования и без SFTP/пробросов. [SERIAL] — локальный последовательный порт
 * (desktop: нативный порт, Android: USB-OTG позже); в профиле `address` хранит имя устройства, `port` —
 * скорость (baud).
 *
 * Живёт в пакете `ssh`, потому что это транспортный признак: [SshTarget.connectionType] несёт его в
 * маршрутизатор транспортов ([RoutingTransport]), а [app.skerry.shared.host.Host.connectionType] — в
 * профиле. Сериализуется по имени (как [app.skerry.shared.ai.AiPolicy]): порядок значений на обратную
 * совместимость не влияет, отсутствие поля в старых файлах даёт дефолт [SSH].
 */
@Serializable
enum class ConnectionType { SSH, TELNET, SERIAL }
