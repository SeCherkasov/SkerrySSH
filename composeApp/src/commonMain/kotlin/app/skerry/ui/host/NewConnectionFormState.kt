package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.host.Host
import app.skerry.shared.tag.MAX_TAGS_PER_RECORD
import app.skerry.shared.tag.normalizeTag
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.isVnc
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind

/**
 * Auth mode selected in the "New connection" form.
 * - [ASK]: don't store a secret, ask for the password on every connect (host without a secret);
 * - [EXISTING]: attach an already-saved keychain secret ([existingCredentialId]);
 * - [NEW_PASSWORD] / [NEW_KEY]: create a new keychain secret (password / private key) and attach
 *   the host to it.
 */
enum class AuthMode { ASK, EXISTING, NEW_PASSWORD, NEW_KEY }

/**
 * "New connection" form state (design-layer modal): editable profile fields as Compose state.
 * Identity ([Host.id]) is assigned by [HostManagerController] on save, so the form works with a
 * draft and hands off a [HostDraft] via [toDraft].
 *
 * Validation ([canSave]) and port/secret parsing live here (pure logic, no rendering), covered by
 * [app.skerry.ui.host.NewConnectionFormStateTest]; the UI just wires fields and the Save button.
 *
 * Auth ([authMode]) resolves to a keychain secret id via [resolveCredentialId]: for new secrets
 * the form doesn't write to the vault itself, it calls the passed-in `saveCredential` (usually
 * [app.skerry.ui.identity.CredentialManagerController.save]) — the side effect stays outside, so
 * the selection logic is testable. Tags are kept in canonical form ([normalizeTag]), edited via
 * [addTag]/[removeTag] and go into the draft. [aiPolicy] is the per-host AI policy (see
 * [app.skerry.shared.ai.AiPolicy]), also carried into the draft.
 */
@Stable
class NewConnectionFormState {
    var name: String by mutableStateOf("")
    var address: String by mutableStateOf("")
    var port: String by mutableStateOf("22")
    var username: String by mutableStateOf("")
    var group: String by mutableStateOf("")

    /**
     * Profile transport. Changing it via [chooseConnectionType] substitutes the default port/speed
     * if the user left the previous default in place. [ConnectionType.TELNET]/[ConnectionType.SERIAL]
     * need no auth (validation doesn't require it).
     */
    var connectionType: ConnectionType by mutableStateOf(ConnectionType.SSH)
        private set

    /**
     * Switch transport. If [port] still equals the previous type's default (user hasn't touched
     * it), substitute the new type's default: SSH/Mosh->22, Telnet->23, Serial->9600 (baud).
     * Otherwise the value is kept. Leaving the SSH-auth family drops the jump host (ProxyJump
     * needs the SSH hop); switching to VNC additionally drops key/selected-secret auth (see
     * [dropNonVncAuth]). [keepAliveSeconds] is deliberately kept: unlike a jump reference it's
     * harmless on other profiles (the session layer gates on SSH), and the choice survives
     * toggling back to SSH.
     */
    fun chooseConnectionType(type: ConnectionType) {
        if (type == connectionType) return
        if (port.trim() == defaultPortFor(connectionType).toString()) {
            port = defaultPortFor(type).toString()
        }
        // Agent forwarding rides the SSH session channel, so it goes with the jump host when the
        // profile leaves the SSH family (Mosh runs its own protocol after the bootstrap exec).
        if (type != ConnectionType.SSH) forwardAgent = false
        if (!type.usesSshAuth) jumpHostId = null
        if (type.isVnc) dropNonVncAuth()
        connectionType = type
    }

    /**
     * VNC auth is password-only: leftover key state — or an already-picked saved secret, possibly
     * a key the form can't tell apart by id — would silently degrade to no auth at connect
     * (`toVncAuth` maps non-password secrets to `VncAuth.None`), so both reset to Ask. A
     * new-password entry stays: it's valid for VNC as-is.
     */
    private fun dropNonVncAuth() {
        if (authMode == AuthMode.NEW_KEY || authMode == AuthMode.EXISTING) authMode = AuthMode.ASK
        existingCredentialId = null
        privateKeyPem = ""
        passphrase = ""
    }

    /** Saved SSH profile to tunnel through (ProxyJump), `null` — connect directly. */
    var jumpHostId: String? by mutableStateOf(null)

    /** Keep-alive cadence for this profile's sessions, seconds (0 = off); see [Host.keepAliveSeconds]. */
    var keepAliveSeconds: Int by mutableStateOf(30)

    /** Forward the built-in SSH agent into this host's shell; see [Host.forwardAgent]. */
    var forwardAgent: Boolean by mutableStateOf(false)

    /** Vault keys this host may use through the agent; empty = every key in it ([Host.agentKeyIds]). */
    var agentKeyIds: List<String> by mutableStateOf(emptyList())

    /**
     * Tick or untick one key for this host. Ticking also puts it in the agent ([addToAgent]) —
     * otherwise the profile would name a key the agent does not hold, and forwarding would quietly
     * offer nothing. Unticking leaves the agent alone: other hosts (and the local socket) may still
     * be using that key.
     */
    fun toggleAgentKey(id: String, addToAgent: (String) -> Unit) {
        agentKeyIds = if (id in agentKeyIds) {
            agentKeyIds - id
        } else {
            addToAgent(id)
            agentKeyIds + id
        }
    }

