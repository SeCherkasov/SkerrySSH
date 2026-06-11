package app.skerry.shared.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Способ аутентификации, хранимый в [Identity]. Полиморфно сериализуется внутрь зашифрованного
 * payload записи vault; `@SerialName` фиксирует стабильное wire-имя в дискриминаторе, чтобы
 * рефакторинг/переименование пакета или минификация (R8) не сделали уже записанные блобы
 * нечитаемыми. `toString` редактится — секрет не должен утечь в логи/краш-репорты.
 *
 * Секреты держатся как `String`: на JVM их нельзя обнулить (живут в куче до GC). Это принятое
 * ограничение текущего этапа (как у [app.skerry.shared.ssh.SshAuth.Password]); переход на
 * затираемые `CharArray`/`ByteArray` — отдельный шаг вместе с секьюрным вводом.
 */
@Serializable
sealed interface IdentityAuth {
    /** Пароль пользователя. */
    @Serializable
    @SerialName("password")
    data class Password(val password: String) : IdentityAuth {
        override fun toString(): String = "Password(redacted)"
    }

    /** Приватный ключ в PEM (OpenSSH/PKCS) и необязательная passphrase для его расшифровки. */
    @Serializable
    @SerialName("private_key")
    data class PrivateKey(val privateKeyPem: String, val passphrase: String? = null) : IdentityAuth {
        override fun toString(): String = "PrivateKey(redacted)"
    }
}

/**
 * Переиспользуемый секрет аутентификации (как «identity» в Termius): на него ссылаются хосты по
 * [id], один ключ/пароль может обслуживать несколько хостов. Целиком — включая [label] — лежит в
 * зашифрованном payload записи [RecordType.IDENTITY]: открытые метаданные [VaultRecord] не должны
 * раскрывать имена и типы ключей (zero-knowledge). По той же причине `toString` редактит [label]
 * и [auth], оставляя только [id] (он и так открыт в метаданных).
 */
@Serializable
data class Identity(
    val id: String,
    val label: String,
    val auth: IdentityAuth,
) {
    override fun toString(): String = "Identity(id=$id, label=redacted, auth=redacted)"
}
