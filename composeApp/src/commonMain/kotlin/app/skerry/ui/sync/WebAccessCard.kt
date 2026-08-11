package app.skerry.ui.sync

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.sync.MAX_WEB_PASSWORD_LENGTH
import app.skerry.shared.sync.MIN_WEB_PASSWORD_LENGTH
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.web_access_announce
import app.skerry.ui.generated.resources.web_access_cancel
import app.skerry.ui.generated.resources.web_access_change
import app.skerry.ui.generated.resources.web_access_checking
import app.skerry.ui.generated.resources.web_access_desc
import app.skerry.ui.generated.resources.web_access_field_new
import app.skerry.ui.generated.resources.web_access_field_repeat
import app.skerry.ui.generated.resources.web_access_mismatch
import app.skerry.ui.generated.resources.web_access_not_connected
import app.skerry.ui.generated.resources.web_access_open
import app.skerry.ui.generated.resources.web_access_remove
import app.skerry.ui.generated.resources.web_access_remove_body
import app.skerry.ui.generated.resources.web_access_remove_title
import app.skerry.ui.generated.resources.web_access_save
import app.skerry.ui.generated.resources.web_access_set
import app.skerry.ui.generated.resources.web_access_state_off
import app.skerry.ui.generated.resources.web_access_state_on
import app.skerry.ui.generated.resources.web_access_state_unknown
import app.skerry.ui.generated.resources.web_access_title
import app.skerry.ui.generated.resources.web_access_too_long
import app.skerry.ui.generated.resources.web_access_too_short
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/**
 * Web access card (Settings → Sync on desktop, the Sync screen on mobile — one composable, so the
 * two platforms cannot drift). Sets, rotates and removes the **web password**: the credential that
 * opens the account page in a browser, and the only way in there — until it is set, `/account`
 * refuses every sign-in and the page itself says to come here.
 *
 * It is not the master password and never becomes one: the server serving that page is the server
 * the master password protects. A browser signed in with it reads metadata and can revoke a device;
 * it cannot decrypt a record.
 *
 * Shown only on an active session ([SyncStatus.Online]) — the change travels over this device's own
 * token.
 */
