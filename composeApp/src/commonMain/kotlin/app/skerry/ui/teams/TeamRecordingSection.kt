package app.skerry.ui.teams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.RemoteRecording
import app.skerry.shared.team.TeamScopeRef
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Txt
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_team_rec_authority
import app.skerry.ui.generated.resources.lib_team_rec_authority_confirm
import app.skerry.ui.generated.resources.lib_team_rec_authority_bootstrap
import app.skerry.ui.generated.resources.lib_team_rec_authority_unknown
import app.skerry.ui.generated.resources.lib_team_rec_owner_keys_required
import app.skerry.ui.generated.resources.lib_team_rec_delete
import app.skerry.ui.generated.resources.lib_team_rec_delete_confirm
import app.skerry.ui.generated.resources.lib_team_rec_delete_selected
import app.skerry.ui.generated.resources.lib_team_rec_empty
import app.skerry.ui.generated.resources.lib_team_rec_error
import app.skerry.ui.generated.resources.lib_team_rec_metadata
import app.skerry.ui.generated.resources.lib_team_rec_loading
import app.skerry.ui.generated.resources.lib_team_rec_mode_off
import app.skerry.ui.generated.resources.lib_team_rec_mode_optional
import app.skerry.ui.generated.resources.lib_team_rec_mode_required
import app.skerry.ui.generated.resources.lib_team_rec_more
import app.skerry.ui.generated.resources.lib_team_rec_play
import app.skerry.ui.generated.resources.lib_team_rec_retention
import app.skerry.ui.generated.resources.lib_team_rec_retry
import app.skerry.ui.generated.resources.lib_team_rec_save
import app.skerry.ui.generated.resources.lib_team_rec_select
import app.skerry.ui.generated.resources.lib_team_rec_selected
import app.skerry.ui.generated.resources.lib_team_rec_title
import app.skerry.ui.generated.resources.lib_team_rec_upload_failed
import app.skerry.ui.generated.resources.lib_team_rec_upload_pending
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val RECORDING_PAGE_SIZE = 50

