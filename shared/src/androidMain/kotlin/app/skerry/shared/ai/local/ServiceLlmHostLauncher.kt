package app.skerry.shared.ai.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import app.skerry.shared.ai.AiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream

/**
 * Binds [LlmHostService] in the `:llm` process and takes the socket it hands back. The service is
 * started on demand — a user who never asks the local model anything never pays for the process —
 * and is released together with the link, so a crashed host is simply replaced by a fresh one.
 */
class ServiceLlmHostLauncher(
    context: Context,
    private val contextLength: Int,
) : LlmHostLauncher {

    private val appContext = context.applicationContext

    override suspend fun launch(): LlmHostLink = withContext(Dispatchers.IO) {
        val binder = CompletableDeferred<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder.complete(null) // the host died before it could hand over the channel
            }
        }

        val intent = Intent(appContext, LlmHostService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            runCatching { appContext.unbindService(connection) }
            fail("could not start the inference service")
        }
        try {
            val service = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { binder.await() }
                ?: fail("the inference service did not start")
            val channel = openChannel(Messenger(service)) ?: fail("the inference service gave no channel")
            StreamLlmHostLink(
                input = ParcelFileDescriptor.AutoCloseInputStream(channel),
                output = FileOutputStream(channel.fileDescriptor),
            ) {
                runCatching { appContext.unbindService(connection) }
            }
        } catch (e: Throwable) {
            runCatching { appContext.unbindService(connection) }
            throw e
        }
    }

    /** Asks the service for a socket pair and waits for the descriptor to come back. */
    private suspend fun openChannel(service: Messenger): ParcelFileDescriptor? {
        val answer = CompletableDeferred<ParcelFileDescriptor?>()
        val thread = HandlerThread("llm-host-reply").also { it.start() }
        try {
            val replyTo = Messenger(
                Handler(thread.looper) { message ->
                    answer.complete(message.data?.channel())
                    true
                },
            )
            val request = Message.obtain(null, LlmHostService.MSG_OPEN).apply {
                arg1 = contextLength
                this.replyTo = replyTo
            }
            service.send(request)
            return withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { answer.await() }
        } finally {
            thread.quitSafely()
        }
    }

    @Suppress("DEPRECATION") // the typed getParcelable overload only exists from API 33
    private fun Bundle.channel(): ParcelFileDescriptor? {
        classLoader = ParcelFileDescriptor::class.java.classLoader
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(LlmHostService.KEY_CHANNEL, ParcelFileDescriptor::class.java)
        } else {
            getParcelable(LlmHostService.KEY_CHANNEL)
        }
    }

    private fun fail(reason: String): Nothing =
        throw AiException(AiException.Kind.ENGINE_CRASHED, "Local inference host: $reason")

    private companion object {
        const val BIND_TIMEOUT_MILLIS = 30_000L
    }
}
