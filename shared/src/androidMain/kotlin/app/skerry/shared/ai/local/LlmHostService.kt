package app.skerry.shared.ai.local

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * The isolated inference host on Android: declared with `android:process=":llm"`, so llama.cpp
 * lives in its own process and a native abort (issue #37) costs one answer instead of the app and
 * every open SSH session. The counterpart of the child JVM that [ProcessLlmHostLauncher] starts on
 * desktop; both run the same [LlmHostServer].
 *
 * Binding gives the client a socket pair rather than a per-message Binder API: the same line
 * protocol then works on both platforms, and the app sees a dead host as a closed stream.
 */
class LlmHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serving: Job? = null
    private var handlerThread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder {
        val thread = handlerThread ?: HandlerThread("llm-host-bind").also { it.start(); handlerThread = it }
        return Messenger(Handler(thread.looper) { message -> handle(message) }).binder
    }

    private fun handle(message: Message): Boolean {
        if (message.what != MSG_OPEN) return false
        val reply = message.replyTo ?: return true
        val pair = ParcelFileDescriptor.createSocketPair()
        val (host, client) = pair[0] to pair[1]
        serving?.cancel()
        serving = scope.launch {
            // Both ends are sockets: reading and writing go over the same descriptor.
            ParcelFileDescriptor.AutoCloseInputStream(host).use { input ->
                FileOutputStream(host.fileDescriptor).use { output ->
                    LlmHostServer.serve(input, output, LlamatikRuntime(message.arg1))
                }
            }
        }
        val answer = Message.obtain(null, MSG_OPEN).apply {
            data = Bundle().apply { putParcelable(KEY_CHANNEL, client) }
        }
        runCatching { reply.send(answer) }
        // The descriptor was duplicated into the client process during the transaction.
        runCatching { client.close() }
        return true
    }

    override fun onDestroy() {
        serving?.cancel()
        scope.cancel()
        handlerThread?.quitSafely()
        super.onDestroy()
    }

    companion object {
        /** Asks for a channel to the host; `arg1` is the context length, `replyTo` receives the socket. */
        const val MSG_OPEN = 1
        const val KEY_CHANNEL = "channel"
    }
}
