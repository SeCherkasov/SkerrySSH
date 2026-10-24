package app.skerry.ui.connection

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.securitykey.SecurityKeyException
import app.skerry.shared.securitykey.SecurityKeyFailure
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.design.Txt
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.security_key_no_device
import app.skerry.ui.generated.resources.security_key_pin_invalid
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertIs

/** The shared probe mappers feed both the desktop and mobile connection forms. */
@OptIn(ExperimentalTestApi::class)
class ConnectionProbeSecurityKeyTextTest {
    private val target = SshTarget(host = "host", port = 22, username = "user")
    private val auth = SshAuth.PublicKey("stub consumed only by the fake")

    @Test
    fun testConnectionExplainsLocalPinFailure() {
        val result = runBlocking { runConnectionTest(TextProbeHardwareFailure(SecurityKeyFailure.PinInvalid), target, auth) }
        val failure = assertIs<ConnectionTestStatus.Failure>(result)
        val expected = runBlocking { getString(Res.string.security_key_pin_invalid) }
        runForm({ Txt(connectionTestFailureText(failure.problem)) }) {
            onNodeWithText(expected).assertExists()
        }
    }

    @Test
    fun containerBrowseExplainsMissingToken() {
        val result = runBlocking { listContainers(TextProbeHardwareFailure(SecurityKeyFailure.NoDevice), target, auth, ContainerSpec()) }
        val failure = assertIs<ContainerBrowseStatus.Failure>(result)
        val expected = runBlocking { getString(Res.string.security_key_no_device) }
        runForm({ Txt(containerBrowseFailureText(failure.problem)) }) {
            onNodeWithText(expected).assertExists()
        }
    }
}

private class TextProbeHardwareFailure(private val reason: SecurityKeyFailure) : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = throw SecurityKeyException(reason)
}
