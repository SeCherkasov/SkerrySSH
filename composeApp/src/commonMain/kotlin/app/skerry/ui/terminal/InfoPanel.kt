package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.jumpRouteLabel
import app.skerry.ui.connection.shortCipher
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_auth_ask
import app.skerry.ui.generated.resources.term_auth_certificate
import app.skerry.ui.generated.resources.term_auth_identity
import app.skerry.ui.generated.resources.term_auth_password
import app.skerry.ui.generated.resources.term_info_address
import app.skerry.ui.generated.resources.term_info_auth
import app.skerry.ui.generated.resources.term_info_cipher
import app.skerry.ui.generated.resources.term_info_host
import app.skerry.ui.generated.resources.term_info_jump
import app.skerry.ui.generated.resources.term_info_system
import app.skerry.ui.generated.resources.term_info_uptime
import app.skerry.ui.generated.resources.term_info_user
import app.skerry.ui.generated.resources.term_system_load
import app.skerry.ui.generated.resources.term_system_vcpu
import app.skerry.ui.metrics.HostMetrics
import app.skerry.ui.metrics.HostMonitorSections
import app.skerry.ui.metrics.MetricsAvailability
import app.skerry.ui.metrics.PREVIEW_HOST_METRICS
import app.skerry.ui.metrics.PREVIEW_METRICS_HISTORY
import app.skerry.ui.metrics.PREVIEW_RX_RATE
import app.skerry.ui.metrics.PREVIEW_TX_RATE
import app.skerry.ui.metrics.formatUptime
import app.skerry.ui.session.sessionDotColor
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// Terminal view info panel: connection parameters, live metrics, and the active session's SYSTEM block.

@Composable
internal fun InfoPanel() {
    val mono = LocalFonts.current.mono
    // Live context of the active session (if any): host profile from the catalog + connection state.
    val sessions = LocalSessions.current
    val hosts = LocalHosts.current
    val credentials = LocalCredentials.current
    val active = sessions?.active
    val host = active?.hostId?.let { id -> hosts?.find(id) }
    val live = sessions != null
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    // Live-metrics controller of the active session (when connected). remember is unconditional —
    // its keys (session id + connected flag) recreate it on session/connection change, avoiding a
    // conditional remember call. openMetrics is idempotent (cached in ConnectionController).
    val monitor = remember(active, connected) {
        if (connected) active.controller.openMetrics() else null
    }
    val liveMetrics = monitor?.metrics
    Column(Modifier.width(268.dp).fillMaxHeight().background(Skerry.colors.surface2).verticalScroll(rememberScrollState())) {
        // No heading of its own: the session action icons are pinned over this strip, and a
        // second title under them just repeated what the connection rows below already say.
        Spacer(Modifier.height(PANE_HEADER_HEIGHT))
        HLine()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Host/Address/User come from the active session's live profile; cipher from the transport,
            // uptime from live metrics ("…" until the first poll); mock mode uses static values.
            InfoRow(stringResource(Res.string.term_info_host), if (live) (host?.label ?: active?.title ?: "—") else "prod-web-01", mono)
            InfoRow(stringResource(Res.string.term_info_address), if (live) (host?.let { "${it.address}:${it.port}" } ?: "—") else "192.168.1.45:22", mono)
            InfoRow(stringResource(Res.string.term_info_user), if (live) (host?.username ?: "—") else "root", mono)
            // ProxyJump route (entry hop first) — only when the profile actually routes through
            // one, so a direct connection's panel omits the row.
            val jumpRoute = if (live) host?.let { h -> jumpRouteLabel(h) { id -> hosts?.find(id) } } else null
            if (jumpRoute != null) InfoRow(stringResource(Res.string.term_info_jump), jumpRoute, mono)
            // Auth = the actual kind of the bound keychain secret (password/key/certificate), or
            // "ask on connect" for a profile with no binding.
            // No active session ⇒ "—" like the other rows, not "ask on connect".
            val authValue = when {
                !live -> "id_ed25519"
                host == null -> "—"
                else -> when (host.credentialId?.let { id -> credentials?.find(id) }?.secret) {
                    is CredentialSecret.Password -> stringResource(Res.string.term_auth_password)
                    is CredentialSecret.PrivateKey -> stringResource(Res.string.term_auth_identity)
                    is CredentialSecret.Certificate -> stringResource(Res.string.term_auth_certificate)
                    null -> stringResource(Res.string.term_auth_ask)
                }
            }
            InfoRow(stringResource(Res.string.term_info_auth), authValue, mono)
            InfoRow(stringResource(Res.string.term_info_cipher), if (live) (shortCipher(active?.controller?.cipher) ?: "…") else "aes256-gcm", mono)
            InfoRow(stringResource(Res.string.term_info_uptime), if (live) (liveMetrics?.uptimeSeconds?.let { formatUptime(it) } ?: "…") else "04:12:45", mono)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            // Live monitor of the session's host (resources, network, filesystems, processes).
            // Without a session (preview/offscreen) the same blocks are drawn from a fixed snapshot.
            HostMonitorSections(
                metrics = if (live) liveMetrics else PREVIEW_HOST_METRICS,
                history = if (live) monitor?.history.orEmpty() else PREVIEW_METRICS_HISTORY,
                netRxRate = if (live) monitor?.netRxRate ?: 0 else PREVIEW_RX_RATE,
                netTxRate = if (live) monitor?.netTxRate ?: 0 else PREVIEW_TX_RATE,
                availability = if (live) monitor?.availability ?: MetricsAvailability.Probing else MetricsAvailability.Live,
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Txt(stringResource(Res.string.term_info_system), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 8.dp))
            // The live block is built from host facts (OS / kernel / CPU+load); "…" until the first poll.
            // Mock mode (preview/offscreen) uses static text.
            val systemText = if (live) liveSystemSummary(liveMetrics) else MOCK_SYSTEM
            Txt(systemText, color = Skerry.colors.dim, size = 10.5.sp, font = mono, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(label, color = Skerry.colors.faint, size = 11.5.sp)
        Txt(value, color = Skerry.colors.textBright, size = 11.5.sp, font = mono)
    }
}

/** Static SYSTEM block for mock/offscreen mode (no live session). */
private const val MOCK_SYSTEM = "Ubuntu 22.04.4 LTS\nLinux 5.15.0-105 x86_64\n4 vCPU · load 0.42 0.51 0.48"

/**
 * Builds the info panel's SYSTEM block from live host facts: OS, kernel, "N vCPU · load …" line.
 * Skips fields not yet available (poll pending / non-Linux). If all are empty, returns "…".
 */
@Composable
private fun liveSystemSummary(m: HostMetrics?): String {
    // Parts are resolved through resources here (not concatenated from literals) so the block follows
    // the UI language.
    val cpu = m?.cpuCount?.let { stringResource(Res.string.term_system_vcpu, it) }
    val load = m?.loadAverage?.let { stringResource(Res.string.term_system_load, it) }
    val cpuLoad = listOfNotNull(cpu, load).joinToString(" · ")
    val lines = listOfNotNull(
        m?.osName,
        m?.kernel,
        cpuLoad.takeIf { it.isNotEmpty() },
    )
    return if (lines.isEmpty()) "…" else lines.joinToString("\n")
}
