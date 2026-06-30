package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.host.Host
import app.skerry.shared.host.MAX_TAGS_PER_HOST
import app.skerry.shared.host.normalizeTag
import app.skerry.shared.ssh.ConnectionType
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
 * правятся [addTag]/[removeTag] и идут в черновик. [aiPolicy] — per-host политика AI (см.
 * [app.skerry.shared.ai.AiPolicy]), тоже уходит в черновик.
 */
@Stable
class NewConnectionFormState {
    var name: String by mutableStateOf("")
    var address: String by mutableStateOf("")
    var port: String by mutableStateOf("22")
    var username: String by mutableStateOf("")
    var group: String by mutableStateOf("")

    /**
     * Транспорт профиля. Смена типа через [chooseConnectionType] подставляет дефолтный порт/скорость,
     * если пользователь оставил прежний дефолт. Для [ConnectionType.TELNET]/[ConnectionType.SERIAL]
     * аутентификация не нужна (валидация её не требует).
     */
    var connectionType: ConnectionType by mutableStateOf(ConnectionType.SSH)
        private set

    /**
     * Сменить транспорт. Если [port] всё ещё равен дефолту прежнего типа (пользователь его не трогал),
     * подставляем дефолт нового: SSH→22, Telnet→23, Serial→9600 (baud). Иначе значение сохраняем.
     */
    fun chooseConnectionType(type: ConnectionType) {
        if (type == connectionType) return
        if (port.trim() == defaultPortFor(connectionType).toString()) {
            port = defaultPortFor(type).toString()
        }
        connectionType = type
    }

    // Аутентификация: режим + поля под каждый вид (держатся рядом, чтобы переключение не теряло ввод).
    var authMode: AuthMode by mutableStateOf(AuthMode.ASK)
    var existingCredentialId: String? by mutableStateOf(null)
    var password: String by mutableStateOf("")
    var privateKeyPem: String by mutableStateOf("")
    var passphrase: String by mutableStateOf("")

    /** Метки хоста в канонической форме (см. [normalizeTag]); правится только через [addTag]/[removeTag]. */
    var tags: List<String> by mutableStateOf(emptyList())
        private set

    /** Per-host политика AI (принцип «AI under policy»). Дефолт — [AiPolicy.Strict] (облако запрещено). */
    var aiPolicy: AiPolicy by mutableStateOf(AiPolicy.Strict)

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

    /**
     * Порт/скорость как валидное число, иначе `null`. Для SSH/Telnet — TCP-порт (1..65535); для
     * Serial [port] несёт скорость (baud), где верхнего предела 65535 нет — валидно любое > 0.
     */
    val portOrNull: Int?
        get() = port.trim().toIntOrNull()?.takeIf {
            if (connectionType == ConnectionType.SERIAL) it > 0 else it in 1..65535
        }

    /** Заполнен ли выбранный способ аутентификации (для [canSave]). */
    private val authValid: Boolean
        get() = when (authMode) {
            AuthMode.ASK -> true
            AuthMode.EXISTING -> existingCredentialId != null
            AuthMode.NEW_PASSWORD -> password.isNotEmpty()
            AuthMode.NEW_KEY -> privateKeyPem.isNotBlank()
        }

    /**
     * Можно ли сохранять. Общее: имя/адрес не пусты, порт/скорость валидны. Для [ConnectionType.SSH]
     * дополнительно нужен пользователь и заполненная аутентификация; у Telnet/Serial их нет (у Serial
     * [address] — имя устройства, [username]/auth не используются).
     */
    val canSave: Boolean
        get() {
            val base = name.isNotBlank() && address.isNotBlank() && portOrNull != null
            return when (connectionType) {
                ConnectionType.SSH -> base && username.isNotBlank() && authValid
                ConnectionType.TELNET, ConnectionType.SERIAL -> base
            }
        }

    /** Метка автосоздаваемого секрета — `user@address`, чтобы его было видно во вкладке Vault. */
    private fun identityLabel(): String = "${username.trim()}@${address.trim()}"

    /**
     * Разрешить [Host.credentialId] (id keychain-секрета) для черновика: для [AuthMode.EXISTING] —
     * выбранный секрет, для новых секретов — создать его через [saveCredential] и вернуть id; для
     * [AuthMode.ASK] — `null` (секрет не хранится). [saveCredential] вызывается ровно для новых
     * секретов (пишет в vault); если он вернул `null`, привязки нет.
     */
    fun resolveCredentialId(saveCredential: (CredentialDraft) -> String?): String? = when {
        // У Telnet/Serial аутентификации нет — секрет не привязываем.
        connectionType != ConnectionType.SSH -> null
        else -> when (authMode) {
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
    }

    /** Собрать черновик для [HostManagerController.save]; [id] != null — правка существующего. */
    fun toDraft(id: String? = null, credentialId: String? = null): HostDraft = HostDraft(
        id = id,
        label = name.trim(),
        address = address.trim(),
        port = portOrNull ?: defaultPortFor(connectionType),
        username = username.trim(),
        group = group.trim().ifBlank { null },
        credentialId = credentialId,
        tags = tags,
        aiPolicy = aiPolicy,
        connectionType = connectionType,
    )

    companion object {
        /** Дефолтный порт/скорость по типу: SSH→22, Telnet→23, Serial→9600 (baud). */
        fun defaultPortFor(type: ConnectionType): Int = when (type) {
            ConnectionType.SSH -> 22
            ConnectionType.TELNET -> 23
            ConnectionType.SERIAL -> 9600
        }

        /**
         * Предзаполнить форму полями существующего [host] для режима правки. Привязанный секрет
         * ([Host.credentialId]) разворачивается в [AuthMode.EXISTING] на тот же id — так
         * [resolveCredentialId] вернёт его без пересоздания (секрет не дублируется), а пользователь
         * при желании может сменить способ аутентификации. Без секрета — [AuthMode.ASK], как у нового
         * хоста. [Host.id] форма не держит: его передают в [toDraft] на сохранении.
         */
        fun fromHost(host: Host): NewConnectionFormState = NewConnectionFormState().apply {
            connectionType = host.connectionType
            name = host.label
            address = host.address
            port = host.port.toString()
            username = host.username
            group = host.group ?: ""
            tags = host.tags
            aiPolicy = host.aiPolicy
            if (host.credentialId != null) {
                authMode = AuthMode.EXISTING
                existingCredentialId = host.credentialId
            }
        }
    }
}