@Composable
fun WebAccessCard(sync: SyncCoordinator, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    // null while the state is still being read; the answer is itself nullable — see webAccessEnabled.
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var checked by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(WebPasswordForm()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WebAccessChange?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }

    LaunchedEffect(sync, reload) {
        checked = false
        try {
            enabled = sync.webAccessEnabled()
        } finally {
            checked = true // never strand the card on "Checking…", whichever way the read ends
        }
    }

    val submit = {
        if (form.canSubmit && !busy) {
            busy = true
            result = null
            // The typed value leaves the composable as a CharArray the coordinator wipes; the String
            // behind it is dropped here so a recomposition can't re-read it.
            val typed = form.password.toCharArray()
            form = WebPasswordForm()
            scope.launch {
                val r = try {
                    sync.setWebPassword(typed)
                } finally {
                    busy = false
                }
                result = r
                if (r is WebAccessChange.Success) {
                    editing = false
                    // Same snapshot as the bump: `enabled` still holds the pre-change answer, and the
                    // announcer below would say the state the user just left (see its comment).
                    checked = false
                    reload++
                }
            }
            Unit
        }
    }

    // The section label is the caller's: desktop and mobile each have their own, and a third style
    // in the middle of the Sync screen would read as a different kind of section.
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
    ) {
        WebAccessHeader(enabled, checked)
        // Setting, rotating or removing the password succeeds silently: the form collapses and the state
        // line above flips, which a sighted user sees and nobody else does. The state line itself is not a
        // live region on purpose — it settles from "Checking…" every time the card is opened, and that
        // would be chatter — so the confirmation is announced here, and only after an action (issue #244).
        // `checked` is the gate that keeps it truthful: the action clears it in the same snapshot that asks
        // for the re-read, so nothing is spoken until `enabled` is the answer from after the change.
        StatusAnnouncer(
            if (result is WebAccessChange.Success && checked) {
                stringResource(Res.string.web_access_announce, webAccessStateText(enabled, checked))
            } else {
                ""
            },
        )
        // The address to open, printed verbatim: the page lives on the user's own server, and a
        // paraphrase ("your sync server") is not something you can type into a browser. Null with no
        // server configured, which is what leaves both the link and the Open button out.
        //
        // Address and opener are remembered together, not rebuilt per recomposition: savedConfig
        // reads and parses the config file, this body re-runs on every keystroke in the password
        // field, and a fresh lambda each time would keep the buttons below from ever being skipped.
        // A failing system handler must not throw into the composition (see AboutSection).
        val account: Pair<String, () -> Unit>? = remember(sync, uriHandler) {
            accountPageUrl(sync.savedConfig?.serverUrl)?.let { url ->
                url to { runCatching { uriHandler.openUri(url) }; Unit }
            }
        }
        account?.let { (url, open) -> WebAccessUrl(url, open) }
        Txt(
            stringResource(Res.string.web_access_desc),
            color = Skerry.colors.faint, size = 11.5.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (editing) {
            WebAccessFields(form, busy, onChange = { form = it; result = null })
        }
        WebAccessMessage(form, result)
        WebAccessActions(
            enabled = enabled,
            checked = checked,
            editing = editing,
            busy = busy,
            canSubmit = form.canSubmit,
            onOpen = account?.second,
            onSubmit = submit,
            onEdit = { editing = true; result = null },
            onCancel = { editing = false; form = WebPasswordForm(); result = null },
            onRemove = { confirmingRemove = true },
        )
    }

    if (confirmingRemove) {
        ConfirmActionDialog(
            title = stringResource(Res.string.web_access_remove_title),
            message = stringResource(Res.string.web_access_remove_body),
            confirmLabel = stringResource(Res.string.web_access_remove),
            onConfirm = {
                confirmingRemove = false
                busy = true
                result = null
                scope.launch {
                    val r = try {
                        sync.clearWebPassword()
                    } finally {
                        busy = false
                    }
                    result = r
                    if (r is WebAccessChange.Success) {
                        editing = false
                        checked = false
                        reload++
                    }
                }
            },
            onDismiss = { confirmingRemove = false },
        )
    }
}

/** The state line: what the card exists to report, in one word. */
@Composable
private fun webAccessStateText(enabled: Boolean?, checked: Boolean): String = when {
    !checked -> stringResource(Res.string.web_access_checking)
    enabled == true -> stringResource(Res.string.web_access_state_on)
    enabled == false -> stringResource(Res.string.web_access_state_off)
    // Unknown is its own state and not "off": an account may well have web access on, this device
    // just couldn't read it.
    else -> stringResource(Res.string.web_access_state_unknown)
}

/** Icon, name, and the one fact the card exists to report: whether web access is on. */
@Composable
private fun WebAccessHeader(enabled: Boolean?, checked: Boolean) {
    val stateText = webAccessStateText(enabled, checked)
    val stateColor = when {
        !checked -> Skerry.colors.faint
        enabled == true -> Skerry.colors.moss
        enabled == false -> Skerry.colors.faint
        else -> Skerry.colors.amber
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Sym("public", size = 20.sp, color = Skerry.colors.cyanBright)
        Txt(
            stringResource(Res.string.web_access_title),
            color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f),
        )
        Txt(stateText, color = stateColor, size = 11.5.sp)
    }
}

/**
 * The account page's address on the configured server, or null when there is no server to point at.
 * The server URL is whatever was typed into the setup field, so a trailing slash is ordinary input
 * and `//account` is a different path to a browser than `/account`.
 */
internal fun accountPageUrl(serverUrl: String?): String? =
    serverUrl?.trim()?.trimEnd('/')?.ifEmpty { null }?.let { "$it/account" }

/**
 * The account page's address, in the monospace face every literal value on screen uses, and the link
 * itself: the whole row opens the page, which is the shortest path from reading the address to being
 * on it.
 */
