package app.skerry.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.agent.SshAgentAction
import app.skerry.shared.agent.SshAgentActivity
import app.skerry.shared.agent.SshAgentOrigin
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.securityMoment
import app.skerry.ui.agent.SshAgentController
import app.skerry.ui.design.D
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.agent_activity
import app.skerry.ui.generated.resources.agent_activity_declined
import app.skerry.ui.generated.resources.agent_activity_denied
import app.skerry.ui.generated.resources.agent_activity_line
import app.skerry.ui.generated.resources.agent_activity_listed
import app.skerry.ui.generated.resources.agent_activity_none
import app.skerry.ui.generated.resources.agent_activity_refused
import app.skerry.ui.generated.resources.agent_activity_signed
import app.skerry.ui.generated.resources.agent_confirm
import app.skerry.ui.generated.resources.agent_confirm_desc
import app.skerry.ui.generated.resources.agent_enable
import app.skerry.ui.generated.resources.agent_enable_desc
import app.skerry.ui.generated.resources.agent_key_certificate
import app.skerry.ui.generated.resources.agent_key_private
import app.skerry.ui.generated.resources.agent_keys
import app.skerry.ui.generated.resources.agent_keys_desc
import app.skerry.ui.generated.resources.agent_keys_empty
import app.skerry.ui.generated.resources.agent_origin_session
import app.skerry.ui.generated.resources.agent_origin_socket
import app.skerry.ui.generated.resources.agent_socket
import app.skerry.ui.generated.resources.agent_socket_copy
import app.skerry.ui.generated.resources.agent_socket_desc
import app.skerry.ui.generated.resources.agent_socket_failed
import app.skerry.ui.generated.resources.agent_socket_hint
import app.skerry.ui.generated.resources.agent_socket_needs_agent
import app.skerry.ui.generated.resources.agent_socket_section
import app.skerry.ui.generated.resources.agent_socket_unsupported
import app.skerry.ui.generated.resources.agent_subtitle
import app.skerry.ui.generated.resources.agent_title
import app.skerry.ui.generated.resources.settings_time_days_ago
import app.skerry.ui.generated.resources.settings_time_today
import app.skerry.ui.generated.resources.settings_time_yesterday
import app.skerry.ui.vault.copyTextToClipboard
import org.jetbrains.compose.resources.stringResource

// SSH agent section: master switch, which vault keys the agent offers, the local agent socket
// (desktop) and what has been asking for signatures lately.

/**
 * Live SSH agent section. [controller] == null means mock/preview without a vault: the layout
 * renders with the switches off and inert, like the other sections do.
 */
