package app.skerry.ui.identity

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth
import app.skerry.shared.vault.IdentityStore

/** Вид секрета в форме identity: разворачивается в [IdentityAuth]. */
enum class IdentityKind { PASSWORD, PRIVATE_KEY, CERTIFICATE }

/**
 * Редактируемые поля identity без [Identity.id]. Поля всех видов держатся рядом (форма
 * переключается без потери ввода); в [IdentityAuth] разворачивается только активный [kind].
 * [id] == null — создаётся новая identity, иначе обновляется существующая. [certificate] — строка
 * `*-cert.pub` (для [IdentityKind.CERTIFICATE]; приватный ключ берётся из [privateKeyPem]).
 */
data class IdentityDraft(
    val id: String? = null,
    val label: String,
    val kind: IdentityKind,
    val password: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
    val certificate: String = "",
) {
    fun toAuth(): IdentityAuth = when (kind) {
        IdentityKind.PASSWORD -> IdentityAuth.Password(password)
        IdentityKind.PRIVATE_KEY -> IdentityAuth.PrivateKey(privateKeyPem, passphrase.ifBlank { null })
        IdentityKind.CERTIFICATE -> IdentityAuth.Certificate(privateKeyPem, certificate, passphrase.ifBlank { null })
    }

    // Секрет не должен утечь в логи/сообщения исключений (как и у [Identity]): держим только метаданные.
    override fun toString(): String = "IdentityDraft(id=$id, label=redacted, kind=$kind, secrets=redacted)"
}

/**
 * Состояние списка переиспользуемых [Identity] поверх [IdentityStore]: держит список как
 * Compose-state и сводит мутации к стору, перечитывая после каждой — как
 * [app.skerry.ui.host.HostManagerController]. Синхронный (vault-CRUD редок), без корутинной scope.
 * Требует разблокированного vault (живёт за гейтом мастер-пароля).
 */
@Stable
class IdentityManagerController(
    private val store: IdentityStore,
    private val newId: () -> String,
) {
    // Пусто на старте: контроллер создаётся до разблокировки vault, а [IdentityStore]/[Vault]
    // на залоченном vault бросает. [reload] вызывается из UI после входа за гейт мастер-пароля.
    var identities by mutableStateOf(emptyList<Identity>())
        private set

    /** Перечитать список из vault. Требует разблокированного vault (вызывать после unlock). */
    fun reload() {
        identities = store.all()
    }

    fun find(id: String?): Identity? = id?.let { wanted -> identities.firstOrNull { it.id == wanted } }

    /** Создать (если [IdentityDraft.id] == null) или обновить identity; возвращает назначенный id. */
    fun save(draft: IdentityDraft): String {
        val id = draft.id ?: newId()
        store.put(Identity(id = id, label = draft.label, auth = draft.toAuth()))
        identities = store.all()
        return id
    }

    fun delete(id: String) {
        store.remove(id)
        identities = store.all()
    }
}
