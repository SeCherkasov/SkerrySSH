package app.skerry.shared.host

import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.rdp.RdpSpec
import app.skerry.shared.ssh.ConnectionType
import kotlinx.serialization.Serializable

/**
 * Saved connection profile in the host manager. Identity is the stable [id] (assigned at creation,
 * unchanged by edits), so renaming [label] or changing the address doesn't lose history/references.
 * [label] is the display name, [address] is the host or IP to dial, [group] is an optional folder
 * for list grouping.
 *
 * The secret itself is not stored here: it lives in the encrypted vault as
 * [app.skerry.shared.vault.Credential] (keychain), and the profile references it by [credentialId]
 * (a reusable secret — one key/password for multiple hosts). `null` means no secret is attached and
 * the password is entered at connect time.
 *
 * [tags] are optional labels for filtering the host list (#prod/#docker chips). Stored in canonical
 * form (no `#`, lowercase, deduplicated, ≤ [app.skerry.shared.tag.MAX_TAG_LENGTH]) via
 * [app.skerry.shared.tag.normalizeTag]; [group] (folder) and
 * [tags] (labels) are independent.
 *
 * [aiPolicy] is the per-host AI policy ("AI under policy" principle). Default [AiPolicy.Strict] is
 * safe: for both existing hosts (field absent) and new ones, cloud is denied until the user
 * deliberately relaxes the policy. Serialized by name (backward compatible).
 *
 * [connectionType] is the profile's transport (see [ConnectionType]). Default [ConnectionType.SSH]
 * preserves backward compatibility: old files without the field read as SSH. For
 * [ConnectionType.MOSH] every SSH field applies unchanged (address/port/credential/jump name the
 * SSH hop that launches `mosh-server`); [keepAliveSeconds] is inert (Mosh heartbeats itself). For
 * [ConnectionType.TELNET] only [address]/[port] matter (no auth/secret). For [ConnectionType.SERIAL]
 * [address] holds the device name (e.g. `/dev/ttyUSB0`, `COM3`) and [port] holds the baud rate;
 * [username]/[credentialId] are unused.
 *
 * [jumpHostId] is an optional ProxyJump reference to another saved SSH profile: the connection is
 * tunneled through that host (which may itself have a jump — a multi-hop chain). SSH-only; stored
 * by id (like [credentialId]) so renaming/re-addressing the jump host doesn't break the link. The
 * chain is resolved at connect time (`resolveJumpChain` in the UI layer): a dangling id, a non-SSH
 * jump, a secretless jump or a cycle fails the connect rather than silently going direct.
 *
 * [keepAliveSeconds] is the keep-alive cadence for this profile's sessions: every N seconds the
 * client sends `keepalive@openssh.com` so NAT/firewall tables don't expire an idle connection
 * (OpenSSH's `ServerAliveInterval`). 0 disables it. SSH-only (Telnet/Serial ignore it). Default 30
 * also covers old saved files (field absent).
 *
 * [container] is set for [ConnectionType.CONTAINER] profiles and says what to exec into (container
 * or pod, namespace, shell); the SSH fields above then describe the host running the
 * `docker`/`kubectl` CLI. `null` on every other type.
 *
 * [vncResizeToWindow] remembers a remote desktop's "Resize to window" toggle across restarts — for
 * RDP as well as VNC; the name is the one the field was saved under before RDP could resize.
 * VNC-only; toggled from the live session's graphics menu, not the edit form (which preserves it).
 *
 * [notes] is an optional free-form remark about the profile (maintenance window, who owns the box),
 * shown as a hover tooltip in the desktop sidebar and in the mobile host details. Stored normalized
 * ([normalizeNotes]): trimmed, ≤ [MAX_NOTES_LENGTH], blank collapsed to `null`. Note: a client
 * predating this field silently drops it when it re-saves the profile (unknown keys are ignored on
 * read, the record is rebuilt without them and LWW propagates that), same as an unknown
 * `connectionType`.
 *
 * [rdp] holds the RDP-only settings the fields above have no place for (a farm's routing token);
 * `null` on every other type and on an ordinary RDP host.
 */
@Serializable
data class Host(
    val id: String,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val group: String? = null,
    val credentialId: String? = null,
    /**
     * Authenticate by answering the server's own questions (2FA code, push confirmation) instead of
     * with a stored secret: no [credentialId], and no password attempt before the exchange. Older
     * clients simply don't see the flag (vault payloads ignore unknown fields), so such a profile
     * falls back to asking for a password there.
     */
    val interactiveAuth: Boolean = false,
    val tags: List<String> = emptyList(),
    val aiPolicy: AiPolicy = AiPolicy.Strict,
    val connectionType: ConnectionType = ConnectionType.SSH,
    val jumpHostId: String? = null,
    val keepAliveSeconds: Int = 30,
    val vncResizeToWindow: Boolean = false,
    /**
     * The remote desktop's quality choice, remembered across sessions (V-03): picked from the live
     * session's graphics menu, not the edit form, like [vncResizeToWindow]. Auto is the wire
     * default and stores nothing worth acting on.
     */
    val vncQuality: app.skerry.shared.graphics.RemoteDesktopQuality =
        app.skerry.shared.graphics.RemoteDesktopQuality.Auto,
    val notes: String? = null,
    val container: ContainerSpec? = null,
    val rdp: RdpSpec? = null,
)
