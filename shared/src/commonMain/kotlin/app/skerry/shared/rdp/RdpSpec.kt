package app.skerry.shared.rdp

import kotlinx.serialization.Serializable

/**
 * RDP-only settings of a saved profile, the ones the SSH-shaped fields have no place for. `null` on
 * the profile means "nothing special" — the common case — so ordinary RDP hosts stay exactly as they
 * were and older clients ignore the field like any other unknown key.
 *
 * [loadBalanceInfo] is the routing token of a Remote Desktop farm (`loadbalanceinfo` in an `.rdp`
 * file, typically `tsv://MS Terminal Services Plugin.1.<collection>`). The connection broker reads
 * it from the X.224 Connection Request to pick the collection and reach the user's existing session;
 * without it the farm hands out an arbitrary host. It is not a secret — it travels unencrypted in
 * front of the TLS handshake, which is exactly why it is stored with the profile and not the vault.
 *
 * [audioOutput] plays the session's sound on this machine (MS-RDPEA). Off by default: the channel
 * carries every notification beep of the remote desktop, and a session opened to run one command has
 * no use for it. [audioOutputDeviceId] names the device to play on, as
 * [app.skerry.shared.audio.AudioOutputs] lists them; empty means the system default, and so does a
 * device that has since been unplugged.
 */
@Serializable
data class RdpSpec(
    val loadBalanceInfo: String = "",
    val audioOutput: Boolean = false,
    val audioOutputDeviceId: String = "",
    val clipboard: Boolean = true,
) {
    /** Whether anything here is worth storing; an all-default spec is dropped to `null`. */
    val isEmpty: Boolean
        get() = loadBalanceInfo.isBlank() && !audioOutput && audioOutputDeviceId.isBlank() && clipboard
}