    // Auth: mode plus fields for each kind (kept side by side so switching doesn't lose input).
    var authMode: AuthMode by mutableStateOf(AuthMode.ASK)
    var existingCredentialId: String? by mutableStateOf(null)
    var password: String by mutableStateOf("")
    var privateKeyPem: String by mutableStateOf("")
    var passphrase: String by mutableStateOf("")

    /** Host tags in canonical form (see [normalizeTag]); edited only via [addTag]/[removeTag]. */
    var tags: List<String> by mutableStateOf(emptyList())
        private set

    /** Per-host AI policy ("AI under policy" principle). Default is [AiPolicy.Strict] (no cloud). */
    var aiPolicy: AiPolicy by mutableStateOf(AiPolicy.Strict)

    /**
     * Add tag(s) from input: string is split on commas, each part normalized ([normalizeTag]),
     * blanks and duplicates dropped, insertion order kept. UI calls this on Enter/",".
     */
    fun addTag(raw: String) {
        val additions = raw.split(',').mapNotNull(::normalizeTag)
        if (additions.isEmpty()) return
        // Cap on tag count (guards against pasting thousands of labels): drop beyond [MAX_TAGS_PER_RECORD].
        tags = LinkedHashSet(tags).apply { addAll(additions) }.take(MAX_TAGS_PER_RECORD)
    }

    /** Remove a tag (value is already canonical, the one rendered on the pill). */
    fun removeTag(tag: String) {
        tags = tags - tag
    }

    /**
     * Port/speed as a valid number, else `null`. For SSH/Telnet it's a TCP port (1..65535); for
     * Serial, [port] carries the speed (baud), which has no 65535 ceiling, any value > 0 is valid.
     */
    val portOrNull: Int?
        get() = port.trim().toIntOrNull()?.takeIf {
            if (connectionType == ConnectionType.SERIAL) it > 0 else it in 1..65535
        }

    /** Whether the selected auth mode is filled in (for [canSave]). */
    private val authValid: Boolean
        get() = when (authMode) {
            AuthMode.ASK -> true
            AuthMode.EXISTING -> existingCredentialId != null
            AuthMode.NEW_PASSWORD -> password.isNotEmpty()
            AuthMode.NEW_KEY -> privateKeyPem.isNotBlank()
        }

    /**
     * Whether it's savable. Common: name/address non-blank, port/speed valid. SSH and Mosh
     * additionally need a username and filled-in auth (Mosh authenticates over SSH); Telnet/Serial
     * don't (for Serial, [address] is the device name, [username]/auth are unused).
     */
    val canSave: Boolean
        get() {
            val base = name.isNotBlank() && address.isNotBlank() && portOrNull != null
            return when (connectionType) {
                ConnectionType.SSH, ConnectionType.MOSH -> base && username.isNotBlank() && authValid
                ConnectionType.TELNET, ConnectionType.SERIAL -> base
                // VNC authenticates with an optional password (no username): base + a valid auth
                // choice (Ask / a stored password), same secret resolution as SSH minus the username.
                ConnectionType.VNC -> base && authValid
            }
        }

    /** Label for the auto-created secret, `user@address`, so it's recognizable in the Vault tab. */
    private fun identityLabel(): String = "${username.trim()}@${address.trim()}"

    /**
     * Resolve [Host.credentialId] (keychain secret id) for the draft: for [AuthMode.EXISTING], the
     * selected secret; for new secrets, create it via [saveCredential] and return the id; for
     * [AuthMode.ASK], `null` (no secret stored). [saveCredential] is called only for new secrets
     * (writes to the vault); if it returns `null`, there's no attachment.
     */
    fun resolveCredentialId(saveCredential: (CredentialDraft) -> String?): String? = when {
        // Telnet/Serial have no auth, no secret gets attached. VNC does authenticate (password),
        // so it takes the same secret-resolution path as SSH below (the UI just hides the key option).
        !connectionType.usesSshAuth && !connectionType.isVnc -> null
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

    /** Build a draft for [HostManagerController.save]; [id] != null means editing an existing one. */
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
        jumpHostId = jumpHostId,
        keepAliveSeconds = keepAliveSeconds,
        forwardAgent = forwardAgent,
        agentKeyIds = agentKeyIds,
    )

    companion object {
        /** Default port/speed by type: SSH/Mosh->22 (the SSH hop's port), Telnet->23, Serial->9600 (baud), VNC->5900 (RFB display :0). */
        fun defaultPortFor(type: ConnectionType): Int = when (type) {
            ConnectionType.SSH, ConnectionType.MOSH -> 22
            ConnectionType.TELNET -> 23
            ConnectionType.SERIAL -> 9600
            ConnectionType.VNC -> 5900
        }

        /**
         * Prefill the form from an existing [host] for edit mode. The attached secret
         * ([Host.credentialId]) resolves to [AuthMode.EXISTING] on the same id, so
         * [resolveCredentialId] returns it without recreating (no duplicate secret), while the user
         * can still switch auth mode if they want. No secret means [AuthMode.ASK], same as a new
         * host. The form doesn't hold [Host.id], it's passed into [toDraft] on save.
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
            jumpHostId = host.jumpHostId
            keepAliveSeconds = host.keepAliveSeconds
            forwardAgent = host.forwardAgent
            agentKeyIds = host.agentKeyIds
            if (host.credentialId != null) {
                authMode = AuthMode.EXISTING
                existingCredentialId = host.credentialId
            }
        }
    }
}
