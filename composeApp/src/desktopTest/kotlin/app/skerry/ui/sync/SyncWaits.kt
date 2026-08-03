package app.skerry.ui.sync

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail

/**
 * How long the coordinator tests wait for the coordinator to get somewhere.
 *
 * These tests drive a real `FileVault` over real Argon2id (m = 64 MiB) on the coordinator's own
 * `Dispatchers.Default` scope, so what they wait for costs milliseconds on an idle machine and can
 * cost orders of magnitude more on one that is simultaneously running the rest of the build — a
 * memory-hard KDF is the first thing to lose to paging. The number is therefore not a latency
 * assertion: it only has to be large enough that anything slower is a wedged coordinator rather than
 * a busy machine (issue #141). A slow honest failure is cheap; a false one discredits the suite.
 *
 * Waits that assert a *period* (a health poll reacting within its own injected interval) keep their
 * own explicit, small budget — that budget is the assertion.
 */
private const val SYNC_WAIT_BUDGET_MS = 120_000L

/** Boxed so a `null` returned by the awaited block isn't read as a timeout. */
private class Awaited<T>(val value: T)

private fun timedOut(what: String, detail: String): Nothing =
    fail("timed out after ${SYNC_WAIT_BUDGET_MS / 1_000}s waiting for $what$detail")

/**
 * Waits for [block] under the shared budget, failing with [what] instead of a bare
 * [kotlinx.coroutines.TimeoutCancellationException] — a timeout that names nothing reads like a sync
 * regression until someone re-runs the suite.
 */
suspend fun <T> awaitSync(what: String, block: suspend () -> T): T =
    (withTimeoutOrNull(SYNC_WAIT_BUDGET_MS) { Awaited(block()) } ?: timedOut(what, "")).value

/** [awaitSync] for the common case: the first status matching [predicate]. */
suspend fun StateFlow<SyncStatus>.awaitStatus(what: String, predicate: (SyncStatus) -> Boolean): SyncStatus =
    (
        withTimeoutOrNull(SYNC_WAIT_BUDGET_MS) { Awaited(first(predicate)) }
            ?: timedOut(what, "; last status: $value")
        ).value
