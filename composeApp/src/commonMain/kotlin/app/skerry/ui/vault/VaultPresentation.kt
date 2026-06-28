package app.skerry.ui.vault

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret

/**
 * Категории менеджера vault ([title]/[icon] — текст и
 * Material-Symbols-иконка sidebar). Три keychain-категории ([SSH_KEYS]/[PASSWORDS]/[CERTIFICATES])
 * наполняются [Credential] по типу секрета. Все категории живые (бэкенд — открытый vault).
 */
enum class VaultCategoryKind(val title: String, val icon: String) {
    SSH_KEYS("SSH keys", "key"),
    PASSWORDS("Passwords", "password"),
    CERTIFICATES("Certificates", "vpn_lock"),
}

/**
 * Чистая presentation-логика раздела Vault поверх keychain-секретов ([Credential]) и каталога
 * хостов: раскладывает секреты по категориям и считает зависимости (какие хосты ссылаются на секрет).
 * Без Compose/IO — тестируется как обычная функция; UI ([VaultView]) лишь рендерит результат.
 */
object VaultPresentation {

    /**
     * Категории, показываемые в сайдбаре Vault. Сущности «учётки» (Identities) больше нет — модель
     * схлопнута до одного уровня (хост → keychain-секрет), поэтому в сайдбаре только три keychain-
     * категории.
     */
    val sidebarCategories: List<VaultCategoryKind> = VaultCategoryKind.entries

    /** Keychain-категория секрета: приватный ключ → [SSH_KEYS], пароль → [PASSWORDS], серт → [CERTIFICATES]. */
    fun categoryOf(credential: Credential): VaultCategoryKind = when (credential.secret) {
        is CredentialSecret.PrivateKey -> VaultCategoryKind.SSH_KEYS
        is CredentialSecret.Password -> VaultCategoryKind.PASSWORDS
        is CredentialSecret.Certificate -> VaultCategoryKind.CERTIFICATES
    }

    /** Keychain-секреты выбранной категории. */
    fun credentialsIn(kind: VaultCategoryKind, credentials: List<Credential>): List<Credential> =
        credentials.filter { categoryOf(it) == kind }

    /** Сколько живых секретов в категории (для счётчика sidebar). */
    fun count(kind: VaultCategoryKind, credentials: List<Credential>): Int =
        credentialsIn(kind, credentials).size

    /** Хосты, привязанные к keychain-секрету [credentialId] (по [Host.credentialId]) — «used by» и развязка при удалении. */
    fun hostsUsing(credentialId: String, hosts: List<Host>): List<Host> =
        hosts.filter { it.credentialId == credentialId }

    /** Подпись «used by N host(s)» с правильной формой числа для карточки секрета (desktop + mobile). */
    fun usedByLabel(count: Int): String =
        if (count == 1) "used by 1 host" else "used by $count hosts"
}
