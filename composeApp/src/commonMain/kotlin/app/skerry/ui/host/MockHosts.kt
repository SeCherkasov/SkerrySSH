package app.skerry.ui.host

import androidx.compose.ui.graphics.Color
import app.skerry.ui.design.D

/**
 * Static demo hosts for the sidebar preview (until the live catalog is wired up).
 * Mocks only, the real host catalog lives in [HostManagerController].
 */

enum class HostStatus(val color: Color, val glow: Boolean) {
    On(D.moss, true), Off(D.faint, false), Warn(D.sunset, false),
}

data class MockHost(val name: String, val status: HostStatus, val badge: String? = null)
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
    HostGroup(
        "Homelab",
        listOf(
            MockHost("homelab-pi", HostStatus.On, "DEV"),
            MockHost("nas-truenas", HostStatus.Warn, "DEV"),
            MockHost("router-opnsense", HostStatus.Off),
            MockHost("k3s-control", HostStatus.Off),
        ),
    ),
)
