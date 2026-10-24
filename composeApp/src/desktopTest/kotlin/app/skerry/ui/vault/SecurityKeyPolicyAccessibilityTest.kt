package app.skerry.ui.vault

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.securitykey.SecurityKeyAssertion
import app.skerry.shared.securitykey.SecurityKeyGenerationOptions
import app.skerry.shared.securitykey.SecurityKeyPrompter
import app.skerry.shared.securitykey.SecurityKeyService
import app.skerry.shared.securitykey.SecurityKeySignRequest
import app.skerry.shared.vault.GeneratedSshKey
import app.skerry.ui.app.LocalSecurityKeyPrompts
import app.skerry.ui.app.LocalSecurityKeyService
import app.skerry.ui.app.LocalSecurityKeysEnabled
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.security_key_hardware
import app.skerry.ui.generated.resources.security_key_resident
import app.skerry.ui.generated.resources.security_key_uv
import app.skerry.ui.securitykey.SecurityKeyPromptController
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SecurityKeyPolicyAccessibilityTest {
    @Test
    fun hardwarePoliciesExposeTheirCheckedState() {
        val hardware = runBlocking { getString(Res.string.security_key_hardware) }
        val labels = runBlocking { listOf(getString(Res.string.security_key_resident), getString(Res.string.security_key_uv)) }
        val service = object : SecurityKeyService {
            override suspend fun generate(options: SecurityKeyGenerationOptions, prompter: SecurityKeyPrompter): GeneratedSshKey = error("No hardware call expected")
            override suspend fun sign(request: SecurityKeySignRequest, prompter: SecurityKeyPrompter): SecurityKeyAssertion = error("No signing expected")
        }
        val prompts = SecurityKeyPromptController()
        runForm({
            CompositionLocalProvider(LocalSecurityKeysEnabled provides true, LocalSecurityKeyService provides service, LocalSecurityKeyPrompts provides prompts) {
                GenerateKeyDialog({}, { _, _ -> }, { _, _ -> })
            }
        }) {
            onNodeWithText(hardware).performClick()
            labels.forEach { label ->
                onNodeWithText(label).assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
            }
        }
    }
}
