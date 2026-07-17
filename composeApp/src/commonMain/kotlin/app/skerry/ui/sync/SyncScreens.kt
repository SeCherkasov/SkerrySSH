package app.skerry.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_back
import app.skerry.ui.generated.resources.sync_connect
import app.skerry.ui.generated.resources.sync_connecting
import app.skerry.ui.generated.resources.sync_connecting_sub
import app.skerry.ui.generated.resources.sync_field_account
import app.skerry.ui.generated.resources.sync_field_choose_password
import app.skerry.ui.generated.resources.sync_field_master_password
import app.skerry.ui.generated.resources.sync_field_pairing_code
import app.skerry.ui.generated.resources.sync_field_repeat_password
import app.skerry.ui.generated.resources.sync_field_server_url
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.generated.resources.sync_join_desc
import app.skerry.ui.generated.resources.sync_join_title
import app.skerry.ui.generated.resources.sync_keep_connected
import app.skerry.ui.generated.resources.sync_keep_connected_sub
import app.skerry.ui.generated.resources.sync_link_this_device
import app.skerry.ui.generated.resources.sync_passwords_mismatch
import app.skerry.ui.generated.resources.sync_placeholder_account
import app.skerry.ui.generated.resources.sync_account_email_hint
import app.skerry.ui.generated.resources.sync_placeholder_master_password
import app.skerry.ui.generated.resources.sync_placeholder_min_chars
import app.skerry.ui.generated.resources.sync_placeholder_pairing_code
import app.skerry.ui.generated.resources.sync_placeholder_repeat
import app.skerry.ui.generated.resources.sync_placeholder_server_url
import app.skerry.ui.generated.resources.sync_scan_qr
import app.skerry.ui.generated.resources.sync_setup_desc
import app.skerry.ui.generated.resources.sync_setup_title
import app.skerry.ui.generated.resources.sync_storage_choice_desc
import app.skerry.ui.generated.resources.sync_storage_choice_title
import app.skerry.ui.generated.resources.sync_storage_local_sub
import app.skerry.ui.generated.resources.sync_storage_local_title
import app.skerry.ui.generated.resources.sync_storage_server_sub
import app.skerry.ui.generated.resources.sync_storage_server_title
import app.skerry.ui.generated.resources.sync_switch_later
import app.skerry.ui.generated.resources.sync_zero_knowledge_account
import app.skerry.ui.generated.resources.sync_zero_knowledge_password
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.sync.qr.QrScannerScreen
import app.skerry.ui.sync.qr.qrScannerAvailable
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/**
 * Onboarding step right after vault creation, before biometric enroll
 * (see [app.skerry.ui.vault.VaultGateState.OfferSync]). First ask how to store data: locally (this
 * device only) or via self-hosted sync — and only in the second case show the server params form.
 * Choosing "local" finishes the step ([onDone]); connecting sync may adopt the account dataKey, so
 * biometrics must be wrapped under the final key — success ([SyncStatus.Online]) also leads to
 * [onDone], with server data pulled right there. Shared screen for both platforms.
 */
@Composable
fun SyncOnboardingScreen(sync: SyncCoordinator, onDone: () -> Unit) {
    // collectAsState (not ...WithLifecycle): this screen is commonMain (desktop too), and lifecycle-aware
    // collection exists only on Android. Collecting in STOPPED is harmless here: auto-lock on ON_STOP
    // locks the vault → controller.state goes to NeedsUnlock → key(state) tears down this subtree and cancels collection.
    val status by sync.status.collectAsState()
    val currentOnDone by rememberUpdatedState(onDone)

    // A successful connection finishes the onboarding step. dropWhile + first: wait for the transition
    // into Online, not a stale value (after a reset the coordinator kills the session asynchronously —
    // Online could still be set on entry). One-shot: Online is a data class with changing
    // lastPushed/lastPulled, a repeat runSync must not fire onDone twice. List managers are already reread (onSynced).
    LaunchedEffect(Unit) {
        sync.status.dropWhile { it is SyncStatus.Online }.first { it is SyncStatus.Online }
        currentOnDone()
    }

    // If the link already has a saved server (Configured / after restart — only a password needed), the
    // "local/sync" choice is moot: skip the fork and open the form directly.
    var showSyncForm by remember { mutableStateOf(sync.savedConfig != null) }

    // fillMaxSize scroll container over the background; content centered vertically and width-constrained.
    // Center (not TopCenter): when the form is shorter than the screen it's centered (large desktop
    // window, phone), longer (keyboard) it scrolls. verticalScroll measures the column by its height.
    Box(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 22.dp, vertical = 32.dp)) {
            if (!showSyncForm) {
                SyncStorageChoice(
                    onLocal = onDone,
                    onSync = { showSyncForm = true },
                )
            } else {
                // Back leads to the fork — the "changed my mind" path: on the fork "Local encrypted
                // storage" finishes onboarding, so a separate Skip button isn't needed.
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { showSyncForm = false }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Sym("chevron_left", size = 20.sp, color = D.dim)
                    Txt(stringResource(Res.string.sync_back), color = D.dim, size = 13.sp)
                }
                Spacer(Modifier.height(10.dp))

                Txt(stringResource(Res.string.sync_setup_title), color = D.text, size = 22.sp, weight = FontWeight.Bold)
                Txt(
                    stringResource(Res.string.sync_setup_desc),
                    color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )

                // The form stays composed in every status. The Busy branch used to replace it,
                // which destroyed its remember state: after a failed connect (server down, typo
                // in the URL) the typed server/account came back blank. Busy now only adds the
                // notice on top and disables submit — same pattern as SyncSetupDialog.
                val busy = status == SyncStatus.Busy
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    SyncStatusNotice("sync", D.cyanBright, stringResource(Res.string.sync_connecting), stringResource(Res.string.sync_connecting_sub))
                }
                SyncSetupBody(
                    sync,
                    errorMessage = (status as? SyncStatus.Failed)?.let { syncFailureText(it) },
                    busy = busy,
                )
            }
        }
    }
}

