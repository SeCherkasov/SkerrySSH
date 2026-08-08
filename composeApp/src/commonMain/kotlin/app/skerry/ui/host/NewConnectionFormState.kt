package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.container.ContainerRuntime
import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.host.Host
import app.skerry.shared.host.normalizeNotes
import app.skerry.shared.rdp.RdpImageQuality
import app.skerry.shared.rdp.RdpSpec
import app.skerry.shared.tag.MAX_TAGS_PER_RECORD
import app.skerry.shared.tag.normalizeTag
import app.skerry.shared.tag.orderTagsProdFirst
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.isRdp
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
enum class AuthMode { ASK, INTERACTIVE, EXISTING, NEW_PASSWORD, NEW_KEY }

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

    /**
     * Windows domain of an RDP logon, edited on its own but stored inside [Host.username] as
     * `DOMAIN\user` — the form every RDP client accepts and the transport splits apart again.
     * Ignored by every other transport.
     */
    var domain: String by mutableStateOf("")

    var group: String by mutableStateOf("")

    /**
     * Free-form remark about the profile, raw as typed; normalized on the way into the draft
     * ([app.skerry.shared.host.normalizeNotes]) so the cap and trimming apply once, at the store's edge.
     */
    var notes: String by mutableStateOf("")

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
        if (isDefaultPort) {
            port = defaultPortFor(type).toString()
        }
        if (!type.usesSshAuth) jumpHostId = null
        if (type.isVnc) dropNonVncAuth()
        connectionType = type
    }

    /**
     * True while [port] still holds the current transport's default — the value the form filled in
     * itself, not one the user typed. Drives the port substitution in [chooseConnectionType] and
     * the select-on-focus rule of the port field.
     */
    val isDefaultPort: Boolean get() = port.trim() == defaultPortFor(connectionType).toString()

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

    // Container profile ([ConnectionType.CONTAINER]): which container/pod on that host to enter.
    // Kept as separate fields (not a [ContainerSpec]) so each input is its own snapshot state; they
    // are assembled and normalized on the way into the draft.

    /** Container runtime the host speaks: `docker` or `kubectl`. */
    var containerRuntime: ContainerRuntime by mutableStateOf(ContainerRuntime.DOCKER)

    /** Container name/id, or pod name for Kubernetes — the one required container field. */
    var containerTarget: String by mutableStateOf("")

    /** Kubernetes namespace; blank uses the host context's default. */
    var containerNamespace: String by mutableStateOf("")

    /** Container inside the pod (Kubernetes `-c`); blank uses the pod's first container. */
    var containerPodContainer: String by mutableStateOf("")

    /** Shell to run inside the container; blank → [app.skerry.shared.container.DEFAULT_CONTAINER_SHELL]. */
    var containerShell: String by mutableStateOf("")

    /** Container fields as a spec, normalized (trimmed); used for the draft and for browsing. */
    fun containerSpec(): ContainerSpec = ContainerSpec(
        runtime = containerRuntime,
        target = containerTarget,
        namespace = containerNamespace,
        podContainer = containerPodContainer,
        shell = containerShell,
    ).normalized()

    // RDP-only settings ([app.skerry.shared.rdp.RdpSpec]). Kept as separate fields for the same
    // reason as the container ones: each is its own snapshot state, and they are assembled on the
    // way into the draft.

    /** Play the remote session's sound on this machine (MS-RDPEA). */
    var rdpAudioOutput: Boolean by mutableStateOf(false)

    /** Output device the sound goes to; blank is the system default. */
    var rdpAudioDeviceId: String by mutableStateOf("")

    /** Share the clipboard with the session, both ways (MS-RDPECLIP). On, as every client has it. */
    var rdpClipboard: Boolean by mutableStateOf(true)

    /**
     * How much of the remote desktop the server is asked to draw. A profile setting because RDP
     * settles the picture at connect time (see [app.skerry.shared.rdp.RdpImageQuality]).
     */
    var rdpQuality: RdpImageQuality by mutableStateOf(RdpImageQuality.DEFAULT)

    /**
     * The farm routing token of an imported `.rdp` profile. Not editable — no user types one — but
     * carried through the form all the same: the draft owns a profile's RDP settings on save, and a
     * token dropped here would send the next connection to an arbitrary host of the farm.
     */
    var rdpLoadBalanceInfo: String by mutableStateOf("")

    /** Saved SSH profile to tunnel through (ProxyJump), `null` — connect directly. */
    var jumpHostId: String? by mutableStateOf(null)

    /** Keep-alive cadence for this profile's sessions, seconds (0 = off); see [Host.keepAliveSeconds]. */
    var keepAliveSeconds: Int by mutableStateOf(30)

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
        // `prod` is hoisted before the cap — the pill that arms the production guard must never be
        // the one that falls off the end (same rule as [normalizeTags]).
        tags = orderTagsProdFirst(LinkedHashSet(tags).apply { addAll(additions) }.toList())
            .take(MAX_TAGS_PER_RECORD)
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
            // Nothing to fill in: the server supplies the questions at connect time.
            AuthMode.INTERACTIVE -> true
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
                // Container: the SSH requirements of the host running the CLI, plus something to
                // exec into (a profile without a container would connect to nothing).
                ConnectionType.CONTAINER ->
                    base && username.isNotBlank() && authValid && containerSpec().isComplete
                // VNC authenticates with an optional password (no username): base + a valid auth
                // choice (Ask / a stored password), same secret resolution as SSH minus the username.
                ConnectionType.VNC -> base && authValid
                // RDP logs on as a Windows user, so it needs a name (optionally `DOMAIN\user`) and
                // a password — the same requirements as SSH, without the SSH-only key material.
                ConnectionType.RDP -> base && username.isNotBlank() && authValid
                // Local shell: no address (blank = default shell), no port, no auth — only a name.
                ConnectionType.LOCAL -> name.isNotBlank()
            }
        }

    /**
     * The user name as it is stored: an RDP profile with a [domain] keeps it as `DOMAIN\user`. A
     * name that already spells out a domain wins — joining both would produce `A\B\user`.
     */
    private fun qualifiedUsername(): String {
        val user = username.trim()
        val prefix = domain.trim()
        val qualifies = connectionType == ConnectionType.RDP &&
            prefix.isNotEmpty() && user.isNotEmpty() && !user.contains('\\')
        return if (qualifies) "$prefix\\$user" else user
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
        // Telnet/Serial have no auth, no secret gets attached. VNC and RDP do authenticate (password),
        // so they take the same secret-resolution path as SSH below (the UI just hides the key option).
        !connectionType.usesSshAuth && !connectionType.isVnc && !connectionType.isRdp -> null
        else -> when (authMode) {
            AuthMode.ASK -> null
            AuthMode.INTERACTIVE -> null
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
        username = qualifiedUsername(),
        group = group.trim().ifBlank { null },
        credentialId = credentialId,
        interactiveAuth = authMode == AuthMode.INTERACTIVE,
        tags = tags,
        aiPolicy = aiPolicy,
        connectionType = connectionType,
        jumpHostId = jumpHostId,
        keepAliveSeconds = keepAliveSeconds,
        notes = normalizeNotes(notes),
        // Only a container profile stores a spec: on another type the (possibly leftover) fields
        // would be dead weight that a later edit could silently resurrect.
        container = containerSpec().takeIf { connectionType == ConnectionType.CONTAINER },
        rdp = rdpSpec().takeIf { connectionType.isRdp && !it.isEmpty },
    )

    /**
     * RDP settings as the profile stores them. The device is forgotten when audio is off, so a
     * profile does not keep pointing at a headset it no longer plays through.
     */
    private fun rdpSpec(): RdpSpec = RdpSpec(
        loadBalanceInfo = rdpLoadBalanceInfo,
        audioOutput = rdpAudioOutput,
        audioOutputDeviceId = if (rdpAudioOutput) rdpAudioDeviceId else "",
        clipboard = rdpClipboard,
        quality = rdpQuality,
    )

    companion object {
        /**
         * Empty form for creating a profile in [section]: it starts on that section's default
         * transport (with its default port), and the picker offers no other section's protocols —
         * a remote desktop is created from the desktops list, a shell from the hosts list.
         */
        fun forSection(section: HostSection): NewConnectionFormState = NewConnectionFormState().apply {
            chooseConnectionType(defaultConnectionTypeIn(section))
        }

        /** Default port/speed by type: SSH/Mosh->22 (the SSH hop's port), Telnet->23, Serial->9600 (baud), VNC->5900 (RFB display :0). */
        fun defaultPortFor(type: ConnectionType): Int = when (type) {
            // Container profiles dial the host over SSH; the port is that hop's.
            ConnectionType.SSH, ConnectionType.MOSH, ConnectionType.CONTAINER -> 22
            ConnectionType.TELNET -> 23
            ConnectionType.SERIAL -> 9600
            ConnectionType.VNC -> 5900
            ConnectionType.RDP -> 3389
            ConnectionType.LOCAL -> 0 // no port — a local shell isn't networked
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
            username = host.username.substringAfterLast('\\')
            domain = host.username.substringBeforeLast('\\', missingDelimiterValue = "")
            group = host.group ?: ""
            notes = host.notes ?: ""
            tags = orderTagsProdFirst(host.tags) // records saved before the rule keep their old order
            aiPolicy = host.aiPolicy
            jumpHostId = host.jumpHostId
            keepAliveSeconds = host.keepAliveSeconds
            host.rdp?.let { spec ->
                rdpLoadBalanceInfo = spec.loadBalanceInfo
                rdpAudioOutput = spec.audioOutput
                rdpAudioDeviceId = spec.audioOutputDeviceId
                rdpClipboard = spec.clipboard
                rdpQuality = spec.quality
            }
            host.container?.let { spec ->
                containerRuntime = spec.runtime
                containerTarget = spec.target
                containerNamespace = spec.namespace
                containerPodContainer = spec.podContainer
                containerShell = spec.shell
            }
            if (host.credentialId != null) {
                authMode = AuthMode.EXISTING
                existingCredentialId = host.credentialId
            } else if (host.interactiveAuth) {
                authMode = AuthMode.INTERACTIVE
            }
        }

        /**
         * Prefill for "Duplicate host": the same profile under the given copy [name], sharing the
         * original's keychain secret (EXISTING binding, nothing re-saved). Unlike edit mode the
         * caller saves with `toDraft(id = null)`, creating a new record.
         */
        fun duplicateOf(host: Host, name: String): NewConnectionFormState =
            fromHost(host).apply { this.name = name }
    }
}