/** Same owner controls and playback path on desktop and Android. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeamRecordingSection(tc: TeamsCoordinator, team: TeamUi, scopeId: String, tick: Int) {
    val ref = remember(team.id, scopeId) { TeamScopeRef(team.id, scopeId) }
    val owner = team.role == app.skerry.shared.team.TeamRole.OWNER
    val scope = rememberCoroutineScope()
    val playback = rememberTeamRecordingPlayback(tc, team.id)
    val uploadPending by tc.recordings.recordingUploadsPending.collectAsState()
    val uploadFailed by tc.recordings.recordingUploadError.collectAsState()
    var changed by remember(ref) { mutableIntStateOf(0) }
    var form by remember(ref) { mutableStateOf(RecordingPolicyForm()) }
    var authorityApproval by remember(ref) { mutableStateOf<RecordingAuthorityApproval?>(null) }
    var ownerKeysRequired by remember(ref) { mutableStateOf(false) }
    var policyError by remember(ref) { mutableStateOf(false) }
    var listError by remember(ref) { mutableStateOf(false) }
    var actionError by remember(ref) { mutableStateOf(false) }
    var listLoading by remember(ref) { mutableStateOf(true) }
    var busy by remember(ref) { mutableStateOf(false) }
    var recordings by remember(ref) { mutableStateOf(emptyList<RemoteRecording>()) }
    var hasMore by remember(ref) { mutableStateOf(false) }
    var selected by remember(ref) { mutableStateOf(emptySet<String>()) }
    var deleteIds by remember(ref) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(ref, tick, changed) {
        form = form.loadFailed()
        policyError = false
        ownerKeysRequired = false
        authorityApproval = null
        try { form = form.loaded(tc.recordings.recordingPolicy(ref)) }
        catch (e: CancellationException) { throw e }
        catch (e: RecordingAuthorityRequired) { authorityApproval = e.decision; policyError = true }
        catch (_: RecordingOwnerKeysRequired) { ownerKeysRequired = true; policyError = true }
        catch (_: Exception) { policyError = true }
    }
    LaunchedEffect(ref, tick, changed) {
        listLoading = true
        listError = false
        try {
            recordings = tc.recordings.listRecordings(ref, 0)
            hasMore = recordings.size == RECORDING_PAGE_SIZE
            selected = emptySet()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { listError = true }
        finally { listLoading = false }
    }
    val error = policyError || listError || actionError
    val errorText = when {
        ownerKeysRequired -> stringResource(Res.string.lib_team_rec_owner_keys_required)
        error -> stringResource(Res.string.lib_team_rec_error)
        else -> ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(stringResource(Res.string.lib_team_rec_title), color = Skerry.colors.text, size = 14.sp)
        StatusAnnouncer(errorText)
        if (uploadPending) Txt(stringResource(if (uploadFailed) Res.string.lib_team_rec_upload_failed
            else Res.string.lib_team_rec_upload_pending), color = Skerry.colors.amber, size = 12.sp)
        if (error) Txt(errorText, color = Skerry.colors.sunset, size = 12.sp)
        if (error || uploadFailed) {
            GhostButton(stringResource(Res.string.lib_team_rec_retry), onClick = {
                tc.recordings.retryRecordingUploads()
                actionError = false
                changed++
            }, enabled = !busy)
        }
        if (canEstablishLocalAuthority(owner, policyError, ownerKeysRequired, authorityApproval)) {
            GhostButton(stringResource(Res.string.lib_team_rec_authority_bootstrap), enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { authorityApproval = tc.recordings.requestOwnRecordingAuthority(ref) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { actionError = true }
                    finally { busy = false }
                }
            })
        }
        if (!form.loaded && !policyError) Txt(stringResource(Res.string.lib_team_rec_loading), color = Skerry.colors.dim, size = 12.sp)
        if (owner) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordingMode.entries.forEach { mode ->
                    GhostButton(mode.label(), onClick = { form = form.copy(mode = mode) },
                        modifier = Modifier.semantics { this.selected = mode == form.mode },
                        enabled = form.loaded && !busy,
                        border = if (mode == form.mode) Skerry.colors.cyan else Skerry.colors.lineStrong)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 7, 30, 90, 365).forEach { days ->
                    GhostButton(stringResource(Res.string.lib_team_rec_retention, days),
                        onClick = { form = form.copy(retentionDays = days) },
                        modifier = Modifier.semantics { this.selected = days == form.retentionDays },
                        enabled = form.loaded && !busy,
                        border = if (days == form.retentionDays) Skerry.colors.cyan else Skerry.colors.lineStrong)
                }
            }
            PrimaryButton(stringResource(Res.string.lib_team_rec_save), enabled = form.canSave && !busy, onClick = {
                val draft = form
                busy = true
                scope.launch {
                    try { tc.recordings.setRecordingPolicy(ref, draft.mode, draft.retentionDays); actionError = false; changed++ }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { actionError = true }
                    finally { busy = false }
                }
            })
        } else if (form.loaded) {
            Txt(form.mode.label(), color = Skerry.colors.dim, size = 12.sp)
        }
        if (listLoading) Txt(stringResource(Res.string.lib_team_rec_loading), color = Skerry.colors.dim, size = 12.sp)
        else if (!listError && recordings.isEmpty()) Txt(stringResource(Res.string.lib_team_rec_empty), color = Skerry.colors.dim, size = 12.sp)
        if (owner && selected.isNotEmpty()) GhostButton(stringResource(Res.string.lib_team_rec_delete_selected, selected.size),
            enabled = !busy, onClick = { deleteIds = selected.toList() })
        recordings.forEach { item ->
            val id = item.identity.recordingId
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(untrustedLabel(item.identity.hostId), color = Skerry.colors.dim, size = 12.sp)
                Txt(stringResource(Res.string.lib_team_rec_metadata, untrustedLabel(item.identity.actorId), item.durationSec),
                    color = Skerry.colors.faint, size = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton(stringResource(Res.string.lib_team_rec_play), enabled = !busy,
                        onClick = { playback.open(item.identity.ref.scopeId, id) })
                    if (owner) {
                        GhostButton(stringResource(if (id in selected) Res.string.lib_team_rec_selected else Res.string.lib_team_rec_select),
                            modifier = Modifier.semantics { this.selected = id in selected }, enabled = !busy,
                            onClick = { selected = if (id in selected) selected - id else selected + id })
                        GhostButton(stringResource(Res.string.lib_team_rec_delete), enabled = !busy, onClick = { deleteIds = listOf(id) })
                    }
                }
            }
        }
        if (hasMore) GhostButton(stringResource(Res.string.lib_team_rec_more), enabled = !listLoading && !busy, onClick = {
            listLoading = true
            scope.launch {
                try {
                    val page = tc.recordings.listRecordings(ref, recordings.size.toLong())
                    recordings = (recordings + page).distinctBy { it.identity.recordingId }
                    hasMore = page.size == RECORDING_PAGE_SIZE
                    listError = false
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { listError = true }
                finally { listLoading = false }
            }
        })
    }
    authorityApproval?.let { decision ->
        val old = decision.previous?.authority
        val unknown = stringResource(Res.string.lib_team_rec_authority_unknown)
        val previousPolicy = decision.previous?.let { previous ->
            previous.mode?.let { mode ->
                mode.label() + " · " + stringResource(Res.string.lib_team_rec_retention, previous.retentionDays)
            }
        } ?: unknown
        val candidatePolicy = decision.policy.mode.label() + " · " +
            stringResource(Res.string.lib_team_rec_retention, decision.policy.retentionDays)
        ConfirmActionDialog(title = stringResource(Res.string.lib_team_rec_authority),
            message = stringResource(Res.string.lib_team_rec_authority_confirm,
                untrustedLabel(old?.accountId ?: unknown), old?.fingerprint ?: unknown,
                untrustedLabel(decision.authority.accountId), decision.authority.fingerprint, previousPolicy, candidatePolicy),
            confirmLabel = stringResource(Res.string.lib_team_rec_authority),
            onDismiss = { authorityApproval = null }, onConfirm = {
                authorityApproval = null
                busy = true
                scope.launch {
                    try { tc.recordings.approveRecordingAuthority(decision); actionError = false; changed++ }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { actionError = true }
                    finally { busy = false }
                }
            })
    }
    deleteIds?.let { capturedIds ->
        ConfirmActionDialog(title = stringResource(Res.string.lib_team_rec_delete),
            message = stringResource(Res.string.lib_team_rec_delete_confirm, capturedIds.size),
            confirmLabel = stringResource(Res.string.lib_team_rec_delete),
            onDismiss = { deleteIds = null }, onConfirm = {
                deleteIds = null
                busy = true
                scope.launch {
                    try { tc.recordings.deleteRecordings(ref, capturedIds); actionError = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { actionError = true }
                    finally { busy = false; changed++ }
                }
            })
    }
}

private fun canEstablishLocalAuthority(
    owner: Boolean, policyError: Boolean, ownerKeysRequired: Boolean, approval: RecordingAuthorityApproval?,
): Boolean {
    if (!owner) return false
    return policyError && !ownerKeysRequired && approval == null
}

@Composable
private fun RecordingMode.label(): String = stringResource(when (this) {
    RecordingMode.OFF -> Res.string.lib_team_rec_mode_off
    RecordingMode.OPTIONAL -> Res.string.lib_team_rec_mode_optional
    RecordingMode.REQUIRED -> Res.string.lib_team_rec_mode_required
})