/**
 * Storage fork in onboarding: two card buttons. "Local encrypted storage" finishes the step
 * ([onLocal]); "Self-hosted sync server" expands the params form ([onSync]). The choice can be changed
 * later in settings — both options are reversible, so this doesn't commit anything.
 */
@Composable
private fun SyncStorageChoice(onLocal: () -> Unit, onSync: () -> Unit) {
    Txt(
        stringResource(Res.string.sync_storage_choice_title),
        color = D.text, size = 22.sp, weight = FontWeight.Bold, lineHeight = 28.sp,
    )
    Txt(
        stringResource(Res.string.sync_storage_choice_desc),
        color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
    )

    SyncChoiceCard(
        icon = "lock",
        iconColor = D.moss,
        title = stringResource(Res.string.sync_storage_local_title),
        subtitle = stringResource(Res.string.sync_storage_local_sub),
        onClick = onLocal,
    )
    Spacer(Modifier.height(12.dp))
    SyncChoiceCard(
        icon = "cloud_sync",
        iconColor = D.cyanBright,
        title = stringResource(Res.string.sync_storage_server_title),
        subtitle = stringResource(Res.string.sync_storage_server_sub),
        onClick = onSync,
    )

    Row(
        Modifier.padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("shield_lock", size = 14.sp, color = D.moss)
        Txt(stringResource(Res.string.sync_switch_later), color = D.faint, size = 11.5.sp)
    }
}

