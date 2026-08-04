package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.container.ContainerEntry
import app.skerry.shared.container.ContainerRuntime
import app.skerry.shared.ssh.SshAuth
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_container_browse
import app.skerry.ui.generated.resources.conn_container_empty
import app.skerry.ui.generated.resources.conn_container_hint
import app.skerry.ui.generated.resources.conn_container_loading
import app.skerry.ui.generated.resources.conn_field_container
import app.skerry.ui.generated.resources.conn_field_container_shell
import app.skerry.ui.generated.resources.conn_field_namespace
import app.skerry.ui.generated.resources.conn_field_pod
import app.skerry.ui.generated.resources.conn_field_pod_container
import app.skerry.ui.generated.resources.conn_field_runtime
import app.skerry.ui.generated.resources.conn_runtime_docker
import app.skerry.ui.generated.resources.conn_runtime_kubernetes
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.connection.ContainerBrowseController
import app.skerry.ui.connection.ContainerBrowseStatus
import app.skerry.ui.connection.containerBrowseFailureText
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.theme.Skerry

/**
 * Auth for the container listing probe, materialized at tap time (like the desktop modal's) so the
 * secret copy lives only for the probe. `null` — "Ask every time" or nothing entered yet.
 */
internal fun mobileFormAuth(
    form: NewConnectionFormState,
    credentials: app.skerry.ui.identity.CredentialManagerController?,
): SshAuth? = when (form.authMode) {
    AuthMode.NEW_PASSWORD -> form.password.takeIf { it.isNotEmpty() }?.let { SshAuth.Password(it) }
    AuthMode.NEW_KEY -> form.privateKeyPem.takeIf { it.isNotBlank() }
        ?.let { SshAuth.PublicKey(it, form.passphrase.ifBlank { null }) }
    // useForConnect: the sheet is opening a connection, not listing secrets (desktop parity).
    AuthMode.EXISTING -> credentials?.useForConnect(form.existingCredentialId)?.toSshAuth()
    AuthMode.ASK -> null
    AuthMode.INTERACTIVE -> SshAuth.Interactive
}

/** Container profile fields on the phone: runtime, target (with "Browse"), namespace/container, shell. */
@Composable
internal fun MobileContainerSection(
    form: NewConnectionFormState,
    browser: ContainerBrowseController?,
    onBrowse: () -> Unit,
) {
    val kubernetes = form.containerRuntime == ContainerRuntime.KUBERNETES
    MobileFormField(stringResource(Res.string.conn_field_runtime)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Skerry.colors.bg)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MobileProtocolSegment(stringResource(Res.string.conn_runtime_docker), !kubernetes, Modifier.weight(1f)) {
                form.containerRuntime = ContainerRuntime.DOCKER
            }
            MobileProtocolSegment(stringResource(Res.string.conn_runtime_kubernetes), kubernetes, Modifier.weight(1f)) {
                form.containerRuntime = ContainerRuntime.KUBERNETES
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    MobileFormField(if (kubernetes) stringResource(Res.string.conn_field_pod) else stringResource(Res.string.conn_field_container)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                MobileFormInput(form.containerTarget, { form.containerTarget = it }, if (kubernetes) "api-0" else "web")
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBrowse)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Txt(stringResource(Res.string.conn_container_browse), color = Skerry.colors.cyanBright, size = 14.sp)
            }
        }
    }
    MobileContainerBrowseResults(browser) { entry ->
        form.containerTarget = entry.name
        if (kubernetes) form.containerPodContainer = entry.containers.singleOrNull().orEmpty()
        browser?.reset()
    }
    if (kubernetes) {
        Spacer(Modifier.height(14.dp))
        MobileFormField(stringResource(Res.string.conn_field_namespace)) {
            MobileFormInput(form.containerNamespace, { form.containerNamespace = it }, "default")
        }
        Spacer(Modifier.height(14.dp))
        MobileFormField(stringResource(Res.string.conn_field_pod_container)) {
            MobileFormInput(form.containerPodContainer, { form.containerPodContainer = it }, "first container")
        }
    }
    Spacer(Modifier.height(14.dp))
    MobileFormField(stringResource(Res.string.conn_field_container_shell)) {
        MobileFormInput(form.containerShell, { form.containerShell = it }, "sh")
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym("info", size = 14.sp, color = Skerry.colors.faint)
        Txt(stringResource(Res.string.conn_container_hint), color = Skerry.colors.faint, size = 12.sp, lineHeight = 16.sp)
    }
}

/** Listing state under the container field: progress, the host's containers, or a localized reason. */
@Composable
private fun MobileContainerBrowseResults(browser: ContainerBrowseController?, onPick: (ContainerEntry) -> Unit) {
    when (val status = browser?.status ?: ContainerBrowseStatus.Idle) {
        ContainerBrowseStatus.Idle -> {}
        ContainerBrowseStatus.Loading -> MobileBrowseNote("progress_activity", stringResource(Res.string.conn_container_loading), Skerry.colors.dim)
        is ContainerBrowseStatus.Failure -> MobileBrowseNote("error", containerBrowseFailureText(status.problem), Skerry.colors.storm)
        is ContainerBrowseStatus.Loaded ->
            if (status.entries.isEmpty()) {
                MobileBrowseNote("info", stringResource(Res.string.conn_container_empty), Skerry.colors.dim)
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(11.dp))
                        .background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                        .heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                ) {
                    status.entries.forEach { entry ->
                        key(entry.name) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(entry) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                            ) {
                                Txt(entry.name, color = Skerry.colors.text, size = 14.sp)
                                val detail = listOf(entry.image, entry.status, entry.containers.joinToString(","))
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                if (detail.isNotEmpty()) Txt(detail, color = Skerry.colors.faint, size = 11.5.sp)
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun MobileBrowseNote(icon: String, text: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym(icon, size = 14.sp, color = color)
        Txt(text, color = color, size = 12.sp, lineHeight = 16.sp)
    }
}
