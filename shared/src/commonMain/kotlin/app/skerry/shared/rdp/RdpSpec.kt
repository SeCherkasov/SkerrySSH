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
 *
 * [quality] is how much of the remote desktop the server is asked to draw. It lives on the profile
 * rather than in the live session's panel because RDP settles the picture in the Client Info PDU and
 * keeps it for the whole session (see [RdpImageQuality]).
 *
 * [graphicsPipeline], [remoteFx] and [h264] pick the graphics path (F-28) — every default is
 * today's behaviour, so an existing profile connects exactly as it did. They are the escape hatch
 * for a host that misbehaves on one path: turn the pipeline off and the session falls back to
 * surface commands; turn RemoteFX or H.264 off and the remaining codecs carry the picture.
 */
@Serializable
data class RdpSpec(
    val loadBalanceInfo: String = "",
    val audioOutput: Boolean = false,
    val audioOutputDeviceId: String = "",
    val clipboard: Boolean = true,
    val quality: RdpImageQuality = RdpImageQuality.DEFAULT,
    val graphicsPipeline: Boolean = true,
    val remoteFx: Boolean = true,
    val h264: RdpH264Mode = RdpH264Mode.Auto,
) {
    /** Whether anything here is worth storing; an all-default spec is dropped to `null`. */
    val isEmpty: Boolean
        get() = loadBalanceInfo.isBlank() && !audioOutput && audioOutputDeviceId.isBlank() &&
            clipboard && quality == RdpImageQuality.DEFAULT &&
            graphicsPipeline && remoteFx && h264 == RdpH264Mode.Auto
}

/**
 * Which H.264 profile the client offers the server (MS-RDPEGFX capability versions). Serialized by
 * name — the names are the storage format, so an entry must never be *renamed*, and a value added
 * in a future release fails to decode on older clients (unknown enum values are not skippable the
 * way unknown keys are). [Auto] is today's behaviour: the full ladder whenever a decoder exists.
 * The explicit values narrow what is advertised; none of them can conjure H.264 on a machine
 * without a decoder.
 */
@Serializable
enum class RdpH264Mode { Auto, Off, Avc420, Avc444 }
