@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.skerry.ui.connection

import app.skerry.shared.ssh.SshAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConnectionRecordingTest {
    @Test fun requiredRecorderArmFailurePreventsTransportAndShellOpening() = runTest {
        val transport = ScriptedTransport(emptyList())
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = ConnectionController(transport, scope, maxReconnectAttempts = 0,
            prepareRecording = { _, _ -> error("required recording cannot be persisted") })
        controller.bindHostId("shared-host")
        try {
            controller.connect(testTarget, SshAuth.Password("pw"))
            runCurrent()
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals(0, transport.connectCalls)
            val error = assertIs<ConnectionUiState.Error>(controller.uiState)
            assertEquals("required recording cannot be persisted", error.message)
        } finally { scope.cancel() }
    }
}
