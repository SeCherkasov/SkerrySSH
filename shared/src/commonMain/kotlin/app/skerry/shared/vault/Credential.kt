package app.skerry.shared.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Секрет аутентификации в keychain — сырой материал (ключ/пароль/сертификат), хранимый в
 * [Credential]. Полиморфно сериализуется внутрь зашифрованного payload записи vault; `@SerialName`
 * фиксирует стабильное wire-имя в дискриминаторе, чтобы рефакторинг/переименование пакета или
 * минификация (R8) не сделали уже записанные блобы нечитаемыми. Имена дискриминатора
 * («password»/«private_key»/«certificate») унаследованы от прежнего `IdentityAuth`, поэтому секреты,
 * записанные до разделения keychain/учётки, читаются без миграции payload-а.
 *
 * `toString` редактится — секрет не должен утечь в логи/краш-репорты. Секреты держатся как `String`:
 * на JVM их нельзя обнулить (живут в куче до GC); это принятое ограничение текущего этапа.
 */
@Serializable
sealed interface CredentialSecret {
    /** Пароль пользователя. */
    @Serializable
    @SerialName("password")
    data class Password(val password: String) : CredentialSecret {
        override fun toString(): String = "Password(redacted)"
    }

    /** Приватный ключ в PEM (OpenSSH/PKCS) и необязательная passphrase для его расшифровки. */
    @Serializable
    @SerialName("private_key")
    data class PrivateKey(val privateKeyPem: String, val passphrase: String? = null) : CredentialSecret {
        override fun toString(): String = "PrivateKey(redacted)"
    }

    /**
     * SSH-сертификат: приватный ключ ([privateKeyPem]) плюс выданный CA сертификат ([certificate],
     * строка `*-cert.pub` вида `ssh-…-cert-v01@openssh.com <base64> [comment]`). При аутентификации
     * клиент предъявляет сертификат, а доказывает владение приватным ключом — поэтому оба хранятся
     * вместе. [passphrase] расшифровывает приватный ключ, если он зашифрован. Сертификат публичен,
     * но приватный ключ/passphrase — секрет, потому `toString` редактится целиком.
     */
    @Serializable
    @SerialName("certificate")
    data class Certificate(
        val privateKeyPem: String,
        val certificate: String,
        val passphrase: String? = null,
    ) : CredentialSecret {
        override fun toString(): String = "Certificate(redacted)"
    }
}

/**
 * Запись keychain — переиспользуемый секрет (ключ/пароль/сертификат). На неё ссылаются хосты
 * напрямую ([app.skerry.shared.host.Host.credentialId]): один секрет может обслуживать несколько хостов.
 * Целиком — включая [label] — лежит в зашифрованном payload записи [RecordType.CREDENTIAL]: открытые
 * метаданные [VaultRecord] не должны раскрывать имена и типы ключей (zero-knowledge). По той же
 * причине `toString` редактит [label] и [secret], оставляя только [id] (он и так открыт в метаданных).
 */
@Serializable
data class Credential(
    val id: String,
    val label: String,
    val secret: CredentialSecret,
) {
    override fun toString(): String = "Credential(id=$id, label=redacted, secret=redacted)"
}
