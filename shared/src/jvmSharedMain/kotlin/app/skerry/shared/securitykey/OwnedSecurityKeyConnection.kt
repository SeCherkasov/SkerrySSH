package app.skerry.shared.securitykey

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Close the raw transport before a cancelled scope waits for blocked CTAP setup/commands. */
internal suspend fun <R : Closeable, T> withOwnedSecurityKeyConnection(resource: R, action: suspend (R) -> T): T {
    val closed = AtomicBoolean()
    fun close() {
        if (closed.compareAndSet(false, true)) {
            try { resource.close() } catch (_: IOException) { /* Preserve the operation's outcome. */ }
        }
    }
    return try {
        coroutineScope {
            val work = async(Dispatchers.IO) { action(resource) }
            try { work.await() } finally { close() }
        }
    } finally { close() }
}
