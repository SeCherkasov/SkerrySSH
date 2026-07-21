package app.skerry.ui.host

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalSshAgent
import app.skerry.ui.design.D
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.agent_no_keys
import app.skerry.ui.generated.resources.conn_agent_keys_all
import app.skerry.ui.generated.resources.conn_agent_keys_hint
import app.skerry.ui.generated.resources.conn_agent_keys_title
import app.skerry.ui.generated.resources.conn_field_forward_agent
import app.skerry.ui.generated.resources.conn_forward_agent_desc
import app.skerry.ui.generated.resources.conn_forward_agent_off
import org.jetbrains.compose.resources.stringResource

/**
 * "Agent forwarding" switch in the host form, shared by the desktop modal and the Android sheet
 * (only the type sizes differ, like the other paired form controls).
 *
 * When the agent itself is off the row still works — the profile setting is remembered — but the
 * description says so, otherwise the switch would silently do nothing at connect time.
 */
@Composable
internal fun ForwardAgentRow(form: NewConnectionFormState) = ForwardAgentToggle(form, titleSize = 12.5.sp, descSize = 11.sp)

/** Android variant of [ForwardAgentRow] (larger type, matching the sheet's other rows). */
@Composable
internal fun MobileForwardAgentRow(form: NewConnectionFormState) = ForwardAgentToggle(form, titleSize = 14.sp, descSize = 11.5.sp)

@Composable
private fun ForwardAgentToggle(
    form: NewConnectionFormState,
    titleSize: androidx.compose.ui.unit.TextUnit,
    descSize: androidx.compose.ui.unit.TextUnit,
) {
    val agent = LocalSshAgent.current
    val agentOn = agent?.enabled == true
    // An agent with no keys in it forwards an empty list, and the remote falls back to a password
    // prompt with nothing to explain it — say so where the switch is.
    val warning = when {
        !agentOn -> Res.string.conn_forward_agent_off
        !agent.hasKeys -> Res.string.agent_no_keys
        else -> null
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.conn_field_forward_agent), color = D.text, size = titleSize)
            Txt(
                stringResource(warning ?: Res.string.conn_forward_agent_desc),
                color = if (warning == null) D.dim else D.amber,
                size = descSize,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Toggle(form.forwardAgent, { form.forwardAgent = !form.forwardAgent })
    }
    // Which keys go with the forwarding is part of the same decision, so it belongs here rather
    // than in Settings — where the user cannot see which host they are deciding for.
    if (form.forwardAgent && agentOn) AgentKeyPicker(form, titleSize, descSize)
}

/**
 * Per-host key set. Empty selection in the profile means "every key in the agent" — what a profile
 * made before this setting existed says, and what OpenSSH does, since its agent has no per-host
 * sets at all. Ticking any key narrows the host to exactly those.
 */
@Composable
private fun AgentKeyPicker(
    form: NewConnectionFormState,
    titleSize: androidx.compose.ui.unit.TextUnit,
    descSize: androidx.compose.ui.unit.TextUnit,
) {
    val keys = LocalSshAgent.current?.agentKeys.orEmpty().filter { it.agentEnabled }
    if (keys.isEmpty()) return
    val chosen = form.agentKeyIds
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Txt(stringResource(Res.string.conn_agent_keys_title), color = D.text, size = titleSize)
        Txt(
            stringResource(Res.string.conn_agent_keys_hint),
            color = D.dim,
            size = descSize,
            modifier = Modifier.padding(top = 3.dp, bottom = 6.dp),
        )
        AgentKeyChoice(
            label = stringResource(Res.string.conn_agent_keys_all),
            checked = chosen.isEmpty(),
            size = descSize,
            onClick = { form.agentKeyIds = emptyList() },
        )
        keys.forEach { key ->
            AgentKeyChoice(
                label = key.label,
                checked = key.id in chosen,
                size = descSize,
                onClick = {
                    form.agentKeyIds = if (key.id in chosen) chosen - key.id else chosen + key.id
                },
            )
        }
    }
}

@Composable
private fun AgentKeyChoice(label: String, checked: Boolean, size: androidx.compose.ui.unit.TextUnit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym(if (checked) "check_box" else "check_box_outline_blank", size = 16.sp, color = if (checked) D.cyan else D.faint)
        Txt(label, color = if (checked) D.text else D.dim, size = size)
    }
}
