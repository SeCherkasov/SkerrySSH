package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.agent.SshAgentAction
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.agent.SshAgentController
import app.skerry.ui.app.LocalSshAgent
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.D
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.agent_activity
import app.skerry.ui.generated.resources.agent_activity_none
import app.skerry.ui.generated.resources.agent_confirm
import app.skerry.ui.generated.resources.agent_confirm_desc
import app.skerry.ui.generated.resources.agent_enable
import app.skerry.ui.generated.resources.agent_enable_desc
import app.skerry.ui.generated.resources.agent_key_certificate
import app.skerry.ui.generated.resources.agent_key_private
import app.skerry.ui.generated.resources.agent_keys
import app.skerry.ui.generated.resources.agent_keys_desc
import app.skerry.ui.generated.resources.agent_keys_empty
import app.skerry.ui.generated.resources.agent_subtitle_mobile
import app.skerry.ui.generated.resources.agent_title
import app.skerry.ui.settings.agentActivityLine
import org.jetbrains.compose.resources.stringResource

/**
 * Android counterpart of the desktop SSH agent section: master switch, which vault keys the agent
 * offers, and recent activity. There is no local socket row — `SSH_AUTH_SOCK` has nothing to serve
 * on Android, so agent forwarding into a session is the whole feature here.
 */
@Composable
fun MobileAgentScreen(state: MobileDesignState) {
    val controller = LocalSshAgent.current
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobilePushHeader(stringResource(Res.string.agent_title), onBack = state::pop)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(
                stringResource(Res.string.agent_subtitle_mobile),
                color = D.dim,
                size = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            MobileAgentToggleRow(
                stringResource(Res.string.agent_enable),
                stringResource(Res.string.agent_enable_desc),
                on = controller?.enabled == true,
                onToggle = { controller?.enable(controller.enabled != true) },
            )
            MobileAgentToggleRow(
                stringResource(Res.string.agent_confirm),
                stringResource(Res.string.agent_confirm_desc),
                on = controller?.confirmSignatures == true,
                onToggle = { controller?.confirmEachSignature(controller.confirmSignatures != true) },
            )

            MobileSectionLabel(stringResource(Res.string.agent_keys))
            Txt(stringResource(Res.string.agent_keys_desc), color = D.faint, size = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            val keys = controller?.agentKeys.orEmpty()
            if (keys.isEmpty()) {
                Txt(stringResource(Res.string.agent_keys_empty), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                keys.forEach { key -> MobileAgentKeyRow(key, controller) }
            }

            MobileSectionLabel(stringResource(Res.string.agent_activity))
            val activity = controller?.activity.orEmpty()
            if (activity.isEmpty()) {
                Txt(stringResource(Res.string.agent_activity_none), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
            } else {
                activity.forEach { entry ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt("●", color = if (entry.action == SshAgentAction.Signed) D.amber else D.moss, size = 9.sp)
                        Txt(agentActivityLine(entry), color = D.dim, size = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** One switch with its explanation, as the desktop section's [SettingToggleRow] does. */
@Composable
private fun MobileAgentToggleRow(title: String, description: String, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 14.5.sp)
            Txt(description, color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Toggle(on, onToggle)
    }
}

@Composable
private fun MobileAgentKeyRow(key: Credential, controller: SshAgentController?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(key.label, color = D.text, size = 14.5.sp)
            Txt(
                stringResource(
                    if (key.secret is CredentialSecret.Certificate) Res.string.agent_key_certificate
                    else Res.string.agent_key_private,
                ),
                color = D.dim,
                size = 11.5.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Toggle(key.agentEnabled, { controller?.setKeyInAgent(key.id, !key.agentEnabled) })
    }
}
