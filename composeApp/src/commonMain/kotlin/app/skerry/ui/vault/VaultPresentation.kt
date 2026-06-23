package app.skerry.ui.vault

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.Identity

/**
 * Категории менеджера vault, 1:1 с макетом `docs/new/Skerry.html` ([title]/[icon] — текст и
 * Material-Symbols-иконка sidebar). Три keychain-категории ([SSH_KEYS]/[PASSWORDS]/[CERTIFICATES])
 * наполняются [Credential] по типу секрета; [IDENTITIES] — учётками ([Identity], username + ссылка
 * на keychain-секрет). Все категории живые (бэкенд — открытый vault).
 */
enum class VaultCategoryKind(val title: String, val icon: String) {
    SSH_KEYS("SSH keys", "key"),
    IDENTITIES("Identities", "badge"),
    PASSWORDS("Passwords", "password"),
    CERTIFICATES("Certificates", "vpn_lock"),
}

/**
 * Чистая presentation-логика раздела Vault поверх keychain-секретов ([Credential]), учёток
 * ([Identity]) и каталога хостов: раскладывает секреты по keychain-категориям и считает зависимости
 * (какие учётки ссылаются на секрет, какие хосты — на учётку). Без Compose/IO — тестируется как
 * обычная функция; UI ([VaultView]) лишь рендерит результат.
 */
object VaultPresentation {

    /** Keychain-категория секрета: приватный ключ → [SSH_KEYS], пароль → [PASSWORDS], серт → [CERTIFICATES]. */
    fun categoryOf(credential: Credential): VaultCategoryKind = when (credential.secret) {
        is CredentialSecret.PrivateKey -> VaultCategoryKind.SSH_KEYS
        is CredentialSecret.Password -> VaultCategoryKind.PASSWORDS
        is CredentialSecret.Certificate -> VaultCategoryKind.CERTIFICATES
    }

    /** Keychain-секреты выбранной категории (для [IDENTITIES] всегда пусто — там учётки, не секреты). */
    fun credentialsIn(kind: VaultCategoryKind, credentials: List<Credential>): List<Credential> =
        if (kind == VaultCategoryKind.IDENTITIES) emptyList() else credentials.filter { categoryOf(it) == kind }

    /** Сколько живых записей в категории (для счётчика sidebar): keychain — секреты, Identities — учётки. */
    fun count(kind: VaultCategoryKind, credentials: List<Credential>, accounts: List<Identity>): Int =
        if (kind == VaultCategoryKind.IDENTITIES) accounts.size else credentialsIn(kind, credentials).size

    /** Учётки, ссылающиеся на keychain-секрет [credentialId] (для «used by» и развязки при удалении). */
    fun accountsUsing(credentialId: String, accounts: List<Identity>): List<Identity> =
        accounts.filter { it.credentialId == credentialId }

    /** Хосты, привязанные к учётке [identityId] (по [Host.identityId]) — «used by» и развязка при удалении. */
    fun hostsUsing(identityId: String, hosts: List<Host>): List<Host> =
        hosts.filter { it.identityId == identityId }
}
