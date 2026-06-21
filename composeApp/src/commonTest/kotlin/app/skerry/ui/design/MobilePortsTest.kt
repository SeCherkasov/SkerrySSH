package app.skerry.ui.design

import app.skerry.ui.forward.ForwardDirection
import app.skerry.ui.forward.ForwardEntry
import app.skerry.ui.forward.ForwardStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Чистая логика мобильного экрана Port forwarding (слайс 5): режим экрана, проекция строки туннеля, счётчики хаба More. */
class MobilePortsTest {

    private fun entry(
        direction: ForwardDirection,
        destHost: String = "10.0.0.5",
        destPort: Int = 80,
        status: ForwardStatus = ForwardStatus.Active(8080),
        paused: Boolean = false,
    ): ForwardEntry = ForwardEntry(
        id = 1,
        direction = direction,
        bindHost = "127.0.0.1",
        requestedPort = 8080,
        destHost = destHost,
        destPort = destPort,
    ).also {
        it.status = status
        it.paused = paused
    }

    // ── режим экрана по состоянию сессий (зеркалит mobileFilesMode) ──

    @Test
    fun ports_mode_picks_preview_without_session_manager() {
        assertEquals(MobilePortsMode.Preview, mobilePortsMode(hasSessions = false, connected = false))
        assertEquals(MobilePortsMode.Preview, mobilePortsMode(hasSessions = false, connected = true))
    }

    @Test
    fun ports_mode_is_live_only_when_connected() {
        assertEquals(MobilePortsMode.Live, mobilePortsMode(hasSessions = true, connected = true))
        assertEquals(MobilePortsMode.NoSession, mobilePortsMode(hasSessions = true, connected = false))
    }

    // ── стрелка между source и dest ──

    @Test
    fun arrow_is_all_inclusive_for_dynamic_else_forward() {
        assertEquals("arrow_forward", mobileTunnelArrow(ForwardDirection.Local))
        assertEquals("arrow_forward", mobileTunnelArrow(ForwardDirection.Remote))
        assertEquals("all_inclusive", mobileTunnelArrow(ForwardDirection.Dynamic))
    }

    // ── текст назначения: явный адрес или «dynamic proxy» для SOCKS ──

    @Test
    fun dest_text_shows_address_or_dynamic_proxy() {
        assertEquals("10.0.0.5:80", mobileTunnelDest(entry(ForwardDirection.Local)))
        assertEquals("localhost:3000", mobileTunnelDest(entry(ForwardDirection.Remote, destHost = "localhost", destPort = 3000)))
        // У -D фиксированного назначения нет — показываем «dynamic proxy», как в макете.
        assertEquals("dynamic proxy", mobileTunnelDest(entry(ForwardDirection.Dynamic)))
    }

    // ── счётчик активных туннелей для подзаголовка хаба More ──

    @Test
    fun active_count_excludes_paused_and_non_active() {
        val forwards = listOf(
            entry(ForwardDirection.Local, status = ForwardStatus.Active(8080)),
            entry(ForwardDirection.Remote, status = ForwardStatus.Active(9000), paused = true),
            entry(ForwardDirection.Dynamic, status = ForwardStatus.Starting),
            entry(ForwardDirection.Local, status = ForwardStatus.Failed("boom")),
        )
        // Активен только первый: на паузе/поднимается/упал — не считаются.
        assertEquals(1, mobileActiveTunnelCount(forwards))
        assertEquals(0, mobileActiveTunnelCount(emptyList()))
    }

    // ── подзаголовок строки Port forwarding в More ──

    @Test
    fun more_ports_subtitle_blank_without_session_else_count() {
        // Нет подключённой сессии — нечего считать, строка без подписи.
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
