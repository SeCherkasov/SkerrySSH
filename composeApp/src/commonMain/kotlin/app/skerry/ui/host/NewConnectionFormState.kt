package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.host.Host
import app.skerry.shared.host.MAX_TAGS_PER_HOST
import app.skerry.shared.host.normalizeTag
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind

/**
 * Способ аутентификации, выбранный в форме «New connection».
 * - [ASK] — секрет не хранить, пароль спрашивается при каждом подключении (хост без секрета);
 * - [EXISTING] — привязать уже сохранённый keychain-секрет ([existingCredentialId]);
 * - [NEW_PASSWORD] / [NEW_KEY] — создать новый keychain-секрет (пароль / приватный ключ) и привязать
 *   к нему хост.
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
 * Аутентификация ([authMode]) разворачивается в идентификатор keychain-секрета через
 * [resolveCredentialId]: для новых секретов форма не пишет в vault сама, а вызывает переданный
 * `saveCredential` (обычно [app.skerry.ui.identity.CredentialManagerController.save]) — побочный
 * эффект остаётся снаружи, логика выбора тестируема. Теги — каноническая форма ([normalizeTag]),
 * правятся [addTag]/[removeTag] и идут в черновик; AI-политика в черновик пока не входит.
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
    var existingCredentialId: String? by mutableStateOf(null)
    var password: String by mutableStateOf("")
    var privateKeyPem: String by mutableStateOf("")
    var passphrase: String by mutableStateOf("")

    /** Метки хоста в канонической форме (см. [normalizeTag]); правится только через [addTag]/[removeTag]. */
    var tags: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * Добавить тег(и) из ввода: строка делится по запятым, каждая часть нормализуется ([normalizeTag]),
     * пустые и уже присутствующие отбрасываются, порядок добавления сохраняется. UI вызывает по Enter/«,».
     */
    fun addTag(raw: String) {
        val additions = raw.split(',').mapNotNull(::normalizeTag)
        if (additions.isEmpty()) return
        // Кап на число тегов (защита от вставки тысяч меток): сверх [MAX_TAGS_PER_HOST] отбрасываем.
        tags = LinkedHashSet(tags).apply { addAll(additions) }.take(MAX_TAGS_PER_HOST)
    }

    /** Убрать тег (значение уже в канонической форме — то, что отрисовано на пилюле). */
    fun removeTag(tag: String) {
        tags = tags - tag
    }

    /** Порт как валидное число в диапазоне TCP-портов, иначе `null`. */
    val portOrNull: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** Заполнен ли выбранный способ аутентификации (для [canSave]). */
    private val authValid: Boolean
        get() = when (authMode) {
            AuthMode.ASK -> true
            AuthMode.EXISTING -> existingCredentialId != null
            AuthMode.NEW_PASSWORD -> password.isNotEmpty()
            AuthMode.NEW_KEY -> privateKeyPem.isNotBlank()
        }

    /** Можно ли сохранять: имя/адрес/пользователь не пусты, порт валиден и аутентификация заполнена. */
    val canSave: Boolean
        get() = name.isNotBlank() && address.isNotBlank() && username.isNotBlank() && portOrNull != null && authValid

    /** Метка автосоздаваемого секрета — `user@address`, чтобы его было видно во вкладке Vault. */
    private fun identityLabel(): String = "${username.trim()}@${address.trim()}"

    /**
     * Разрешить [Host.credentialId] (id keychain-секрета) для черновика: для [AuthMode.EXISTING] —
     * выбранный секрет, для новых секретов — создать его через [saveCredential] и вернуть id; для
     * [AuthMode.ASK] — `null` (секрет не хранится). [saveCredential] вызывается ровно для новых
     * секретов (пишет в vault); если он вернул `null`, привязки нет.
     */
    fun resolveCredentialId(saveCredential: (CredentialDraft) -> String?): String? = when (authMode) {
        AuthMode.ASK -> null
        AuthMode.EXISTING -> existingCredentialId
        AuthMode.NEW_PASSWORD -> saveCredential(
            CredentialDraft(label = identityLabel(), kind = CredentialKind.PASSWORD, password = password),
        )
        AuthMode.NEW_KEY -> saveCredential(
            CredentialDraft(
                label = identityLabel(),
                kind = CredentialKind.PRIVATE_KEY,
                privateKeyPem = privateKeyPem,
                passphrase = passphrase,
            ),
        )
    }

    /** Собрать черновик для [HostManagerController.save]; [id] != null — правка существующего. */
    fun toDraft(id: String? = null, credentialId: String? = null): HostDraft = HostDraft(
        id = id,
        label = name.trim(),
        address = address.trim(),
        port = portOrNull ?: 22,
        username = username.trim(),
        group = group.trim().ifBlank { null },
        credentialId = credentialId,
        tags = tags,
    )

    companion object {
        /**
         * Предзаполнить форму полями существующего [host] для режима правки. Привязанный секрет
         * ([Host.credentialId]) разворачивается в [AuthMode.EXISTING] на тот же id — так
         * [resolveCredentialId] вернёт его без пересоздания (секрет не дублируется), а пользователь
         * при желании может сменить способ аутентификации. Без секрета — [AuthMode.ASK], как у нового
         * хоста. [Host.id] форма не держит: его передают в [toDraft] на сохранении.
         */
        fun fromHost(host: Host): NewConnectionFormState = NewConnectionFormState().apply {
            name = host.label
            address = host.address
            port = host.port.toString()
            username = host.username
            group = host.group ?: ""
            tags = host.tags
            if (host.credentialId != null) {
                authMode = AuthMode.EXISTING
                existingCredentialId = host.credentialId
            }
        }
    }
}
