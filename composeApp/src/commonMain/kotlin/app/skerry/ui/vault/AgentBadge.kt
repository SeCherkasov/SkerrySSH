package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.Credential
import app.skerry.ui.app.LocalSshAgent
import app.skerry.ui.design.Badge
import app.skerry.ui.design.D
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_badge_agent
import org.jetbrains.compose.resources.stringResource

/**
 * "in agent" marker on a key in the keychain — the way back from Settings → SSH agent, where the
 * text already points at the vault.
 *
 * Shown only while the agent is actually on: the flag lives on the key, but a key in a switched-off
 * agent signs nothing, and a badge claiming otherwise would be the wrong thing to trust.
 */
@Composable
fun AgentBadge(credential: Credential, size: androidx.compose.ui.unit.TextUnit = 9.5.sp) {
    val agent = LocalSshAgent.current ?: return
    if (!credential.agentEnabled || !agent.enabled) return
    Badge(stringResource(Res.string.vault_badge_agent), bg = D.amber.copy(alpha = 0.16f), fg = D.amber, radius = 3, size = size)
}
