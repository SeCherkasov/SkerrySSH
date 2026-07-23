package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.shared.team.TeamRole
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_invite_role
import app.skerry.ui.generated.resources.lib_teams_create_subtitle
import app.skerry.ui.generated.resources.lib_teams_create_title
import app.skerry.ui.generated.resources.lib_teams_invite_account_placeholder
import app.skerry.ui.generated.resources.lib_teams_invite_fingerprint
import app.skerry.ui.generated.resources.lib_teams_invite_next
import app.skerry.ui.generated.resources.lib_teams_invite_send
import app.skerry.ui.generated.resources.lib_teams_invite_subtitle
import app.skerry.ui.generated.resources.lib_teams_invite_title
import app.skerry.ui.generated.resources.lib_teams_invite_verify
import app.skerry.ui.generated.resources.lib_teams_name_placeholder
import app.skerry.ui.generated.resources.lib_teams_your_fingerprint
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_create
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/** Share-picker item (a host or snippet from the own vault): [detail] is the second line (address/command). */
data class ShareItem(val id: String, val label: String, val detail: String)

/** Teams dialog card — same visual language as [app.skerry.ui.design.ConfirmActionDialog]. */
@Composable
internal fun TeamsDialogCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) { content() }
    }
}

/** Hand-rolled dialog field: border in decorationBox + fillMaxWidth (a click anywhere places the caret). */
@Composable
private fun TeamsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(Skerry.colors.cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(Skerry.colors.card)
                    .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 13.sp)
                    inner()
                }
            }
        },
    )
}

/** Create team: a single name field; the team key is generated locally (the caption notes this). */
@Composable
fun CreateTeamDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        if (name.trim().isNotEmpty()) onCreate(name.trim())
    }
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_create_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(stringResource(Res.string.lib_teams_create_subtitle), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        TeamsTextField(name, { name = it }, stringResource(Res.string.lib_teams_name_placeholder), ::save, focus)
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
 * Two-step invite: enter accountId → [onLookup] fetches the published key; once [preview] arrives,
 * show the fingerprint for verification over a trusted channel and only then allow sending. Changing
 * the entered id clears the preview via [onEdited] (can't send to an unverified key).
 */
@Composable
fun InviteMemberDialog(
    preview: InvitePreview?,
    ownFingerprint: String?,
    busy: Boolean,
    assignableRoles: List<TeamRole>,
    onLookup: (String) -> Unit,
    onEdited: () -> Unit,
    onSend: (String, TeamRole) -> Unit,
    onDismiss: () -> Unit,
) {
    var accountId by remember { mutableStateOf("") }
    // Least-privilege by default: the lowest assignable role (usually VIEWER).
    var role by remember { mutableStateOf(assignableRoles.lastOrNull() ?: TeamRole.VIEWER) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val mono = LocalFonts.current.mono
    val ready = preview != null && preview.accountId == accountId.trim()
    fun submit() {
        val id = accountId.trim()
        if (id.isEmpty() || busy) return
        if (ready) onSend(id, role) else onLookup(id)
    }
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_invite_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(stringResource(Res.string.lib_teams_invite_subtitle), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        TeamsTextField(accountId, { accountId = it; onEdited() }, stringResource(Res.string.lib_teams_invite_account_placeholder), ::submit, focus)
        if (preview != null && ready) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Skerry.colors.cyan.copy(alpha = 0.06f))
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                    .padding(12.dp),
            ) {
                Txt(stringResource(Res.string.lib_teams_invite_fingerprint).uppercase(), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Txt(preview.fingerprint, color = Skerry.colors.cyanBright, size = 14.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
                Txt(stringResource(Res.string.lib_teams_invite_verify), color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
                if (ownFingerprint != null) {
                    Txt(stringResource(Res.string.lib_teams_your_fingerprint, ownFingerprint), color = Skerry.colors.faint, size = 11.sp, font = mono, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (assignableRoles.isNotEmpty()) {
            Txt(stringResource(Res.string.lib_teams_invite_role).uppercase(), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            RoleChips(assignableRoles, role) { role = it }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
            PrimaryButton(
                if (ready) stringResource(Res.string.lib_teams_invite_send) else stringResource(Res.string.lib_teams_invite_next),
                onClick = ::submit,
                enabled = accountId.trim().isNotEmpty() && !busy,
            )
        }
    }
}

/** Picker of an own-vault record to share with a team: already-shared records are filtered by the caller. */
@Composable
fun SharePickerDialog(
    title: String,
    items: List<ShareItem>,
    emptyText: String,
    onPick: (ShareItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    TeamsDialogCard(onDismiss) {
        Txt(title, color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, modifier = Modifier.padding(bottom = 14.dp))
        if (items.isEmpty()) {
            Txt(emptyText, color = Skerry.colors.dim, size = 12.5.sp)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(7.dp))
                            .clickable { onPick(item) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Txt(item.label, color = Skerry.colors.textBright, size = 12.5.sp, modifier = Modifier.weight(1f))
                        Txt(item.detail, color = Skerry.colors.faint, size = 11.sp, font = mono)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
        }
    }
}
