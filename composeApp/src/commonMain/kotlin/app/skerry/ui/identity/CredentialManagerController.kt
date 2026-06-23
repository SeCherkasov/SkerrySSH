package app.skerry.ui.identity

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore

/** Вид keychain-секрета в форме: разворачивается в [CredentialSecret]. */
enum class CredentialKind { PASSWORD, PRIVATE_KEY, CERTIFICATE }

/**
 * Редактируемые поля keychain-секрета без [Credential.id]. Поля всех видов держатся рядом (форма
 * переключается без потери ввода); в [CredentialSecret] разворачивается только активный [kind].
 * [id] == null — создаётся новый секрет, иначе обновляется существующий. [certificate] — строка
 * `*-cert.pub` (для [CredentialKind.CERTIFICATE]; приватный ключ берётся из [privateKeyPem]).
 */
data class CredentialDraft(
    val id: String? = null,
    val label: String,
    val kind: CredentialKind,
    val password: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
    val certificate: String = "",
) {
    fun toSecret(): CredentialSecret = when (kind) {
        CredentialKind.PASSWORD -> CredentialSecret.Password(password)
        CredentialKind.PRIVATE_KEY -> CredentialSecret.PrivateKey(privateKeyPem, passphrase.ifBlank { null })
        CredentialKind.CERTIFICATE -> CredentialSecret.Certificate(privateKeyPem, certificate, passphrase.ifBlank { null })
    }

    // Секрет не должен утечь в логи/сообщения исключений: держим только метаданные.
    override fun toString(): String = "CredentialDraft(id=$id, label=redacted, kind=$kind, secrets=redacted)"
}

/**
 * Состояние списка keychain-секретов [Credential] поверх [CredentialStore]: держит список как
 * Compose-state и сводит мутации к стору, перечитывая после каждой. Синхронный (vault-CRUD редок).
 * Требует разблокированного vault (живёт за гейтом мастер-пароля).
 */
@Stable
class CredentialManagerController(
    private val store: CredentialStore,
    private val newId: () -> String,
) {
    var credentials by mutableStateOf(emptyList<Credential>())
        private set

    /** Перечитать список из vault. Требует разблокированного vault (вызывать после unlock). */
    fun reload() {
        credentials = store.all()
    }

    fun find(id: String?): Credential? = id?.let { wanted -> credentials.firstOrNull { it.id == wanted } }

    /** Создать (если [CredentialDraft.id] == null) или обновить секрет; возвращает назначенный id. */
    fun save(draft: CredentialDraft): String {
        val id = draft.id ?: newId()
        store.put(Credential(id = id, label = draft.label, secret = draft.toSecret()))
        credentials = store.all()
        return id
    }

    fun delete(id: String) {
        store.remove(id)
        credentials = store.all()
    }
}