@Composable
private fun WebAccessUrl(url: String, onOpen: () -> Unit) {
    val mono = LocalFonts.current.mono
    Txt(
        url,
        color = Skerry.colors.cyanBright, size = 11.5.sp, font = mono,
        modifier = Modifier.padding(top = 8.dp).clickable(onClick = onOpen),
    )
}

/** New password + repeat. The repeat is not ceremony: a typo here is only found by failing to sign in. */
@Composable
private fun WebAccessFields(
    form: WebPasswordForm,
    busy: Boolean,
    onChange: (WebPasswordForm) -> Unit,
) {
    SyncFormField(stringResource(Res.string.web_access_field_new)) {
        SyncTextField(form.password, "••••••••", KeyboardType.Password, masked = true, icon = "key") {
            if (!busy) onChange(form.copy(password = it))
        }
    }
    SyncFormField(stringResource(Res.string.web_access_field_repeat)) {
        SyncTextField(form.confirm, "••••••••", KeyboardType.Password, masked = true, icon = "key") {
            if (!busy) onChange(form.copy(confirm = it))
        }
    }
}

/** Whichever of the two has something to say: the last server answer, else the input rule broken now. */
@Composable
private fun WebAccessMessage(form: WebPasswordForm, result: WebAccessChange?) {
    val message: String? = when (result) {
        is WebAccessChange.NotConnected -> stringResource(Res.string.web_access_not_connected)
        is WebAccessChange.Failed -> syncFailureText(SyncStatus.Failed(result.reason, result.detail))
        // Success leaves no message — the state line above says Enabled, and a second sentence
        // congratulating the user on it would say nothing the card doesn't already show.
        is WebAccessChange.Success, null -> when {
            form.tooShort -> stringResource(Res.string.web_access_too_short, MIN_WEB_PASSWORD_LENGTH)
            form.tooLong -> stringResource(Res.string.web_access_too_long, MAX_WEB_PASSWORD_LENGTH)
            form.mismatch -> stringResource(Res.string.web_access_mismatch)
            else -> null
        }
    }
    SyncFormError(message)
}

/**
 * Open / Set / Change / Remove, or Save / Cancel while editing. Open is null when no server is
 * configured — there is nothing to point a browser at. Remove is offered only for a state read as
 * on: a button that clears a password nobody set does nothing and says nothing.
 */
@Composable
private fun WebAccessActions(
    enabled: Boolean?,
    checked: Boolean,
    editing: Boolean,
    busy: Boolean,
    canSubmit: Boolean,
    onOpen: (() -> Unit)?,
    onSubmit: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    if (!checked) return
    Row(
        Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            PrimaryButton(stringResource(Res.string.web_access_save), onClick = onSubmit, enabled = canSubmit && !busy, modifier = Modifier.testTag(UiTags.FORM_SAVE))
            GhostButton(stringResource(Res.string.web_access_cancel), onClick = onCancel, fg = Skerry.colors.dim)
        } else {
            // First in the row and offered whichever way the state read went: with no password set
            // the page still opens and says to come back here, which is the answer to "what now".
            onOpen?.let {
                GhostButton(
                    stringResource(Res.string.web_access_open),
                    onClick = it,
                    icon = "open_in_new",
                    fg = Skerry.colors.cyanBright,
                    border = Skerry.colors.cyanBright.copy(alpha = 0.4f),
                )
            }
            // Gated on busy like the Save above: the removal dialog closes the moment it is
            // confirmed, so without this both buttons are live again while that request is still in
            // flight, and a second tap starts a concurrent change with no defined winner.
            GhostButton(
                stringResource(if (enabled == true) Res.string.web_access_change else Res.string.web_access_set),
                onClick = onEdit,
                icon = "key",
                enabled = !busy,
                modifier = Modifier.testTag(UiTags.FORM_EDIT),
            )
            if (enabled == true) {
                GhostButton(
                    stringResource(Res.string.web_access_remove),
                    onClick = onRemove,
                    fg = Skerry.colors.sunset,
                    border = Skerry.colors.sunset.copy(alpha = 0.4f),
                    enabled = !busy,
                )
            }
        }
    }
}
