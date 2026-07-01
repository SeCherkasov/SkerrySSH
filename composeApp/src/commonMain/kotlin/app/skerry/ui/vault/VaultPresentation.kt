package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_category_certificates
import app.skerry.ui.generated.resources.vtail_category_passwords
import app.skerry.ui.generated.resources.vtail_category_ssh_keys
import app.skerry.ui.generated.resources.vtail_used_by_one
import app.skerry.ui.generated.resources.vtail_used_by_other
import org.jetbrains.compose.resources.stringResource

/**
 * Категории менеджера vault ([icon] — Material-Symbols-иконка sidebar; локализованная подпись —
 * [title]). Три keychain-категории ([SSH_KEYS]/[PASSWORDS]/[CERTIFICATES]) наполняются [Credential]
 * по типу секрета. Все категории живые (бэкенд — открытый vault).
 */
enum class VaultCategoryKind(val icon: String) {
    SSH_KEYS("key"),
    PASSWORDS("password"),
    CERTIFICATES("vpn_lock"),
}

/** Локализованная подпись категории Vault (sidebar/заголовок). */
@Composable
fun VaultCategoryKind.title(): String = when (this) {
    VaultCategoryKind.SSH_KEYS -> stringResource(Res.string.vtail_category_ssh_keys)
    VaultCategoryKind.PASSWORDS -> stringResource(Res.string.vtail_category_passwords)
    VaultCategoryKind.CERTIFICATES -> stringResource(Res.string.vtail_category_certificates)
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

    /** Локализованная подпись «used by N host(s)» для карточки секрета (desktop + mobile). */
    @Composable
    fun usedByLabel(count: Int): String =
        if (count == 1) stringResource(Res.string.vtail_used_by_one)
        else stringResource(Res.string.vtail_used_by_other, count)
}
