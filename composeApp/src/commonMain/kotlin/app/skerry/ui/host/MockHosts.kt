package app.skerry.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.skerry.shared.ssh.ConnectionType
import app.skerry.ui.theme.Skerry

/**
 * Static demo hosts for the sidebar preview (until the live catalog is wired up).
 * Mocks only, the real host catalog lives in [HostManagerController].
 */

enum class HostStatus(val glow: Boolean) {
    On(true), Off(false), Warn(false),
}

/** Status dot tint from the active theme. */
val HostStatus.color: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        HostStatus.On -> Skerry.colors.moss
        HostStatus.Off -> Skerry.colors.faint
        HostStatus.Warn -> Skerry.colors.sunset
    }

data class MockHost(
    val name: String,
    val status: HostStatus,
    val badge: String? = null,
    val connectionType: ConnectionType = ConnectionType.SSH,
)
data class HostGroup(val name: String, val hosts: List<MockHost>)

val HOST_GROUPS = listOf(
    HostGroup(
        "Production",
        listOf(
            MockHost("prod-web-01", HostStatus.On, "STRICT"),
            MockHost("prod-web-02", HostStatus.On, "STRICT"),
            MockHost("db-master", HostStatus.On, "STRICT"),
        ),
    ),
    HostGroup(
        "Staging",
        listOf(
            MockHost("staging-web", HostStatus.Off),
            MockHost("staging-api", HostStatus.Off),
        ),
    ),
    // Mixed protocols, so the preview exercises the row's protocol icon the way a real catalog does.
    HostGroup(
        "Homelab",
        listOf(
            MockHost("homelab-pi", HostStatus.On, "DEV"),
            MockHost("nas-truenas", HostStatus.Warn, "DEV", ConnectionType.MOSH),
            MockHost("router-opnsense", HostStatus.Off, connectionType = ConnectionType.TELNET),
            MockHost("k3s-control", HostStatus.Off, connectionType = ConnectionType.SERIAL),
            MockHost("lab-desktop", HostStatus.Off, connectionType = ConnectionType.VNC),
        ),
    ),
)
