package app.skerry.ui.teams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.ui.design.Card
import app.skerry.ui.design.KeyValueRow
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_card_devices
import app.skerry.ui.generated.resources.lib_teams_card_devices_value
import app.skerry.ui.generated.resources.lib_teams_card_encryption
import app.skerry.ui.generated.resources.lib_teams_card_encryption_value
import app.skerry.ui.generated.resources.lib_teams_card_endpoint
import app.skerry.ui.generated.resources.lib_teams_card_hosts
import app.skerry.ui.generated.resources.lib_teams_card_items
import app.skerry.ui.generated.resources.lib_teams_card_last_rekey
import app.skerry.ui.generated.resources.lib_teams_card_live
import app.skerry.ui.generated.resources.lib_teams_card_live_value
import app.skerry.ui.generated.resources.lib_teams_card_runbooks
import app.skerry.ui.generated.resources.lib_teams_card_server
import app.skerry.ui.generated.resources.lib_teams_card_shared
import app.skerry.ui.generated.resources.lib_teams_card_snippets
import app.skerry.ui.generated.resources.lib_teams_card_storage
import app.skerry.ui.generated.resources.lib_teams_card_storage_value
import app.skerry.ui.generated.resources.lib_teams_card_vault
import app.skerry.ui.generated.resources.lib_teams_card_version
import org.jetbrains.compose.resources.stringResource

/** Which list a "Shared with the team" row opens. */
internal enum class TeamSharedView { HOSTS, SNIPPETS, RUNBOOKS, LIVE }

/**
 * The three facts panels under the member table: what the shared vault holds, which server it lives
 * on, and what the team has put in it. The counts are the way into the lists — the records
 * themselves (with their share/unshare/history actions) open from these rows.
 *
 * Fields the server hasn't told us (an old instance, an unreachable one) show [NO_VALUE] rather than
 * a plausible-looking zero.
 */
internal data class TeamCards(
    val items: Int,
    val lastRekeyAt: Long?,
    val endpoint: String?,
    val serverVersion: String?,
    val devices: Int?,
    val hosts: Int,
    val snippets: Int,
    val runbooks: Int,
    val liveSessions: Int,
)

/** The three cards side by side — the desktop screen, where there is room for a row of them. */
@Composable
internal fun TeamSummaryCards(cards: TeamCards, onOpen: (TeamSharedView) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VaultCard(cards, Modifier.weight(1f))
        ServerCard(cards, Modifier.weight(1f))
        SharedCard(cards, onOpen, Modifier.weight(1f))
    }
}

/** The same three cards stacked — the phone, where a row of them would be unreadable. */
@Composable
internal fun TeamSummaryCardsStacked(cards: TeamCards, onOpen: ((TeamSharedView) -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VaultCard(cards, Modifier.fillMaxWidth())
        ServerCard(cards, Modifier.fillMaxWidth())
        SharedCard(cards, onOpen, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VaultCard(cards: TeamCards, modifier: Modifier) {
    Card(modifier, title = stringResource(Res.string.lib_teams_card_vault)) {
        KeyValueRow(stringResource(Res.string.lib_teams_card_items), cards.items.toString())
        KeyValueRow(stringResource(Res.string.lib_teams_card_encryption), stringResource(Res.string.lib_teams_card_encryption_value))
        KeyValueRow(
            stringResource(Res.string.lib_teams_card_last_rekey),
            cards.lastRekeyAt?.let { formatEpochUtc(it).substringBefore(' ') } ?: NO_VALUE,
        )
    }
}

@Composable
private fun ServerCard(cards: TeamCards, modifier: Modifier) {
    Card(modifier, title = stringResource(Res.string.lib_teams_card_server)) {
        KeyValueRow(stringResource(Res.string.lib_teams_card_endpoint), cards.endpoint ?: NO_VALUE)
        KeyValueRow(stringResource(Res.string.lib_teams_card_storage), stringResource(Res.string.lib_teams_card_storage_value))
        // The version string is whatever the server says it is — bounded and stripped like every other
        // field it authors, or it reverses the label beside it.
        KeyValueRow(
            stringResource(Res.string.lib_teams_card_version),
            cards.serverVersion?.takeIf { it.isNotBlank() }?.let { untrustedLabel(it) }?.takeIf { it.isNotBlank() } ?: NO_VALUE,
        )
        KeyValueRow(
            stringResource(Res.string.lib_teams_card_devices),
            cards.devices?.let { stringResource(Res.string.lib_teams_card_devices_value, it) } ?: NO_VALUE,
        )
    }
}

@Composable
private fun SharedCard(cards: TeamCards, onOpen: ((TeamSharedView) -> Unit)?, modifier: Modifier) {
    Card(modifier, title = stringResource(Res.string.lib_teams_card_shared)) {
        KeyValueRow(
            stringResource(Res.string.lib_teams_card_hosts),
            cards.hosts.toString(),
            modifier = openModifier(onOpen, TeamSharedView.HOSTS),
        )
        KeyValueRow(
            stringResource(Res.string.lib_teams_card_snippets),
            cards.snippets.toString(),
            modifier = openModifier(onOpen, TeamSharedView.SNIPPETS),
        )
        KeyValueRow(
            stringResource(Res.string.lib_teams_card_runbooks),
            cards.runbooks.toString(),
            modifier = openModifier(onOpen, TeamSharedView.RUNBOOKS),
        )
        KeyValueRow(
            stringResource(Res.string.lib_teams_card_live),
            stringResource(Res.string.lib_teams_card_live_value, cards.liveSessions),
            modifier = openModifier(onOpen, TeamSharedView.LIVE),
        )
    }
}

/** A count is clickable only where clicking it opens something — see [TeamSummaryCardsStacked]. */
private fun openModifier(onOpen: ((TeamSharedView) -> Unit)?, view: TeamSharedView): Modifier =
    if (onOpen == null) Modifier else Modifier.clickable { onOpen(view) }
