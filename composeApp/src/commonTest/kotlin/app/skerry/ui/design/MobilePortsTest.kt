package app.skerry.ui.design

import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Чистая логика мобильного экрана Port forwarding (глобальные туннели): проекция строки, счётчики хаба More. */
class MobilePortsTest {

    private fun tunnel(
        direction: TunnelDirection,
        destHost: String? = "10.0.0.5",
        destPort: Int? = 80,
    ): Tunnel = Tunnel("1", "t", "h1", direction, "127.0.0.1", 8080, destHost, destPort)

    private fun entry(direction: TunnelDirection, status: TunnelStatus = TunnelStatus.Active(8080)): TunnelEntry =
        TunnelEntry(tunnel(direction)).also { it.status = status }

    // ── стрелка между source и dest ──

    @Test
    fun arrow_is_all_inclusive_for_dynamic_else_forward() {
        assertEquals("arrow_forward", mobileTunnelArrow(TunnelDirection.Local))
        assertEquals("arrow_forward", mobileTunnelArrow(TunnelDirection.Remote))
        assertEquals("all_inclusive", mobileTunnelArrow(TunnelDirection.Dynamic))
    }

    // ── текст назначения: явный адрес или «dynamic proxy» для SOCKS ──

    @Test
    fun dest_text_shows_address_or_dynamic_proxy() {
        assertEquals("10.0.0.5:80", mobileTunnelDest(tunnel(TunnelDirection.Local)))
        assertEquals("localhost:3000", mobileTunnelDest(tunnel(TunnelDirection.Remote, destHost = "localhost", destPort = 3000)))
        // У -D фиксированного назначения нет — показываем «dynamic proxy», как в макете.
        assertEquals("dynamic proxy", mobileTunnelDest(tunnel(TunnelDirection.Dynamic, destHost = null, destPort = null)))
    }

    // ── счётчик активных туннелей для подзаголовка хаба More ──

    @Test
    fun active_count_counts_only_active() {
        val tunnels = listOf(
            entry(TunnelDirection.Local, status = TunnelStatus.Active(8080)),
            entry(TunnelDirection.Remote, status = TunnelStatus.Connecting),
            entry(TunnelDirection.Dynamic, status = TunnelStatus.Inactive),
            entry(TunnelDirection.Local, status = TunnelStatus.Failed("boom")),
        )
        // Активен только первый: поднимается/выключен/упал — не считаются.
        assertEquals(1, mobileActiveTunnelCount(tunnels))
        assertEquals(0, mobileActiveTunnelCount(emptyList()))
    }

    // ── подзаголовок строки Port forwarding в More ──

    @Test
    fun more_ports_subtitle_blank_without_manager_else_count() {
        // Нет менеджера (превью/офскрин) — нечего считать, строка без подписи.
        assertEquals("", mobileMorePortsSubtitle(null))
        assertEquals("0 active", mobileMorePortsSubtitle(0))
        assertEquals("1 active", mobileMorePortsSubtitle(1))
        assertEquals("3 active", mobileMorePortsSubtitle(3))
    }

    // ── подзаголовок строки Known hosts в More ──

    @Test
    fun more_known_subtitle_reports_changes_or_verified() {
        assertEquals("All verified", mobileMoreKnownSubtitle(0))
        assertEquals("1 changed", mobileMoreKnownSubtitle(1))
        assertEquals("2 changed", mobileMoreKnownSubtitle(2))
    }
}
