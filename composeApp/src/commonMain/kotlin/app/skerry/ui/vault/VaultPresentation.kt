package app.skerry.ui.vault

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth

/**
 * Категории менеджера vault, 1:1 с макетом `docs/new/Skerry.html` ([title]/[icon] — текст и
 * Material-Symbols-иконка sidebar). [hasBackend] == true — категория наполняется живыми записями
 * vault (ключи/пароли); false — макетная категория без бэкенда ([IDENTITIES]/[CERTIFICATES]),
 * показывается как заглушка до появления соответствующей модели.
 */
enum class VaultCategoryKind(val title: String, val icon: String, val hasBackend: Boolean) {
    SSH_KEYS("SSH keys", "key", hasBackend = true),
    IDENTITIES("Identities", "badge", hasBackend = false),
    PASSWORDS("Passwords", "password", hasBackend = true),
    CERTIFICATES("Certificates", "vpn_lock", hasBackend = true),
}

/**
 * Чистая presentation-логика раздела Vault поверх списка [Identity] и каталога хостов: раскладывает
 * identity по категориям и считает, какие хосты ссылаются на секрет. Без Compose/IO — тестируется как
 * обычная функция; UI ([VaultView]) лишь рендерит результат.
 */
object VaultPresentation {

    /** Категория, в которой живёт [identity]: приватный ключ → [SSH_KEYS], пароль → [PASSWORDS]. */
    fun categoryOf(identity: Identity): VaultCategoryKind = when (identity.auth) {
        is IdentityAuth.PrivateKey -> VaultCategoryKind.SSH_KEYS
        is IdentityAuth.Password -> VaultCategoryKind.PASSWORDS
        is IdentityAuth.Certificate -> VaultCategoryKind.CERTIFICATES
    }

    /** Identity выбранной категории (для бэкендовых — фильтр по типу; для заглушек — всегда пусто). */
    fun identitiesIn(kind: VaultCategoryKind, identities: List<Identity>): List<Identity> =
        if (!kind.hasBackend) emptyList() else identities.filter { categoryOf(it) == kind }

    /** Сколько живых записей в категории (для счётчика в sidebar). */
    fun count(kind: VaultCategoryKind, identities: List<Identity>): Int =
        identitiesIn(kind, identities).size

    /**
     * Хосты, привязанные к секрету [identityId] (по [Host.identityId]). Используется и для блока
     * «Used by hosts» в деталях, и для развязки при удалении (эти хосты теряют привязку).
     */
    fun hostsUsing(identityId: String, hosts: List<Host>): List<Host> =
        hosts.filter { it.identityId == identityId }
}
