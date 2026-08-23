package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.TeamMember
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_scope_access_hint
import app.skerry.ui.generated.resources.lib_teams_scope_access_title
import app.skerry.ui.generated.resources.lib_teams_scope_access_unknown
import app.skerry.ui.generated.resources.lib_teams_scope_delete
import app.skerry.ui.generated.resources.lib_teams_scope_no_key
import app.skerry.ui.generated.resources.lib_teams_scope_access
import app.skerry.ui.generated.resources.lib_teams_scope_all
import app.skerry.ui.generated.resources.lib_teams_scope_create_hint
import app.skerry.ui.generated.resources.lib_teams_scope_create_title
import app.skerry.ui.generated.resources.lib_teams_scope_grant
import app.skerry.ui.generated.resources.lib_teams_scope_members_count
import app.skerry.ui.generated.resources.lib_teams_scope_name
import app.skerry.ui.generated.resources.lib_teams_scope_new
import app.skerry.ui.generated.resources.lib_teams_scope_revoke
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_create
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Scope selector: which share space of the team the records below belong to. The team-wide space is
 * always first — records without a scope stay visible to everyone, as they were before scopes
 * existed. A scope whose key never reached us is shown greyed out: a manager may see it listed and
 * delete it, but its records are ciphertext to them.
 *
 * Chips only. Creating a scope is a team action and lives in the screen's header beside Invite —
 * a button in this row is a different shape from everything around it.
 */
@Composable
private fun ScopeChipRow(
    scopes: List<TeamScopeUi>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScopeChip(stringResource(Res.string.lib_teams_scope_all), active = selected.isEmpty(), muted = false) { onSelect("") }
        scopes.forEach { scope ->
            ScopeChip(untrustedLabel(scope.name), active = selected == scope.id, muted = !scope.hasKey) { onSelect(scope.id) }
        }
    }
}

/** "New scope" as the screen headers carry it — one button, the same on desktop and on the phone. */
@Composable
internal fun NewScopeButton(onClick: () -> Unit, enabled: Boolean = true) {
    GhostButton(stringResource(Res.string.lib_teams_scope_new), onClick = onClick, icon = "add", enabled = enabled)
}

/**
 * The team's scope block: which space the records below belong to, plus the manager actions on the
 * selected scope. Shared verbatim by the desktop and the mobile team screen — the only thing that
 * differs between them is the section label above it, which each screen draws in its own style.
 */
@Composable
internal fun ScopeSection(
    scopes: List<TeamScopeUi>,
    selected: String,
    canManage: Boolean,
    onSelect: (String) -> Unit,
    onAccess: (TeamScopeUi) -> Unit,
    onDelete: (TeamScopeUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ScopeChipRow(scopes = scopes, selected = selected, onSelect = onSelect)
        val active = scopes.firstOrNull { it.id == selected } ?: return@Column
        if (canManage) {
            // Wraps rather than a Row: on a phone at a large text scale "Delete scope" is the button
            // that would land off the edge, and the column around it only scrolls vertically.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(stringResource(Res.string.lib_teams_scope_access), onClick = { onAccess(active) }, icon = "key")
                GhostButton(
                    stringResource(Res.string.lib_teams_scope_delete),
                    onClick = { onDelete(active) },
                    icon = "delete",
                    fg = Skerry.colors.sunset,
                    border = Skerry.colors.sunset.copy(alpha = 0.3f),
                )
            }
        }
        if (!active.hasKey) {
            Txt(
                stringResource(Res.string.lib_teams_scope_no_key),
                color = Skerry.colors.amber, size = 11.5.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ScopeChip(label: String, active: Boolean, muted: Boolean, onClick: () -> Unit) {
    val fg = when {
        active -> Skerry.colors.cyanBright
        muted -> Skerry.colors.faint
        else -> Skerry.colors.dim
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Skerry.colors.cyan10 else Color.Transparent)
            .border(1.dp, if (active) Skerry.colors.cyan14 else Skerry.colors.line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (muted) Sym("lock", size = 12.sp, color = fg)
        Txt(label, color = fg, size = 12.sp)
    }
}

/** Create a scope: a name only. Its key is generated locally and sealed to us, like a team's. */
@Composable
internal fun CreateScopeDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        if (name.trim().isNotEmpty()) onCreate(name.trim())
    }
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_scope_create_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(
            stringResource(Res.string.lib_teams_scope_create_hint),
            color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        TeamsTextField(name, { name = it }, stringResource(Res.string.lib_teams_scope_name), ::save, focus)
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
            PrimaryButton(stringResource(Res.string.shell_create), onClick = ::save, enabled = name.trim().isNotEmpty())
        }
    }
}

/**
 * One scope's access list as the dialog sees it. Loading and failed are separate states on purpose:
 * telling the user "the server did not answer" while the request is still in flight is a lie that
 * looks exactly like the truth.
 */
internal sealed interface ScopeAccess {
    data object Loading : ScopeAccess
    data class Known(val accounts: Set<String>) : ScopeAccess
    data object Unavailable : ScopeAccess
}

/**
 * Who may read a scope. Granting seals the scope key to the member, so only someone who holds the
 * key can hand it out — a manager without a grant sees the list but can't add to it ([canGrant]).
 */
@Composable
internal fun ScopeAccessDialog(
    scopeName: String,
    members: List<TeamMember>,
    granted: ScopeAccess,
    canGrant: Boolean,
    busy: Boolean,
    onGrant: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_scope_access_title, untrustedLabel(scopeName)), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(
            stringResource(Res.string.lib_teams_scope_access_hint),
            color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            members.forEach { member ->
                val has = granted is ScopeAccess.Known && member.accountId in granted.accounts
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(7.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Txt(untrustedLabel(member.accountId), color = if (has) Skerry.colors.textBright else Skerry.colors.dim, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
                    if (has) {
                        GhostButton(
                            stringResource(Res.string.lib_teams_scope_revoke),
                            onClick = { if (!busy) onRevoke(member.accountId) },
                            fg = Skerry.colors.sunset,
                            border = Skerry.colors.sunset.copy(alpha = 0.3f),
                        )
                    } else if (canGrant && granted is ScopeAccess.Known) {
                        GhostButton(stringResource(Res.string.lib_teams_scope_grant), onClick = { if (!busy) onGrant(member.accountId) })
                    }
                }
            }
        }
        when (granted) {
            is ScopeAccess.Known -> Txt(
                stringResource(Res.string.lib_teams_scope_members_count, granted.accounts.size),
                color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(top = 10.dp),
            )
            ScopeAccess.Unavailable -> Txt(
                stringResource(Res.string.lib_teams_scope_access_unknown),
                color = Skerry.colors.amber, size = 11.sp, modifier = Modifier.padding(top = 10.dp),
            )
            // Loading says nothing: the count and the failure line would both be guesses.
            ScopeAccess.Loading -> Unit
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
        }
    }
}
