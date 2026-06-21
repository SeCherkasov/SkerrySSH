package app.skerry.ui.metrics

import app.skerry.shared.ssh.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HostMetricsControllerTest {

    private val sample = """
        cpu  100 0 100 800 0 0 0 0
        cpu  150 0 150 900 0 0 0 0
        @MEM
        Mem:     4000000000  2100000000  1000000000
        @DISK
        /dev/sda1  51475068 42000000 6900000 87% /
    """.trimIndent()

    @Test
    fun polls_and_publishes_parsed_metrics() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(exec = { ExecResult(0, sample, "") }, scope = scope)

        assertNull(controller.metrics)
        controller.start()

        val m = controller.metrics!!
        assertEquals(50, m.cpuPercent)
        assertEquals(87, m.diskPercent)
        assertEquals(2_100_000_000L, m.memUsedBytes)

        controller.stop()
        scope.cancel()
    }

    @Test
    fun exec_failure_keeps_metrics_null_and_does_not_crash() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(exec = { throw RuntimeException("boom") }, scope = scope)

        controller.start()

        assertNull(controller.metrics)
        controller.stop()
        scope.cancel()
    }

    @Test
    fun start_is_idempotent() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            exec = { calls++; ExecResult(0, sample, "") },
            scope = scope,
        )

        controller.start()
        val afterFirst = calls
        controller.start() // повторный старт не должен поднять второй цикл

        assertEquals(afterFirst, calls)
        controller.stop()
        scope.cancel()
    }
}