/** Storage-fork choice card: icon in a panel, title/subtitle, chevron affordance. */
@Composable
private fun SyncChoiceCard(
    icon: String,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(D.surfaceDeep)
            .border(1.dp, D.cyan14, RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Sym(icon, size = 22.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
            Txt(subtitle, color = D.faint, size = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Sym("chevron_right", size = 22.sp, color = D.dim)
    }
}

/**
 * Sync server connection form (server + accountId + master password + keep connected). Shared by
 * onboarding and the Sync screen: the password goes to [SyncCoordinator] as a CharArray and is wiped there.
 * [busy] — a connect is in flight: submit is disabled (fields stay editable, like [SyncSetupDialog]).
 * Callers must keep the form composed during Busy — leaving composition wipes the typed fields.
 */
@Composable
internal fun SyncSetupBody(
    sync: SyncCoordinator,
    errorMessage: String?,
    busy: Boolean = false,
) {
    // Prefill from the saved link (Configured after restart): only the password is needed.
    val saved = remember { sync.savedConfig }
    var serverUrl by remember { mutableStateOf(saved?.serverUrl ?: SyncSetupForm.DEFAULT_SERVER_URL) }
    var account by remember { mutableStateOf(saved?.accountId ?: "") }
    var password by remember { mutableStateOf("") }
    var keepConnected by remember { mutableStateOf(saved?.keepConnected ?: true) }

    val form = SyncSetupForm(serverUrl, account)
    val canSubmit = form.canSubmit(password.length) && !busy

    SyncFieldLabel(stringResource(Res.string.sync_field_server_url))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            SyncTextField(serverUrl, stringResource(Res.string.sync_placeholder_server_url), KeyboardType.Uri, icon = "dns") { serverUrl = it }
        }
        if (serverUrl.trim() != SyncSetupForm.DEFAULT_SERVER_URL) {
            Sym("close", size = 18.sp, color = D.faint, modifier = Modifier.padding(start = 6.dp).clickable { serverUrl = SyncSetupForm.DEFAULT_SERVER_URL })
        }
    }
    SyncFieldLabel(stringResource(Res.string.sync_field_account))
    SyncTextField(account, stringResource(Res.string.sync_placeholder_account), KeyboardType.Email, icon = "person") { account = it }
    if (account.isNotEmpty() && !form.isAccountEmail) {
        Txt(stringResource(Res.string.sync_account_email_hint), color = D.amber, size = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
    SyncFieldLabel(stringResource(Res.string.sync_field_master_password))
    SyncTextField(password, stringResource(Res.string.sync_placeholder_master_password), KeyboardType.Password, masked = true, icon = "key") { password = it }

    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clickable { keepConnected = !keepConnected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (keepConnected) D.cyan else Color.Transparent)
                .border(1.dp, if (keepConnected) D.cyan else D.cyan14, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (keepConnected) Sym("check", size = 14.sp, color = Color(0xFF0A1A26))
        }
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.sync_keep_connected), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.sync_keep_connected_sub), color = D.faint, size = 11.5.sp)
        }
    }

    // http:// is allowed (local test/LAN without a TLS proxy) but defenseless against MITM — warn explicitly.
    if (form.isInsecureUrl) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("warning", size = 14.sp, color = D.sunset)
            Txt(stringResource(Res.string.sync_insecure_url_warning), color = D.sunset, size = 11.5.sp, lineHeight = 15.sp)
        }
    }

    if (errorMessage != null) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(errorMessage, color = D.sunset, size = 12.sp)
        }
    }

    PrimaryButton(
        stringResource(Res.string.sync_connect),
        onClick = {
            if (!canSubmit) return@PrimaryButton
            val pw = password.toCharArray() // the coordinator wipes the array
            password = ""
            val url = form.normalizedServerUrl
            val acc = form.normalizedAccountId
            // The coordinator owns the launch (its own scope) — a coroutine tied to this composable
            // would cancel mid-flight if the form leaves composition (navigation away). One call:
            // the coordinator decides register vs login.
            sync.connect(url, acc, pw, keepConnected)
        },
        modifier = Modifier.padding(top = 18.dp),
        enabled = canSubmit,
        bg = if (canSubmit) D.cyan else D.cyan.copy(alpha = 0.4f),
        icon = "cloud_sync",
    )
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym("shield_lock", size = 14.sp, color = D.moss)
        Txt(stringResource(Res.string.sync_zero_knowledge_password), color = D.faint, size = 11.sp)
    }
}

/**
 * Screen to link this device by code (quick pairing, variant B) — shown from the vault creation screen
 * ([app.skerry.ui.vault.VaultGateState.NeedsCreate]). The password is entered once:
 * [SyncCoordinator.claimPairing] creates the local vault under it and adopts the account key, so
 * there's no re-entry or password desync. Shared screen for desktop+mobile. [onBack] returns to the
 * creation form; success ([SyncStatus.Online]) leads to [onDone] (the gate moves to the biometrics offer).
 */
