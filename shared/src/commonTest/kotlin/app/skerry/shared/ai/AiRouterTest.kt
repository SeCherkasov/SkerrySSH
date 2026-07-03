package app.skerry.shared.ai

import app.skerry.shared.ai.local.LocalModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AiRouterTest {

    private val device = LocalModel(
        id = "test-model-q4",
        displayName = "Test Model 1B",
        fileName = "m.gguf",
        url = "https://example.com/m.gguf",
        sizeBytes = 1,
        sha256 = "00",
        license = "Apache-2.0",
    )

    private val cloudSettings = AiSettings(apiKey = "sk-x")
    private val deviceSettings = AiSettings(provider = AiProviderKind.DEVICE)

    private fun route(
        policy: AiPolicy,
        settings: AiSettings,
        installed: Boolean,
        model: LocalModel? = device,
    ): AiRoute = AiRouter.route(AiPolicyDecision.of(policy), settings, model, installed)

    @Test
    fun `cloud provider with a key routes to the cloud endpoint`() {
        val r = route(AiPolicy.Balanced, cloudSettings, installed = false)
        val use = assertIs<AiRoute.Use>(r)
        val endpoint = assertIs<AiEndpoint.Cloud>(use.endpoint)
        assertEquals("sk-x", endpoint.config.apiKey)
    }

    @Test
    fun `cloud provider without a key is blocked as not configured`() {
        val r = route(AiPolicy.Balanced, AiSettings(), installed = true)
        assertEquals(AiRoute.Blocked(AiRoute.Reason.CLOUD_NOT_CONFIGURED), r)
    }

    @Test
    fun `device provider with the model installed routes on-device`() {
        val r = route(AiPolicy.Balanced, deviceSettings, installed = true)
        val use = assertIs<AiRoute.Use>(r)
        val endpoint = assertIs<AiEndpoint.Device>(use.endpoint)
        assertEquals(device, endpoint.model)
    }

    @Test
    fun `device provider without the model is blocked until downloaded`() {
        val r = route(AiPolicy.Balanced, deviceSettings, installed = false)
        assertEquals(AiRoute.Blocked(AiRoute.Reason.DEVICE_NOT_READY), r)
    }

    @Test
    fun `strict policy forces on-device even when cloud is the default`() {
        val r = route(AiPolicy.Strict, cloudSettings, installed = true)
        val use = assertIs<AiRoute.Use>(r)
        assertIs<AiEndpoint.Device>(use.endpoint)
    }

    @Test
    fun `strict policy without an installed model is blocked as strict`() {
        val r = route(AiPolicy.Strict, cloudSettings, installed = false)
        assertEquals(AiRoute.Blocked(AiRoute.Reason.STRICT_NEEDS_DEVICE), r)
    }

    @Test
    fun `permissive with device default still routes on-device`() {
        // Выбор пользователя «на устройстве» уважается и там, где облако разрешено.
        val r = route(AiPolicy.Permissive, deviceSettings, installed = true)
        assertIs<AiEndpoint.Device>(assertIs<AiRoute.Use>(r).endpoint)
    }

    @Test
    fun `missing catalog model behaves as not ready`() {
        val strict = route(AiPolicy.Strict, cloudSettings, installed = true, model = null)
        assertEquals(AiRoute.Blocked(AiRoute.Reason.STRICT_NEEDS_DEVICE), strict)

        val chosen = route(AiPolicy.Balanced, deviceSettings, installed = true, model = null)
        assertEquals(AiRoute.Blocked(AiRoute.Reason.DEVICE_NOT_READY), chosen)
    }
}
