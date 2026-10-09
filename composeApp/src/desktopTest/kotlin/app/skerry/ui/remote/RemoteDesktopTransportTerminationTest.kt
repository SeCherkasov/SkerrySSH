package app.skerry.ui.remote

import app.skerry.shared.audio.RemoteAudioFormat
import app.skerry.shared.audio.RemoteAudioPlayer
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.rdp.RdpClientSettings
import app.skerry.shared.rdp.RdpConnection
import app.skerry.shared.rdp.RdpLogonInfo
import app.skerry.shared.rdp.RdpRemoteDesktop
import app.skerry.shared.rdp.RdpSessionState
import app.skerry.shared.rdp.RdpSocketSession
import app.skerry.shared.rdp.ServerCapabilities
import app.skerry.shared.rdp.X224NegotiationResponse
import app.skerry.shared.vnc.JvmInflaterFactory
import app.skerry.shared.vnc.VncAuth
import app.skerry.shared.vnc.VncDesCipher
import app.skerry.shared.vnc.VncRemoteDesktop
import app.skerry.shared.vnc.VncSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real socket-session read loops and adapters over deterministic failing streams; no live server. */
class RemoteDesktopTransportTerminationTest {
    @Test
    fun vnc_eof_and_confirmed_read_timeout_release_the_socket() = runBlocking {
        for (timedOut in listOf(false, true)) {
            val socket = EndingSocket(timedOut)
            val session = VncSocketSession(socket, VncAuth.None, JvmInflaterFactory, VncDesCipher, null)
            disconnected(VncRemoteDesktop(session)) { socket.ended.get() }
            assertTrue(socket.ended.get())
        }
    }

    @Test
    fun rdp_eof_and_confirmed_read_timeout_release_socket_and_audio() = runBlocking {
        for (timedOut in listOf(false, true)) {
            val socket = EndingSocket(timedOut)
            val audioClosed = AtomicBoolean()
            val audio = object : RemoteAudioPlayer {
                override fun play(format: RemoteAudioFormat, pcm: ByteArray) = Unit
                override fun flush() = Unit
                override fun close() { audioClosed.set(true) }
            }
            val connection = RdpConnection(socket, 1, X224NegotiationResponse(1, false, false, false), ByteArray(0))
            val caps = ServerCapabilities(
                shareId = 1, desktopWidth = 640, desktopHeight = 480, preferredBitsPerPixel = 32,
                desktopResizeSupported = false, refreshRectSupported = false, suppressOutputSupported = false,
                fastPathOutputSupported = false, noBitmapCompressionHeader = false, surfaceCommandsSupported = false,
                frameAcknowledgeSupported = false, maxRequestSize = 0, supportedCodecs = emptyList(),
            )
            val session = RdpSocketSession(
                "test-rdp", connection, RdpSessionState(1007, 1003, emptyMap(), caps),
                RdpClientSettings(desktopWidth = 640, desktopHeight = 480, clientName = "Skerry", selectedProtocol = 1),
                RdpLogonInfo(domain = "", username = "test"), audioPlayer = audio,
            )
            disconnected(RdpRemoteDesktop(session)) { socket.ended.get() && audioClosed.get() }
            assertTrue(socket.ended.get())
            assertTrue(audioClosed.get())
        }
    }

    private suspend fun disconnected(session: RemoteDesktopSession, released: () -> Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = RemoteDesktopController(scope)
        try {
            controller.connect { session }
            withTimeout(5_000) {
                while (controller.uiState !is RemoteDesktopUiState.Disconnected || !released()) delay(10)
            }
        } finally {
            controller.disconnect()
            scope.cancel()
        }
    }
}

private class EndingSocket(timedOut: Boolean) : Socket() {
    val ended = AtomicBoolean()
    private val incoming = if (timedOut) object : InputStream() {
        override fun read(): Int = throw SocketTimeoutException("Read timed out")
    } else ByteArrayInputStream(ByteArray(0))
    private val outgoing = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = incoming
    override fun getOutputStream() = outgoing
    override fun close() { ended.set(true) }
}