@Composable
fun PairingJoinScreen(sync: SyncCoordinator, onBack: () -> Unit, onDone: () -> Unit) {
    val status by sync.status.collectAsState()
    // onDone is created inline in the gate (a new instance per recomposition) — keep a fresh ref so the
    // long-lived LaunchedEffect(Unit) doesn't call a stale lambda.
    val currentOnDone by rememberUpdatedState(onDone)

    // The master password is entered here — protect the screen from Recent Apps snapshots ourselves,
    // not relying on the caller (on desktop SecureScreen is a no-op).
    SecureScreen()
    // System "back" = the visual Back arrow: return to the creation form, don't close the app.
    PlatformBackHandler(onBack = onBack)

    // Finish the step only on the transition into Online, not a stale value. status is a StateFlow, and
    // after a vault reset the coordinator kills the session asynchronously (disconnect in scope.launch):
    // entering here right after a reset we might see a not-yet-killed Online and fire onDone before
    // claimPairing even recreates the vault. dropWhile drops leading Online; wait for Online after our
    // claim (it sets Busy synchronously). One-shot: Online is a data class with counters, no repeat needed.
    LaunchedEffect(Unit) {
        sync.status.dropWhile { it is SyncStatus.Online }.first { it is SyncStatus.Online }
        currentOnDone()
    }

    Box(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 22.dp, vertical = 32.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Sym("chevron_left", size = 20.sp, color = D.dim)
                Txt(stringResource(Res.string.sync_back), color = D.dim, size = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Txt(stringResource(Res.string.sync_join_title), color = D.text, size = 22.sp, weight = FontWeight.Bold)
            Txt(
                stringResource(Res.string.sync_join_desc),
                color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            SyncJoinBody(sync, errorMessage = (status as? SyncStatus.Failed)?.let { syncFailureText(it) })
        }
    }
}

/**
 * Fields to link a device by code: code (manual paste) + optional QR camera scan (only where
 * [qrScannerAvailable]) + a password with which [SyncCoordinator.claimPairing] creates the local vault
 * and wraps the adopted account key under it. The password is chosen here for the first time (no vault
 * yet), so it has the same minimum length as normal creation. Completion — the shared
 * `status.first { Online }` in [PairingJoinScreen] → onDone.
 */
@Composable
private fun SyncJoinBody(sync: SyncCoordinator, errorMessage: String?) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var keepConnected by remember { mutableStateOf(true) }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        QrScannerScreen(
            onResult = { code = it; showScanner = false },
            onCancel = { showScanner = false },
        )
        return
    }

    // The password creates a new vault and is unrecoverable — require confirmation (like the normal
    // creation form), else a typo would lock the device while the one-time code has already burned.
    val passwordsMatch = password == confirm
    val canSubmit = code.isNotBlank() && password.length >= MIN_MASTER_PASSWORD_LENGTH && passwordsMatch

    SyncFieldLabel(stringResource(Res.string.sync_field_pairing_code))
    SyncTextField(code, stringResource(Res.string.sync_placeholder_pairing_code), KeyboardType.Text, icon = "qr_code") { code = it }
    if (qrScannerAvailable) {
        GhostButton(stringResource(Res.string.sync_scan_qr), onClick = { showScanner = true }, icon = "photo_camera", modifier = Modifier.padding(top = 10.dp))
    }
    // The server URL is inside the pairing code, not typed here — warn when it decodes to http://
    // (same MITM exposure as the sync form's insecure-URL warning), so a QR pointing at a cleartext
    // server isn't accepted blind. Decode is memoized on `code` so a keystroke in the password/confirm
    // fields (which recompose this body) doesn't re-run it on the unchanged code.
    val codeIsInsecure = remember(code) { PairingPayload.isInsecureServerUrl(code) }
    if (codeIsInsecure) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("warning", size = 14.sp, color = D.sunset)
            Txt(stringResource(Res.string.sync_insecure_url_warning), color = D.sunset, size = 11.5.sp, lineHeight = 15.sp)
        }
    }
    SyncFieldLabel(stringResource(Res.string.sync_field_choose_password))
    SyncTextField(password, stringResource(Res.string.sync_placeholder_min_chars, MIN_MASTER_PASSWORD_LENGTH), KeyboardType.Password, masked = true, icon = "key") { password = it }
    SyncFieldLabel(stringResource(Res.string.sync_field_repeat_password))
    SyncTextField(confirm, stringResource(Res.string.sync_placeholder_repeat), KeyboardType.Password, masked = true, icon = "key") { confirm = it }
    if (confirm.isNotEmpty() && !passwordsMatch) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(stringResource(Res.string.sync_passwords_mismatch), color = D.sunset, size = 11.5.sp)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clickable { keepConnected = !keepConnected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (keepConnected) D.cyan else Color.Transparent)
                .border(1.dp, if (keepConnected) D.cyan else D.cyan14, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (keepConnected) Sym("check", size = 14.sp, color = Color(0xFF0A1A26))
        }
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.sync_keep_connected), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.sync_keep_connected_sub), color = D.faint, size = 11.5.sp)
        }
    }

    if (errorMessage != null) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(errorMessage, color = D.sunset, size = 12.sp)
        }
    }

    PrimaryButton(
        stringResource(Res.string.sync_link_this_device),
        onClick = {
            if (!canSubmit) return@PrimaryButton
            val pw = password.toCharArray() // the coordinator wipes the array
            password = ""
            confirm = ""
            sync.claimPairing(code.trim(), pw, keepConnected)
        },
        modifier = Modifier.padding(top = 18.dp),
        enabled = canSubmit,
        bg = if (canSubmit) D.cyan else D.cyan.copy(alpha = 0.4f),
        icon = "cloud_sync",
    )
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym("shield_lock", size = 14.sp, color = D.moss)
        Txt(stringResource(Res.string.sync_zero_knowledge_account), color = D.faint, size = 11.sp)
    }
}

/** Sync-flow status card (icon + title/subtitle) — shared by onboarding and the Sync screen. */
@Composable
internal fun SyncStatusNotice(icon: String, iconColor: Color, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).border(1.dp, D.cyan08, RoundedCornerShape(11.dp)).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, size = 22.sp, color = iconColor)
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SyncFieldLabel(text: String) {
    Txt(text, color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun SyncTextField(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    masked: Boolean = false,
    icon: String? = null,
    onChange: (String) -> Unit,
) {
    val ui = LocalFonts.current.ui
    val style = remember(ui) { TextStyle(color = D.text, fontSize = 15.sp, fontFamily = ui, lineHeight = 20.sp) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(D.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null) Sym(icon, size = 18.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}
