package app.skerry.shared.securitykey

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/** Serial bounded USB probes may be superseded by an NFC choice without committing an unrelated HID. */
internal class SecurityKeyDiscovery<C>(
    private val permission: suspend (C) -> Boolean,
    private val validate: suspend (C) -> Boolean,
    capacity: Int = 16,
) {
    private val candidates = Channel<C>(capacity.also { require(it in 1..128) })
    private val selected = CompletableDeferred<C>()
    private val failure = MutableStateFlow<SecurityKeyFailure?>(null)
    val lastFailure: SecurityKeyFailure? get() = failure.value
    fun offer(candidate: C): Boolean = candidates.trySend(candidate).isSuccess
    fun offerAlternative(candidate: C): Boolean = selected.complete(candidate)
    suspend fun await(): C = coroutineScope {
        val worker = launch {
            for (candidate in candidates) {
                if (selected.isCompleted) break
                try {
                    if (!permission(candidate)) { failure.value = SecurityKeyFailure.PermissionDenied; continue }
                    if (validate(candidate) && selected.complete(candidate)) break
                } catch (e: CancellationException) { throw e
                } catch (e: SecurityKeyException) {
                    if (e.reason != SecurityKeyFailure.InvalidKey) failure.value = e.reason
                } catch (_: Exception) { failure.value = SecurityKeyFailure.DeviceError }
            }
        }
        try { selected.await() } finally { worker.cancel(); candidates.cancel() }
    }
}
