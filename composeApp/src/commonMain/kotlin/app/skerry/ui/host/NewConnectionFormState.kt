package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind

/**
 * Способ аутентификации, выбранный в форме «New connection».
 * - [ASK] — секрет не хранить, пароль спрашивается при каждом подключении (хост без учётки);
 * - [EXISTING] — привязать уже сохранённую в vault учётку ([existingIdentityId]);
 * - [NEW_PASSWORD] / [NEW_KEY] — создать новый keychain-секрет (пароль / приватный ключ) и учётку
 *   поверх него, затем привязать хост к учётке.
 */
enum class AuthMode { ASK, EXISTING, NEW_PASSWORD, NEW_KEY }

/**
 * Состояние формы «New connection» (модалка дизайн-слоя): редактируемые поля профиля как
 * Compose-state. Идентичность ([Host.id]) присваивает [HostManagerController] на сохранении,
 * поэтому форма оперирует черновиком и отдаёт [HostDraft] через [toDraft].
 *
 * Валидация ([canSave]) и парсинг порта/секрета — здесь (чистая логика, без рендера), зафиксированы
 * [app.skerry.ui.host.NewConnectionFormStateTest]; UI лишь связывает поля и кнопку Save.
 *
 * Аутентификация ([authMode]) разворачивается в идентификатор vault-записи через [resolveIdentityId]:
 * для новых секретов форма не пишет в vault сама, а вызывает переданный `saveIdentity` (обычно
 * [app.skerry.ui.identity.IdentityManagerController.save]) — побочный эффект остаётся снаружи, логика
 * выбора тестируема. AI-политика и теги в черновик пока не входят (отдельные слайсы).
 */
@Stable
class NewConnectionFormState {
    var name: String by mutableStateOf("")
    var address: String by mutableStateOf("")
    var port: String by mutableStateOf("22")
    var username: String by mutableStateOf("")
    var group: String by mutableStateOf("")

    // Аутентификация: режим + поля под каждый вид (держатся рядом, чтобы переключение не теряло ввод).
    var authMode: AuthMode by mutableStateOf(AuthMode.ASK)
    var existingIdentityId: String? by mutableStateOf(null)
    var password: String by mutableStateOf("")
    var privateKeyPem: String by mutableStateOf("")
    var passphrase: String by mutableStateOf("")

    /** Порт как валидное число в диапазоне TCP-портов, иначе `null`. */
    val portOrNull: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** Заполнен ли выбранный способ аутентификации (для [canSave]). */
    private val authValid: Boolean
        get() = when (authMode) {
            AuthMode.ASK -> true
            AuthMode.EXISTING -> existingIdentityId != null
            AuthMode.NEW_PASSWORD -> password.isNotEmpty()
            AuthMode.NEW_KEY -> privateKeyPem.isNotBlank()
        }

    /** Можно ли сохранять: имя/адрес/пользователь не пусты, порт валиден и аутентификация заполнена. */
    val canSave: Boolean
        get() = name.isNotBlank() && address.isNotBlank() && username.isNotBlank() && portOrNull != null && authValid

    /** Метка автосоздаваемой identity — `user@address`, чтобы её было видно во вкладке Vault. */
    private fun identityLabel(): String = "${username.trim()}@${address.trim()}"

    /**
     * Разрешить [Host.identityId] (id учётки) для черновика: для [AuthMode.EXISTING] — выбранная
     * учётка, для новых секретов — создать keychain-секрет через [saveCredential], затем учётку
     * поверх него через [saveAccount] и вернуть её id; для [AuthMode.ASK] — `null` (секрет не
     * хранится). Колбэки вызываются ровно для новых секретов (пишут в vault); если [saveCredential]
     * вернул `null`, учётка не создаётся.
     */
    fun resolveIdentityId(
        saveCredential: (CredentialDraft) -> String?,
        saveAccount: (label: String, username: String, credentialId: String) -> String?,
    ): String? = when (authMode) {
        AuthMode.ASK -> null
        AuthMode.EXISTING -> existingIdentityId
        AuthMode.NEW_PASSWORD -> wrapInAccount(
            saveCredential(CredentialDraft(label = identityLabel(), kind = CredentialKind.PASSWORD, password = password)),
            saveAccount,
        )
        AuthMode.NEW_KEY -> wrapInAccount(
            saveCredential(
                CredentialDraft(
                    label = identityLabel(),
                    kind = CredentialKind.PRIVATE_KEY,
                    privateKeyPem = privateKeyPem,
                    passphrase = passphrase,
                ),
            ),
            saveAccount,
        )
    }

    // Обернуть свежесозданный keychain-секрет в учётку (username из формы). null credentialId →
    // секрет не сохранился, учётку не плодим.
    private fun wrapInAccount(
        credentialId: String?,
        saveAccount: (label: String, username: String, credentialId: String) -> String?,
    ): String? = credentialId?.let { saveAccount(identityLabel(), username.trim(), it) }

    /** Собрать черновик для [HostManagerController.save]; [id] != null — правка существующего. */
    fun toDraft(id: String? = null, identityId: String? = null): HostDraft = HostDraft(
        id = id,
        label = name.trim(),
        address = address.trim(),
        port = portOrNull ?: 22,
        username = username.trim(),
        group = group.trim().ifBlank { null },
        identityId = identityId,
    )
}
