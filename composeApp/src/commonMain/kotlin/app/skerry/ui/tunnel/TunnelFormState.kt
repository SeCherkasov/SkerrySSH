package app.skerry.ui.tunnel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.tunnel.TunnelDirection

/**
 * State for the tunnel create/edit form: editable fields as Compose state, mirroring
 * [app.skerry.ui.host.NewConnectionFormState]. Shared between the desktop editor (`TunnelEditor`
 * in [TunnelsView]) and the mobile sheet (`MobileTunnelEditorSheet`) as the single source for
 * field seeding and draft assembly.
 *
 * The edited tunnel's identity ([editingId]) is fixed at form creation ([fromEntry]) and flows
 * into [draft]; fields are an isolated edit buffer — external `entry.tunnel` mutations do not
 * propagate here, since in-progress user edits take priority (see call sites:
 * `remember(editingId) { TunnelFormState.fromEntry(existing) }`).
 */
@Stable
class TunnelFormState private constructor(private val editingId: String?) {
    var label: String by mutableStateOf("")
    var direction: TunnelDirection by mutableStateOf(TunnelDirection.Local)
    var hostId: String? by mutableStateOf(null)
    var bindHost: String by mutableStateOf("127.0.0.1")
    var bindPort: String by mutableStateOf("")
    var destHost: String by mutableStateOf("")
    var destPort: String by mutableStateOf("")

    /** SOCKS (`-D`) has no destination; the form hides the destination fields. */
    val isDynamic: Boolean get() = direction == TunnelDirection.Dynamic

    /** Valid draft for [TunnelManager.save], or `null` while input is incomplete (see [buildTunnelDraft]). */
    val draft: TunnelDraft?
        get() = buildTunnelDraft(editingId, label, hostId, direction, bindHost, bindPort, destHost, destPort)

    companion object {
        /**
         * Form pre-filled from [entry] (edit), or empty with defaults (create, `entry == null`):
         * type `-L`, loopback bind host, empty ports.
         */
        fun fromEntry(entry: TunnelEntry?): TunnelFormState {
            val seed = entry?.tunnel
            return TunnelFormState(entry?.id).apply {
                if (seed != null) {
                    label = seed.label
                    direction = seed.direction
                    hostId = seed.hostId
                    bindHost = seed.bindHost
                    bindPort = seed.bindPort.toString()
                    destHost = seed.destHost ?: ""
                    destPort = seed.destPort?.toString() ?: ""
                }
            }
        }
    }
}
