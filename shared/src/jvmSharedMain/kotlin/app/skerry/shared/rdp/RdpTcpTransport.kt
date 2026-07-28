package app.skerry.shared.rdp

import app.skerry.shared.audio.RemoteAudioPlayer
import app.skerry.shared.audio.RemoteAudioPlayerFactory
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.rdp.egfx.ClearCodec
import app.skerry.shared.rdp.egfx.DynamicChannels
import app.skerry.shared.rdp.egfx.GraphicsChannel
import app.skerry.shared.rdp.egfx.GraphicsCodecs
import app.skerry.shared.rdp.egfx.Progressive
import app.skerry.shared.rdp.nla.CredSspClient
import app.skerry.shared.rdp.nla.JvmNtlmCrypto
import app.skerry.shared.rdp.nla.NtlmCredentials
import app.skerry.shared.rdp.rfx.RemoteFx
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * TCP implementation of [RdpTransport]: negotiates and upgrades the socket, runs NLA when the server
 * selected it, drives the connection sequence and hands back a live session.
 *
 * No expect/actual — sockets and `javax.net.ssl` behave identically on desktop and Android, the same
 * reasoning as `VncTcpTransport`.
 */
class RdpTcpTransport(
    private val certificateVerifier: RdpCertificateVerifier,
    private val connectTimeoutMillis: Int = 15_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Opens the local playback device for a session that asked for audio (see
     * [RdpTarget.audioOutput]). `null` — this platform plays no sound, and the channel is left out
     * of the connection request rather than opened and dropped on the floor.
     */
    private val audioPlayers: RemoteAudioPlayerFactory? = null,
) : RdpTransport {

    /**
     * Connect, following the redirections a Remote Desktop farm answers with (MS-RDPBCGR 2.2.13):
     * the broker takes the first connection and names the host that actually runs the session, and
     * without following that the user lands nowhere. The hop count is bounded — a pair of brokers
     * pointing at each other would otherwise dial forever.
     */
    override suspend fun connect(target: RdpTarget, credentials: RdpCredentials): RdpSession =
        withContext(Dispatchers.IO) {
            var currentTarget = target
            var currentCredentials = credentials
            repeat(MAX_REDIRECTS + 1) {
                try {
                    return@withContext connectOnce(currentTarget, currentCredentials)
                } catch (e: RdpRedirectException) {
                    currentTarget = e.redirection.applyTo(currentTarget)
                    currentCredentials = e.redirection.applyTo(currentCredentials)
                }
            }
            throw RdpProtocolException("the server redirected the connection more than $MAX_REDIRECTS times")
        }

    private suspend fun connectOnce(target: RdpTarget, credentials: RdpCredentials): RdpSession =
        withContext(Dispatchers.IO) {
            val connector = RdpTcpConnector(certificateVerifier, connectTimeoutMillis)
            val connection = connector.connect(
                host = target.host,
                port = target.port,
                requestedProtocols = RdpSecurityProtocol.SSL or RdpSecurityProtocol.HYBRID,
                cookie = credentials.username.takeIf { it.isNotBlank() },
                loadBalanceInfo = target.loadBalanceInfo.takeIf { it.isNotBlank() },
            )
            // The device is resolved before the channel is asked for: a machine with no usable
            // output plays nothing either way, and asking for a channel we would then ignore costs
            // the session bandwidth for every sound the server sends.
            val audio: RemoteAudioPlayer? =
                if (target.audioOutput) audioPlayers?.open(target.audioDeviceId) else null
            rdpAudioTrace(
                "connect: audioOutput=${target.audioOutput} device='${target.audioDeviceId}' " +
                    "factory=${audioPlayers != null} player=${audio != null}",
            )
            // The rest of the sequence is blocking reads on this socket, and a blocking read does
            // not answer to cancellation. Closing the socket underneath it does, and until the
            // session exists there is nothing else that would.
            val closeOnCancel = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause != null) {
                    connection.close()
                    audio?.close()
                }
            }
            try {
                val networkLevelAuth = connection.selectedProtocol == RdpSecurityProtocol.HYBRID
                if (networkLevelAuth) authenticate(connection, target, credentials)

                val settings = RdpClientSettings(
                    desktopWidth = target.desktopWidth,
                    desktopHeight = target.desktopHeight,
                    clientName = target.clientName,
                    selectedProtocol = connection.selectedProtocol,
                    keyboardLayout = target.keyboardLayout,
                    redirectedSessionId = target.redirectedSessionId,
                    wantsGraphicsPipeline = target.graphicsPipeline,
                    channels = buildList {
                        if (target.clipboard) add(RdpClientSettings.CHANNEL_CLIPBOARD)
                        if (audio != null) add(RdpClientSettings.CHANNEL_AUDIO)
                        // The dynamic channel carries the graphics pipeline, the display control
                        // channel and the audio a modern host prefers to send that way; without it
                        // the server has no way to open any of them, which is the point of leaving
                        // it out.
                        if (target.graphicsPipeline || target.dynamicResize || audio != null) {
                            add(RdpClientSettings.CHANNEL_DYNAMIC)
                        }
                    },
                )
                // With NLA the user is already authenticated; sending the password again in the
                // Client Info PDU would put it on a second path for no gain.
                val logon = RdpLogonInfo(
                    domain = credentials.domain,
                    username = credentials.username,
                    password = if (networkLevelAuth) "" else credentials.password,
                )
                rdpAudioTrace(
                    "connect: requesting static channels ${settings.channels}, " +
                        "client info says audio playback is wanted=" +
                        settings.channels.contains(RdpClientSettings.CHANNEL_AUDIO),
                )
                val state = RdpConnectionSequence(
                    connection.source, connection.sink, settings, logon, JvmLicenseCrypto(),
                ).run()
                // Established. The session's own reads wait for as long as the desktop is still,
                // so the timeout the negotiation ran under goes now.
                connection.clearReadTimeout()
                // `target` is where this attempt landed, which after a redirection is not the host
                // the user typed.
                RdpSocketSession(target.host, connection, state, settings, logon, audio)
            } catch (e: Throwable) {
                connection.close()
                audio?.close()
                throw e
            } finally {
                closeOnCancel.dispose()
            }
        }

    private suspend fun authenticate(connection: RdpConnection, target: RdpTarget, credentials: RdpCredentials) {
        val client = CredSspClient(
            credentials = NtlmCredentials(
                domain = credentials.domain,
                user = credentials.username,
                password = credentials.password,
                workstation = target.clientName,
            ),
            crypto = JvmNtlmCrypto(),
            // The SPN binds the ticket to this host, so a relay to a different machine is refused
            // by servers that enforce target binding.
            spn = "TERMSRV/${target.host}",
            nowFileTime = { toFileTime(clock()) },
        )
        client.authenticate(connection.source, connection.sink, connection.serverPublicKey)
    }

    /** Windows FILETIME: 100-nanosecond ticks since 1601, which NTLM timestamps are measured in. */
    private fun toFileTime(epochMillis: Long): Long = (epochMillis + EPOCH_DIFFERENCE_MILLIS) * 10_000

    private companion object {
        /** Milliseconds between 1601-01-01 and 1970-01-01. */
        const val EPOCH_DIFFERENCE_MILLIS = 11_644_473_600_000L

        /**
         * Redirections to follow before giving up. One hop is the normal case (broker → session
         * host); more than a couple means the farm is pointing the client in a circle.
         */
        const val MAX_REDIRECTS = 4
    }
}