@Composable
internal fun AgentSection(controller: SshAgentController?) {
    SectionTitle(stringResource(Res.string.agent_title), stringResource(Res.string.agent_subtitle))

    SettingToggleRow(
        stringResource(Res.string.agent_enable),
        stringResource(Res.string.agent_enable_desc),
        on = controller?.enabled == true,
        onToggle = { controller?.enable(controller.enabled != true) },
    )

    SettingToggleRow(
        stringResource(Res.string.agent_confirm),
        stringResource(Res.string.agent_confirm_desc),
        on = controller?.confirmSignatures == true,
        onToggle = { controller?.confirmEachSignature(controller.confirmSignatures != true) },
    )

    SectionLabel(stringResource(Res.string.agent_keys))
    Txt(stringResource(Res.string.agent_keys_desc), color = D.faint, size = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
    val keys = controller?.agentKeys.orEmpty()
    if (keys.isEmpty()) {
        Txt(stringResource(Res.string.agent_keys_empty), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
    } else {
        keys.forEach { key -> AgentKeyRow(key, controller) }
    }

    // The local socket is desktop-only and needs owner-only file permissions; where that is
    // impossible the row still appears, explaining why rather than silently vanishing.
    SectionLabel(stringResource(Res.string.agent_socket_section))
    if (controller != null && !controller.socketSupported) {
        Txt(stringResource(Res.string.agent_socket_unsupported), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
    } else {
        SettingToggleRow(
            stringResource(Res.string.agent_socket),
            stringResource(Res.string.agent_socket_desc),
            on = controller?.socketEnabled == true,
            onToggle = { controller?.exposeSocket(controller.socketEnabled != true) },
        )
        // A socket switch with the agent off would sit there "on" and serve nothing.
        if (controller?.socketEnabled == true && !controller.enabled) {
            Txt(stringResource(Res.string.agent_socket_needs_agent), color = D.amber, size = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
        controller?.socketPath?.let { path -> SocketPathRow(path) }
        if (controller?.socketFailed == true) {
            Txt(stringResource(Res.string.agent_socket_failed), color = D.storm, size = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    SectionLabel(stringResource(Res.string.agent_activity))
    val activity = controller?.activity.orEmpty()
    if (activity.isEmpty()) {
        Txt(stringResource(Res.string.agent_activity_none), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
    } else {
        activity.forEach { entry ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("●", color = if (entry.action == SshAgentAction.Signed) D.amber else D.moss, size = 9.sp)
                Txt(agentActivityLine(entry), color = D.dim, size = 12.sp)
            }
        }
    }
}

/** One keychain key with the switch that puts it in the agent. */
@Composable
private fun AgentKeyRow(key: Credential, controller: SshAgentController?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(key.label, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(agentKeyKind(key), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Toggle(key.agentEnabled, { controller?.setKeyInAgent(key.id, !key.agentEnabled) })
    }
}

/** The `SSH_AUTH_SOCK` value, with a copy action — it is meant to be pasted into a shell. */
@Composable
private fun SocketPathRow(path: String) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Txt(stringResource(Res.string.agent_socket_hint), color = D.faint, size = 11.sp)
        Row(
            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { copyTextToClipboard(path) }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt(path, color = D.cyanBright, size = 12.sp)
            Sym("content_copy", size = 13.sp, color = D.cyan)
            Txt(stringResource(Res.string.agent_socket_copy), color = D.cyan, size = 11.sp)
        }
    }
}

/** Localized secret kind of an agent key (only key-shaped secrets reach this section). */
@Composable
private fun agentKeyKind(key: Credential): String = stringResource(
    when (key.secret) {
        is CredentialSecret.Certificate -> Res.string.agent_key_certificate
        else -> Res.string.agent_key_private
    },
)

/** Activity row: "Signed with <key> · <who> · <when>". */
@Composable
internal fun agentActivityLine(entry: SshAgentActivity): String {
    val action = stringResource(
        when (entry.action) {
            SshAgentAction.Listed -> Res.string.agent_activity_listed
            SshAgentAction.Signed -> Res.string.agent_activity_signed
            SshAgentAction.Refused -> Res.string.agent_activity_refused
            SshAgentAction.ForwardingDenied -> Res.string.agent_activity_denied
            SshAgentAction.Declined -> Res.string.agent_activity_declined
        },
    )
    val origin = when (val where = entry.origin) {
        is SshAgentOrigin.Session -> stringResource(Res.string.agent_origin_session, where.address)
        SshAgentOrigin.LocalSocket -> stringResource(Res.string.agent_origin_socket)
    }
    val head = entry.keyComment?.let { "$action · $it" } ?: action
    return stringResource(Res.string.agent_activity_line, head, origin, agentActivityTime(entry.at))
}

/** Relative time of an activity entry, sharing the security log's wording. */
@Composable
private fun agentActivityTime(at: String): String {
    val moment = securityMoment(at) ?: return at
    return when (moment.daysAgo) {
        0 -> stringResource(Res.string.settings_time_today, moment.timeOfDay)
        1 -> stringResource(Res.string.settings_time_yesterday, moment.timeOfDay)
        else -> stringResource(Res.string.settings_time_days_ago, moment.daysAgo)
    }
}
