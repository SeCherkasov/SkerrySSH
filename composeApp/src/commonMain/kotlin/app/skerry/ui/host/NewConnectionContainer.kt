package app.skerry.ui.host

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import app.skerry.ui.connection.ContainerBrowseController
import app.skerry.ui.connection.ContainerBrowseStatus
import app.skerry.ui.connection.containerBrowseFailureText
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.identity.CredentialManagerController
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
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * Auth for a form-side probe (test connection / container listing) — materialized at click time so
 * the password/key copy lives only for the probe's duration, not for as long as the modal is open.
 * `null` means "Ask every time" or an incomplete entry: nothing to dial with.
 */
internal fun formSshAuth(form: NewConnectionFormState, credentials: CredentialManagerController?): SshAuth? =
    when (form.authMode) {
        AuthMode.NEW_PASSWORD -> form.password.takeIf { it.isNotEmpty() }?.let { SshAuth.Password(it) }
        AuthMode.NEW_KEY -> form.privateKeyPem.takeIf { it.isNotBlank() }
            ?.let { SshAuth.PublicKey(it, form.passphrase.ifBlank { null }) }
        // useForConnect: this is a real connection about to be opened, so the secret counts as used.
        AuthMode.EXISTING -> credentials?.useForConnect(form.existingCredentialId)?.toSshAuth()
        AuthMode.ASK -> null
        AuthMode.INTERACTIVE -> SshAuth.Interactive
    }

/**
 * Container profile fields: runtime, what to enter (with "Browse" listing the host's containers),
 * Kubernetes namespace/container, and the shell to run. [browser] is `null` on the mock/preview path
 * (no live transport) — the field stays typeable, only listing is unavailable.
 */
@Composable
internal fun ContainerSection(
    form: NewConnectionFormState,
    browser: ContainerBrowseController?,
    onBrowse: () -> Unit,
) {
    val kubernetes = form.containerRuntime == ContainerRuntime.KUBERNETES
    Spacer14()
    Field(stringResource(Res.string.conn_field_runtime)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ProtocolSegment(stringResource(Res.string.conn_runtime_docker), "deployed_code", !kubernetes, Modifier.weight(1f)) {
                form.containerRuntime = ContainerRuntime.DOCKER
            }
            ProtocolSegment(stringResource(Res.string.conn_runtime_kubernetes), "hub", kubernetes, Modifier.weight(1f)) {
                form.containerRuntime = ContainerRuntime.KUBERNETES
            }
        }
    }
    Spacer14()
    Field(if (kubernetes) stringResource(Res.string.conn_field_pod) else stringResource(Res.string.conn_field_container)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                ModalTextField(
                    form.containerTarget, { form.containerTarget = it },
                    if (kubernetes) "api-0" else "web or 9c1a2b3c4d5e",
                    icon = "deployed_code",
                )
            }
            GhostButton(stringResource(Res.string.conn_container_browse), onClick = onBrowse)
        }
    }
    ContainerBrowseResults(browser) { entry ->
        form.containerTarget = entry.name
        // A single-container pod has no ambiguity to resolve; a multi-container one keeps the
        // field empty (kubectl then picks the pod's first container) for the user to narrow down.
        if (kubernetes) form.containerPodContainer = entry.containers.singleOrNull().orEmpty()
        browser?.reset()
    }
    if (kubernetes) {
        Spacer14()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(stringResource(Res.string.conn_field_namespace), Modifier.weight(1f)) {
                ModalTextField(form.containerNamespace, { form.containerNamespace = it }, "default")
            }
            Field(stringResource(Res.string.conn_field_pod_container), Modifier.weight(1f)) {
                ModalTextField(form.containerPodContainer, { form.containerPodContainer = it }, "first container")
            }
        }
    }
    Spacer14()
    Field(stringResource(Res.string.conn_field_container_shell)) {
        ModalTextField(form.containerShell, { form.containerShell = it }, "sh", icon = "terminal")
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym("info", size = 14.sp, color = Skerry.colors.faint)
        Txt(stringResource(Res.string.conn_container_hint), color = Skerry.colors.faint, size = 11.5.sp, lineHeight = 15.sp)
    }
}

/** Result of "Browse": progress, the host's containers (click to pick), or a localized reason. */
@Composable
private fun ContainerBrowseResults(browser: ContainerBrowseController?, onPick: (ContainerEntry) -> Unit) {
    when (val status = browser?.status ?: ContainerBrowseStatus.Idle) {
        ContainerBrowseStatus.Idle -> {}
        ContainerBrowseStatus.Loading -> BrowseNote("progress_activity", stringResource(Res.string.conn_container_loading), Skerry.colors.dim)
        is ContainerBrowseStatus.Failure ->
            BrowseNote("error", containerBrowseFailureText(status.problem), Skerry.colors.storm)
        is ContainerBrowseStatus.Loaded ->
            if (status.entries.isEmpty()) {
                BrowseNote("info", stringResource(Res.string.conn_container_empty), Skerry.colors.dim)
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(7.dp))
                        .background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                        .heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                ) {
                    status.entries.forEach { entry ->
                        key(entry.name) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Txt(untrustedLabel(entry.name), color = Skerry.colors.text, size = 12.5.sp, modifier = Modifier.weight(1f))
                                val detail = listOf(entry.image, entry.status, entry.containers.joinToString(",")).map(::untrustedLabel)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                if (detail.isNotEmpty()) Txt(detail, color = Skerry.colors.faint, size = 11.sp)
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun BrowseNote(icon: String, text: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym(icon, size = 14.sp, color = color)
        Txt(text, color = color, size = 11.5.sp, lineHeight = 15.sp)
    }
}
