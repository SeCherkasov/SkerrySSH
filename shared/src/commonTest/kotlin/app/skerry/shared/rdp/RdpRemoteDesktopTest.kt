package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteDesktopUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The adapter between the RDP session and what the UI sees ([RdpRemoteDesktop]). */
class RdpRemoteDesktopTest {

    @Test
    fun a_dead_audio_device_reaches_the_ui_as_a_playback_failure() = runTest {
        // The one hop between the RDP session and what the screen state sees. Drop the arm and the
        // player, the channel and the screen state all still pass their own tests while the UI never
        // hears about a device that died.
        val stream = MutableSharedFlow<RdpUpdate>(extraBufferCapacity = 4)
        val seen = mutableListOf<RemoteDesktopUpdate>()
        val desktop = RdpRemoteDesktop(FakeRdpSession(updates = stream))
        val collector = launch { desktop.updates.collect { update -> seen.add(update) } }
        runCurrent()

        stream.emit(RdpUpdate.AudioPlayback(failing = true))
        stream.emit(RdpUpdate.AudioPlayback(failing = false))
        runCurrent()
        collector.cancel()

        val expected: List<RemoteDesktopUpdate> = listOf(
            RemoteDesktopUpdate.AudioPlaybackFailing(failing = true),
            RemoteDesktopUpdate.AudioPlaybackFailing(failing = false),
        )
        assertEquals(expected, seen)
    }

    @Test
    fun a_hidden_window_is_reported_and_nothing_is_asked_for() = runTest {
        val session = FakeRdpSession()

        RdpRemoteDesktop(session).setOutputVisible(false)

        assertEquals(listOf(false), session.visibility)
        // Asking for pixels from a server that was just told to stop drawing them is the one thing
        // this must not do.
        assertEquals(listOf("visible(false)"), session.calls)
    }

    @Test
    fun coming_back_into_view_asks_for_the_whole_screen() = runTest {
        // Nothing was painted while output was suppressed, so the server's screen and the last frame
        // drawn here have drifted apart; the next incremental update would leave stale pixels behind.
        val session = FakeRdpSession(desktopWidth = 800, desktopHeight = 600)

        RdpRemoteDesktop(session).setOutputVisible(true)

        assertEquals(listOf("visible(true)", "refresh"), session.calls, "the repaint follows the report")
        assertEquals(listOf(RdpRect(0, 0, 800, 600)), session.refreshed.single())
    }

    @Test
    fun a_session_that_cannot_be_repainted_is_never_suppressed() = runTest {
        // Suppress Output and Refresh Rect are separate capability bits, and a server can grant one
        // without the other. Stopping the drawing on a server that will not take a repaint request
        // leaves the last frame on screen for the rest of the session; not suppressing at all costs
        // bandwidth and keeps the picture. The mirror case matters too: without the suppression the
        // repaint has nothing to repair, and every window restore would retransmit the whole desktop.
        val session = FakeRdpSession(outputSuppressionSupported = false)
        val desktop = RdpRemoteDesktop(session)

        desktop.setOutputVisible(false)
        desktop.setOutputVisible(true)

        assertEquals(emptyList(), session.calls)
    }
}