/**
 * Diagnostics for the audio path, from what the connection asked for to the blocks that reach the
 * device: `SKERRY_RDP_AUDIO_TRACE=1` writes it to stderr.
 *
 * A session with no sound has to be diagnosed on the machine where it happens, against the server
 * that does it, and the interesting part is what the *server* does — whether it opens an audio
 * channel at all. Note that `./gradlew run` starts the app in a process that inherits the Gradle
 * daemon's environment rather than the shell's, so the run task forwards the variable as the system
 * property this also reads (see composeApp/build.gradle.kts); a packaged build reads the variable
 * itself.
 */
private val audioTracing = System.getenv("SKERRY_RDP_AUDIO_TRACE") == "1" ||
    System.getProperty("skerry.rdp.audioTrace") == "1"

internal val rdpAudioTrace: (String) -> Unit = { line -> if (audioTracing) System.err.println("rdp audio: $line") }

/**
 * A live session over a connected [connection].
 *
 * Cancellation follows the VNC session's pattern: the blocking socket read does not respond to
 * coroutine cancellation, so the collector's completion closes the socket, which drops the parked
 * read as an IOException and lets the producer finish.
 */
class RdpSocketSession(
    override val connectedHost: String,
    private val connection: RdpConnection,
    state: RdpSessionState,
    settings: RdpClientSettings,
    logon: RdpLogonInfo,
    /** Local playback device for the session's sound; `null` when the profile asked for none. */
    audioPlayer: RemoteAudioPlayer? = null,
) : RdpSession {

    override val framebuffer = RemoteFramebuffer(
        state.capabilities.desktopWidth.coerceAtLeast(1),
        state.capabilities.desktopHeight.coerceAtLeast(1),
    )

    private val codec = RdpSessionCodec(
        source = connection.source,
        sink = connection.sink,
        framebuffer = framebuffer,
        state = state,
        settings = settings,
        logon = logon,
        // The codec is plugged in unconditionally; whether the server uses it was settled in the
        // capability exchange, and a decoder that is never called costs nothing.
        remoteFx = RemoteFx(),
    )

    private val clipboard = ClipboardChannel(
        send = { data -> codec.sendChannelData(RdpClientSettings.CHANNEL_CLIPBOARD, data) },
    )

    private val dynamicChannels = DynamicChannels(
        send = { data -> codec.sendChannelData(RdpClientSettings.CHANNEL_DYNAMIC, data) },
        trace = rdpAudioTrace,
    )

    /**
     * The session's sound (MS-RDPEA), present only when a device was opened for it. Unlike the
     * channels above it is not registered unconditionally: the server opens the audio channels
     * because the connection request named them, so a channel with nowhere to play would receive
     * audio and drop it.
     */
    private val audio = audioPlayer?.let { player -> AudioChannel(player, trace = rdpAudioTrace) }

    /**
     * Playback runs on a thread of its own: writing into the device blocks until it has room, and on
     * the read loop that would stall the picture every time the sound got ahead.
     */
    private val audioScope = audio?.let { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * The graphics pipeline. It is registered unconditionally: whether the server opens the channel
     * at all was settled when the connection asked for it, and a channel that is never opened costs
     * one entry in a map.
     */
    private val graphics = GraphicsChannel(
        framebuffer = framebuffer,
        codecs = GraphicsCodecs(remoteFx = RemoteFx(), progressive = Progressive(), clear = ClearCodec()),
        send = { data -> dynamicChannels.sendTo(GraphicsChannel.NAME, data) },
    )

    /**
     * The display control channel, registered on the same terms as the graphics pipeline: the server
     * opens it if it can resize the session, and stays silent if it cannot.
     */
    private val display = DisplayControlChannel(
        send = { data -> dynamicChannels.sendTo(DisplayControlChannel.NAME, data) },
    )

    private val collected = AtomicBoolean(false)
    private val closedFlag = AtomicBoolean(false)

    override val desktopWidth: Int get() = codec.desktopWidth
    override val desktopHeight: Int get() = codec.desktopHeight

    init {
        dynamicChannels.register(GraphicsChannel.NAME, graphics)
        dynamicChannels.register(DisplayControlChannel.NAME, display)
        val clipboardChannelId = state.channels[RdpClientSettings.CHANNEL_CLIPBOARD]
        val dynamicChannelId = state.channels[RdpClientSettings.CHANNEL_DYNAMIC]
        val audioChannelId = state.channels[RdpClientSettings.CHANNEL_AUDIO]
        codec.onChannelData = { channelId, data ->
            when (channelId) {
                clipboardChannelId -> clipboard.onData(data)
                dynamicChannelId -> dynamicChannels.onData(data)
                audioChannelId -> audio?.onData(data) { reply ->
                    codec.sendChannelData(RdpClientSettings.CHANNEL_AUDIO, reply)
                }
                // Nothing else should arrive on a static channel this session asked for; if it
                // does, the audio was routed past its handler rather than never sent.
                else -> rdpAudioTrace("channel data on $channelId (${data.size} bytes), no handler")
            }
        }
        rdpAudioTrace("session: channels granted by the server ${state.channels}, audio=${audio != null}")
        audio?.let { channel ->
            // Both transports of MS-RDPEA, because the server picks: a Windows host with drdynvc
            // open sends the sound over the dynamic channel and leaves the static one silent.
            dynamicChannels.register(
                AudioChannel.DVC_NAME,
                channel.dynamicHandler { data -> dynamicChannels.sendTo(AudioChannel.DVC_NAME, data) },
            )
            audioScope?.launch { channel.play() }
        }
    }

    override val updates: Flow<RdpUpdate> = flow {
        check(collected.compareAndSet(false, true)) { "RdpSession.updates supports only one collector" }
        while (true) {
            val batch = try {
                codec.readMessage()
            } catch (e: CancellationException) {
                throw e // never swallow cooperative cancellation
            } catch (e: RdpAuthException) {
                emit(RdpUpdate.Closed(cleanExit = true, reason = e.message.orEmpty()))
                break
            } catch (e: Exception) {
                // Any decode or socket failure ends the session cleanly rather than escaping the
                // flow and taking the process down on Android. The message travels as diagnostics —
                // the UI shows its own typed text, but without this a protocol bug is invisible.
                emit(RdpUpdate.Closed(cleanExit = false, reason = e.message.orEmpty()))
                break
            }
            for (update in batch) emit(update.also { repaintAfterResize(it) })
            // Graphics-pipeline work arrives on a channel rather than in the batch, so its updates
            // are collected after the PDU that produced them has been decoded.
            for (update in graphics.drainUpdates()) emit(update.also { repaintAfterResize(it) })
            for (update in display.drainUpdates()) emit(update)
            if (batch.any { it is RdpUpdate.Closed }) break
            for (text in clipboard.drainIncoming()) emit(RdpUpdate.ClipboardText(text))
        }
    }.flowOn(Dispatchers.IO)
        // Runs on the collector side, so it fires even while the read loop is parked in a blocking
        // read; closing the socket is what unblocks it (see VncSocketSession for the full argument).
        .onCompletion { closeSocket() }

    /**
     * Ask for the whole desktop again after a resolution change, whichever way it arrived — the
     * legacy reactivation or the pipeline's reset.
     *
     * Both sides throw away what they held and start the new resolution from an empty screen, and
     * the two ideas of "empty" have to agree exactly: the progressive codec refines tiles rather
     * than resending them, so a single tile whose state the two ends disagree about stays on screen
     * as a grey square for the rest of the session. Asking for a repaint costs one full frame and
     * settles the argument — every tile arrives established again.
     */
    private var desktopEstablished = false

    private suspend fun repaintAfterResize(update: RdpUpdate) {
        if (update !is RdpUpdate.Resize) return
        // The first one is the session announcing the size it opened at, not a change of it: nothing
        // has been painted yet, so there is no disagreement to settle, and the server is about to
        // send the whole screen of its own accord.
        // Neither the pipeline's reset nor the display control channel goes through a reactivation,
        // so this is the only place that learns the new size.
        codec.desktopResized(update.width, update.height)
        if (!desktopEstablished) {
            desktopEstablished = true
            return
        }
        codec.requestRefresh(listOf(RdpRect(0, 0, update.width, update.height)))
    }

    override suspend fun sendKey(scancode: Int, down: Boolean, extended: Boolean) =
        withContext(Dispatchers.IO) { codec.sendKey(scancode, down, extended) }

    override suspend fun sendUnicode(code: Int, down: Boolean) =
        withContext(Dispatchers.IO) { codec.sendUnicode(code, down) }

    override suspend fun sendPointerMove(x: Int, y: Int) =
        withContext(Dispatchers.IO) { codec.sendPointerMove(x, y) }

    override suspend fun sendPointerButton(button: RdpMouseButton, down: Boolean, x: Int, y: Int) =
        withContext(Dispatchers.IO) { codec.sendPointerButton(button, down, x, y) }

    override suspend fun sendWheel(clicks: Int, axis: RdpWheelAxis, x: Int, y: Int) =
        withContext(Dispatchers.IO) { codec.sendWheel(clicks, axis, x, y) }

    override suspend fun sendLockKeys(scroll: Boolean, num: Boolean, caps: Boolean) =
        withContext(Dispatchers.IO) { codec.sendLockKeys(scroll, num, caps) }

    override suspend fun requestRefresh(rects: List<RdpRect>) =
        withContext(Dispatchers.IO) { codec.requestRefresh(rects) }

    override suspend fun setOutputVisible(visible: Boolean) =
        withContext(Dispatchers.IO) { codec.setOutputVisible(visible) }

    override suspend fun setDesktopSize(width: Int, height: Int) {
        withContext(Dispatchers.IO) { display.requestResolution(width, height) }
    }

    override suspend fun sendClipboardText(text: String) =
        withContext(Dispatchers.IO) { clipboard.offerText(text) }

    override suspend fun close() = withContext(Dispatchers.IO) {
        // Ask the server to end the session before dropping the socket, so it tears the session
        // down instead of leaving it disconnected-but-alive for the next logon to inherit.
        runCatching { codec.requestShutdown() }
        closeSocket()
    }

    private fun closeSocket() {
        if (!closedFlag.compareAndSet(false, true)) return
        // The device goes first: closing it releases a playback write parked on a full buffer, which
        // is what lets the playback coroutine end instead of outliving the session.
        audio?.close()
        audioScope?.cancel()
        connection.close()
    }
}
