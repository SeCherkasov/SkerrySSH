package app.skerry.ui.identity

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityStore

/**
 * Состояние списка учёток [Identity] (label + username + ссылка на keychain-секрет) поверх
 * [IdentityStore]: держит список как Compose-state и сводит мутации к стору, перечитывая после
 * каждой — как [app.skerry.ui.host.HostManagerController]. Синхронный (vault-CRUD редок).
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

    /**
     * Создать (если [id] == null) или обновить учётку; возвращает назначенный id. [credentialId]
     * ссылается на существующий keychain-секрет ([app.skerry.shared.vault.Credential]).
     */
    fun save(label: String, username: String, credentialId: String, id: String? = null): String {
        val accountId = id ?: newId()
        store.put(Identity(id = accountId, label = label, username = username, credentialId = credentialId))
        identities = store.all()
        return accountId
    }

    fun delete(id: String) {
        store.remove(id)
        identities = store.all()
    }
}
