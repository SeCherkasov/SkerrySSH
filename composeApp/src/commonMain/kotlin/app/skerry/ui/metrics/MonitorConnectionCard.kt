package app.skerry.ui.metrics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.connection.jumpRouteLabel
import app.skerry.ui.connection.shortCipher
import app.skerry.ui.design.Card
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.mon_card_connection
import app.skerry.ui.generated.resources.mon_card_system
import app.skerry.ui.generated.resources.term_auth_ask
import app.skerry.ui.generated.resources.term_auth_certificate
import app.skerry.ui.generated.resources.term_auth_identity
import app.skerry.ui.generated.resources.term_auth_key_file
import app.skerry.ui.generated.resources.term_auth_password
import app.skerry.ui.generated.resources.term_info_address
import app.skerry.ui.generated.resources.term_info_auth
import app.skerry.ui.generated.resources.term_info_cipher
import app.skerry.ui.generated.resources.term_info_host
import app.skerry.ui.generated.resources.term_info_jump
import app.skerry.ui.generated.resources.term_info_uptime
import app.skerry.ui.generated.resources.term_info_user
import app.skerry.ui.generated.resources.term_system_load
import app.skerry.ui.generated.resources.term_system_vcpu
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

// The two cards that describe the connection the metrics came over, rather than the metrics
// themselves: what this session is, and what the machine on the other end runs.

/**
 * How this session is connected: host profile, address, user, the ProxyJump route when there is
 * one, the kind of secret it authenticated with and the negotiated cipher. `null` fields read as
 * "—", and a value the transport hasn't reported yet as "…".
 */
data class ConnectionFacts(
    val host: String,
    val address: String,
    val user: String,
    val jump: String?,
    val credential: CredentialSecret?,
    val hasCredential: Boolean,
    val cipher: String?,
)

/** [ConnectionFacts] of the pane in focus, or the mock ones when there is no session backend. */
@Composable
internal fun connectionFacts(hostId: String?, paneTitle: String?, cipher: String?): ConnectionFacts {
    val hosts = LocalHosts.current
    val credentials = LocalCredentials.current
    val host = hostId?.let { hosts?.find(it) }
    return ConnectionFacts(
        host = host?.label ?: paneTitle ?: NO_DATA,
        address = host?.let { "${it.address}:${it.port}" } ?: NO_DATA,
        user = host?.username ?: NO_DATA,
        jump = host?.let { h -> jumpRouteLabel(h) { id -> hosts?.find(id) } },
        credential = host?.credentialId?.let { id -> credentials?.find(id) }?.secret,
        hasCredential = host != null,
        cipher = cipher,
    )
}

/** Static facts for the preview path (no session manager), matching the mockup's host. */
internal val PREVIEW_CONNECTION_FACTS = ConnectionFacts(
    host = "prod-web-01",
    address = "192.168.1.45:22",
    user = "root",
    jump = null,
    credential = null,
    hasCredential = false,
    cipher = "aes256-gcm",
)

@Composable
internal fun MonitorConnectionCard(
    facts: ConnectionFacts,
    uptimeSeconds: Long?,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Card(modifier, stringResource(Res.string.mon_card_connection)) {
        Spacer(Modifier.height(4.dp))
        KeyValue(stringResource(Res.string.term_info_host), facts.host, Skerry.colors.textBright, mono)
        KeyValue(stringResource(Res.string.term_info_address), facts.address, Skerry.colors.textBright, mono)
        KeyValue(stringResource(Res.string.term_info_user), facts.user, Skerry.colors.textBright, mono)
        facts.jump?.let { KeyValue(stringResource(Res.string.term_info_jump), it, Skerry.colors.textBright, mono) }
        KeyValue(stringResource(Res.string.term_info_auth), authText(facts), Skerry.colors.textBright, mono)
        KeyValue(stringResource(Res.string.term_info_cipher), facts.cipher ?: PENDING, Skerry.colors.textBright, mono)
        KeyValue(
            stringResource(Res.string.term_info_uptime),
            uptimeSeconds?.let { formatUptime(it) } ?: PENDING,
            Skerry.colors.textBright,
            mono,
        )
    }
}

/**
 * What the host runs: OS, kernel, cores and load average. Every line is optional — a poll that
 * hasn't landed yet (or a host whose /etc/os-release is missing) leaves the block saying "…"
 * instead of inventing a distribution.
 */
@Composable
internal fun MonitorSystemCard(metrics: HostMetrics?, mono: FontFamily, modifier: Modifier = Modifier) {
    val cpu = metrics?.cpuCount?.let { stringResource(Res.string.term_system_vcpu, it) }
    val load = metrics?.loadAverage?.let { stringResource(Res.string.term_system_load, it) }
    val cpuLoad = listOfNotNull(cpu, load).joinToString(" · ")
    val lines = listOfNotNull(metrics?.osName, metrics?.kernel, cpuLoad.takeIf { it.isNotEmpty() })
    Card(modifier, stringResource(Res.string.mon_card_system)) {
        Spacer(Modifier.height(8.dp))
        Txt(
            if (lines.isEmpty()) PENDING else lines.joinToString("\n"),
            color = Skerry.colors.dim,
            size = 11.5.sp,
            font = mono,
            lineHeight = 19.sp,
        )
    }
}

/** The kind of secret the session authenticated with — the binding, not the secret itself. */
@Composable
private fun authText(facts: ConnectionFacts): String = when {
    !facts.hasCredential -> NO_DATA
    else -> when (facts.credential) {
        is CredentialSecret.Password -> stringResource(Res.string.term_auth_password)
        is CredentialSecret.PrivateKey -> stringResource(Res.string.term_auth_identity)
        is CredentialSecret.Certificate -> stringResource(Res.string.term_auth_certificate)
        is CredentialSecret.KeyFile -> stringResource(Res.string.term_auth_key_file)
        null -> stringResource(Res.string.term_auth_ask)
    }
}

/** A fact this session simply doesn't have (an ad-hoc connection with no host profile). */
private const val NO_DATA = "—"

/** A value that is on its way: the first poll, or a cipher the transport hasn't reported yet. */
private const val PENDING = "…"
