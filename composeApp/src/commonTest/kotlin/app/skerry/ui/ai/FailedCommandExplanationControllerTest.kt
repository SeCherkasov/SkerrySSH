package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiRoute
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ai.SecretRedactor
import app.skerry.shared.terminal.FailedCommandSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FailedCommandExplanationControllerTest {
    private val cloud = AiSettings(apiKey = "synthetic-key")
    private val snapshot = FailedCommandSnapshot("false", "failure", 1, false)

    private class Provider(val hang: Boolean = false) : AiProvider {
        val requests = mutableListOf<AiChatRequest>()
        var closed = false
        override fun chat(request: AiChatRequest) = flow {
            requests += request
            emit(AiDelta("The command failed."))
            if (hang) awaitCancellation()
        }
        override suspend fun close() { closed = true }
    }

    @Test fun preparingAndCancellingNeverConstructsAProvider() = runTest {
        var built = false
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { cloud },
            providerFactory = { built = true; Provider() }, scope = this,
        )
        controller.prepare(snapshot)
        assertNotNull(controller.preview)
        controller.cancel()
        runCurrent()
        assertFalse(built)
        assertNull(controller.preview)
    }

    @Test fun strictWithoutInstalledDeviceNeverUsesConfiguredCloud() = runTest {
        var built = false
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Strict }, settings = { cloud },
            providerFactory = { built = true; Provider() }, scope = this,
        )
        controller.prepare(snapshot)
        assertEquals(AiNotice.Blocked(AiRoute.Reason.STRICT_NEEDS_DEVICE), controller.notice)
        assertNull(controller.preview)
        controller.confirm()
        runCurrent()
        assertFalse(built)
    }

    @Test fun balancedPreviewIsRedactedAndExactlyMatchesTheOnlyRequest() = runTest {
        val provider = Provider()
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { cloud }, providerFactory = { provider }, scope = this,
        )
        controller.prepare(FailedCommandSnapshot("tool --password=synthetic-secret", "TOKEN=synthetic-output-secret", 3, true))
        val preview = assertNotNull(controller.preview)
        assertFalse(preview.messages.any { "synthetic-secret" in it.content || "synthetic-output-secret" in it.content })
        assertTrue(preview.messages.last().content.contains(SecretRedactor.MASK))
        assertEquals(listOf(AiRole.SYSTEM, AiRole.USER), preview.messages.map { it.role })
        controller.confirm()
        controller.confirm()
        runCurrent()
        assertEquals(1, provider.requests.size)
        assertEquals(preview.messages, provider.requests.single().messages)
        assertEquals(preview.endpoint.requestModel, provider.requests.single().model)
        assertEquals("The command failed.", controller.answer)
        assertTrue(provider.closed)
    }

    @Test fun staleOriginPolicyAndSettingsCannotRedirectAnApprovedPreview() = runTest {
        for (change in 0..2) {
            var current = true
            var policy = AiPolicy.Permissive
            var settings = cloud
            var built = false
            val controller = FailedCommandExplanationController(
                policy = { policy }, settings = { settings }, isCurrent = { current },
                providerFactory = { built = true; Provider() }, scope = this,
            )
            controller.prepare(snapshot)
            when (change) {
                0 -> current = false
                1 -> policy = AiPolicy.Strict
                2 -> settings = cloud.copy(baseUrl = "https://changed.example/v1")
            }
            controller.confirm()
            runCurrent()
            assertFalse(built)
            assertTrue(controller.unavailable)
        }
    }

    @Test fun changesBeforeScheduledProviderCreationAlsoBlockOutboundData() = runTest {
        var current = true
        var built = false
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { cloud }, isCurrent = { current },
            providerFactory = { built = true; Provider() }, scope = this,
        )
        controller.prepare(snapshot)
        controller.confirm()
        current = false
        runCurrent()
        assertFalse(built)
        assertFalse(controller.busy)
        assertTrue(controller.unavailable)
    }

    @Test fun successiveExplanationsNeverReplayThePreviousAnswerOrCommand() = runTest {
        val provider = Provider()
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Permissive }, settings = { cloud }, providerFactory = { provider }, scope = this,
        )
        controller.prepare(FailedCommandSnapshot("first-command", "first-output", 1, false))
        controller.confirm()
        runCurrent()
        controller.prepare(FailedCommandSnapshot("second-command", "second-output", 2, false))
        controller.confirm()
        runCurrent()
        val request = provider.requests.last()
        assertEquals(2, request.messages.size)
        assertFalse(request.messages.any { "first-command" in it.content || "first-output" in it.content || "The command failed." in it.content })
    }

    @Test fun permissiveKeepsValuesWhileOffAndMissingBoundariesBlock() = runTest {
        var policy = AiPolicy.Permissive
        val controller = FailedCommandExplanationController(
            policy = { policy }, settings = { cloud }, providerFactory = { Provider() }, scope = this,
        )
        controller.prepare(FailedCommandSnapshot("PASSWORD=synthetic-value", "", 1, false))
        assertTrue(assertNotNull(controller.preview).messages.last().content.contains("synthetic-value"))
        policy = AiPolicy.Off
        controller.prepare(snapshot)
        assertNull(controller.preview)
        controller.prepare(null)
        assertTrue(controller.unavailable)
    }

    @Test fun strictUsesOnlyTheReadyDeviceEvenWhenCloudIsSelected() = runTest {
        val provider = Provider()
        var endpoint: app.skerry.shared.ai.AiEndpoint? = null
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Strict }, settings = { cloud }, localInstalled = { true },
            providerFactory = { endpoint = it; provider }, scope = this,
        )
        controller.prepare(snapshot)
        val preview = assertNotNull(controller.preview)
        assertTrue(preview.endpoint is app.skerry.shared.ai.AiEndpoint.Device)
        controller.confirm()
        runCurrent()
        assertEquals(preview.endpoint, endpoint)
        assertEquals(preview.messages, provider.requests.single().messages)
    }

    @Test fun globalOffAndLostDeviceReadinessNeverConstructAProvider() = runTest {
        var settings = cloud.copy(provider = app.skerry.shared.ai.AiProviderKind.OFF)
        var installed = true
        var built = false
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Strict }, settings = { settings }, localInstalled = { installed },
            providerFactory = { built = true; Provider() }, scope = this,
        )
        controller.prepare(snapshot)
        assertEquals(AiNotice.Blocked(AiRoute.Reason.AI_DISABLED), controller.notice)
        settings = cloud
        controller.prepare(snapshot)
        assertNotNull(controller.preview)
        installed = false
        controller.confirm()
        runCurrent()
        assertFalse(built)
        assertTrue(controller.unavailable)
    }

    @Test fun truncatedPrivateKeyPrefixAndInvisibleTextAreSafeInTheExactRequest() = runTest {
        val provider = Provider()
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { cloud }, providerFactory = { provider }, scope = this,
        )
        controller.prepare(FailedCommandSnapshot("false", "shortSecretFragment\n-----END PRIVATE KEY-----\nerror" + Char(0x202E), 1, true))
        val preview = assertNotNull(controller.preview)
        val text = preview.messages.last().content
        assertFalse("shortSecretFragment" in text)
        assertFalse(Char(0x202E) in text)
        assertTrue("<U+202E>" in text)
        controller.confirm()
        runCurrent()
        assertEquals(preview.messages, provider.requests.single().messages)
    }

    @Test fun incompletePrivateKeysNeverReachTheBalancedPreviewOrRequest() = runTest {
        val fragments = listOf(
            "-----BEGIN PRIVATE KEY-----\nc2hvcnRTZWNyZXQ=\nYW5vdGhlcg==" to false,
            "-----BEGIN RSA PRIVATE KEY-----\nc2hvcnRTZWNyZXQ=" to true,
            "c2hvcnRTZWNyZXQ=\nYW5vdGhlcg==\nerror: failed" to true,
        )
        for ((output, truncated) in fragments) {
            val provider = Provider()
            val controller = FailedCommandExplanationController(
                policy = { AiPolicy.Balanced }, settings = { cloud }, providerFactory = { provider }, scope = this,
            )
            controller.prepare(FailedCommandSnapshot("false", output, 1, truncated))
            val preview = assertNotNull(controller.preview)
            val text = preview.messages.last().content
            assertFalse("c2hvcnRTZWNyZXQ=" in text, "Private-key fragment survived: $output")
            assertFalse("YW5vdGhlcg==" in text)
            assertTrue(SecretRedactor.MASK in text)
            controller.confirm()
            runCurrent()
            assertEquals(preview.messages, provider.requests.single().messages)
        }
    }

    @Test fun policyChangeCancelsAnAlreadyStreamingAnswer() = runTest {
        var policy = AiPolicy.Balanced
        val provider = Provider(hang = true)
        val controller = FailedCommandExplanationController(
            policy = { policy }, settings = { cloud }, providerFactory = { provider }, scope = this,
        )
        controller.prepare(snapshot)
        controller.confirm()
        runCurrent()
        policy = AiPolicy.Off
        controller.revalidate()
        runCurrent()
        assertTrue(controller.unavailable)
        assertFalse(controller.busy)
        assertNull(controller.answer)
        assertTrue(provider.closed)
    }

    @Test fun cancellationDropsLateResponsesAndResetsBusy() = runTest {
        val provider = Provider(hang = true)
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { cloud }, providerFactory = { provider }, scope = this,
        )
        controller.prepare(snapshot)
        controller.confirm()
        runCurrent()
        assertTrue(controller.busy)
        controller.cancel()
        runCurrent()
        assertFalse(controller.busy)
        assertNull(controller.answer)
        assertTrue(provider.closed)
    }
}
