package app.skerry.ui.mobile

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

    // Стрелка между source и dest

    @Test
    fun arrow_is_all_inclusive_for_dynamic_else_forward() {
        assertEquals("arrow_forward", mobileTunnelArrow(TunnelDirection.Local))
        assertEquals("arrow_forward", mobileTunnelArrow(TunnelDirection.Remote))
        assertEquals("all_inclusive", mobileTunnelArrow(TunnelDirection.Dynamic))
    }

    // Прим.: mobileTunnelDest, mobileMorePortsSubtitle и mobileMoreKnownSubtitle стали @Composable
    // (текст локализован через строковые ресурсы), поэтому их строковые юнит-тесты сняты — проекция
    // адреса/счётчика теперь резолвится в composition. Ниже — оставшаяся чистая логика (стрелка, счётчик).

    // Счётчик активных туннелей для подзаголовка хаба More

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
}
