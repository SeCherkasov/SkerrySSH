package app.skerry.ui.trust

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.trust.HostTrustCertificate
import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.ui.app.UiTags
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.FingerprintBox
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.KeyValueRow
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.MAX_UNTRUSTED_LABEL_CHARS
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.rememberPromptFocus
import app.skerry.ui.design.sanitizeServerHost
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_known_accept_new_key
import app.skerry.ui.generated.resources.lib_known_now_offered
import app.skerry.ui.generated.resources.lib_known_previously_recorded
import app.skerry.ui.generated.resources.lib_known_reinstall_note
import app.skerry.ui.generated.resources.lib_known_reject_block
import app.skerry.ui.generated.resources.trust_accept
import app.skerry.ui.generated.resources.trust_accept_new_cert
import app.skerry.ui.generated.resources.trust_cert_issuer
import app.skerry.ui.generated.resources.trust_cert_name_mismatch
import app.skerry.ui.generated.resources.trust_cert_not_verified
import app.skerry.ui.generated.resources.trust_cert_subject
import app.skerry.ui.generated.resources.trust_cert_valid_until
import app.skerry.ui.generated.resources.trust_changed_cert_title
import app.skerry.ui.generated.resources.trust_changed_key_title
import app.skerry.ui.generated.resources.trust_fingerprint
import app.skerry.ui.generated.resources.trust_new_cert_body
import app.skerry.ui.generated.resources.trust_new_cert_title
import app.skerry.ui.generated.resources.trust_new_key_body
import app.skerry.ui.generated.resources.trust_new_key_title
import app.skerry.ui.generated.resources.trust_other_type_note
import app.skerry.ui.generated.resources.trust_other_type_title
import app.skerry.ui.generated.resources.trust_reject
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.sftp.fileDateText
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Asks the user to accept a host's identity while the handshake waits — the moment TOFU used to
 * take silently. Shown on desktop and mobile alike; style follows the other blocking prompts
 * (`KeyboardInteractiveDialog`) and the fingerprint comparison of the known-hosts manager, which
 * answers the same question after the fact.
 *
 * Nothing here is trusted text: an SSH key type, a certificate's subject and issuer are all authored
 * by whatever answered the connection, so each goes through [sanitizeServerText] before it is drawn.
 *
 * Which button is primary depends on the danger. On first contact the user is deciding whether to
 * connect at all, so accepting leads; on a changed key the safe answer is to refuse, and "Accept new
 * key" is the second, danger-styled button — the same ordering, and the same wording, the
 * known-hosts panel uses.
 */
@Composable
fun HostTrustDialog(
    questionId: Long,
    request: HostTrustRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val noop = remember { MutableInteractionSource() }
    val mono = LocalFonts.current.mono
    val focus = remember(questionId) { FocusRequester() }
    // A fresh scroll per question: the card is composed from one call site, so without this the
    // next host's dialog opens at the offset the previous one was left scrolled to, with its own
    // title above the viewport.
    val scroll = remember(questionId) { ScrollState(0) }
    // Registered as a modal holding the caret, like the 2FA prompt: the answer must not be typed
    // into the session underneath, and a second host asking must not draw over this one.
    val prompt = rememberPromptFocus(focus, questionId)
    val title = stringResource(trustTitle(request))
    // The host as the card draws it, hoisted so the pane announces the same name the eye reads.
    val host = sanitizeServerHost(request.host)
    PlatformBackHandler(onBack = onReject)

    Box(
        prompt.fillMaxSize().background(Skerry.colors.modalScrim)
            // A click outside refuses: the safe answer is the one an accident lands on.
            .clickable(interactionSource = noop, indication = null, onClick = onReject),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, if (request.hostAlreadyKnown) Skerry.colors.sunset else Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                // The pane a screen reader announces on arrival. Without the host in it the
                // announcement is "New host key" and nothing else — the one fact the answer turns on
                // is then several stops away, past the buttons.
                .semantics { paneTitle = "$title: $host:${request.port}" }
                .verticalScroll(scroll)
                .padding(26.dp),
        ) {
            Header(title, request.hostAlreadyKnown)
            Endpoint(host, request.port, request.keyType, mono)
            if (request.keyChanged) ChangedKey(request, mono) else NewKey(request, mono)
            request.certificate?.let { CertificateFacts(it) }
            Actions(request, focus = focus, onAccept = onAccept, onReject = onReject)
        }
    }
}

private fun trustTitle(request: HostTrustRequest) = when {
    request.kind == HostTrustKind.RdpCertificate && request.keyChanged -> Res.string.trust_changed_cert_title
    request.kind == HostTrustKind.RdpCertificate -> Res.string.trust_new_cert_title
    request.keyChanged -> Res.string.trust_changed_key_title
    // Not a first contact either: the store holds this host under another algorithm, and saying
    // "new host key" would be the attacker's own framing.
    request.recordedKeyTypes.isNotEmpty() -> Res.string.trust_other_type_title
    else -> Res.string.trust_new_key_title
}

@Composable
private fun Header(title: String, alarming: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(
            if (alarming) "policy" else "vpn_key",
            size = 18.sp,
            color = if (alarming) Skerry.colors.sunset else Skerry.colors.cyan,
        )
        // Our own heading, never the server's — everything the host authored is drawn below, in a
        // shape that reads as data rather than as Skerry's own chrome.
        Txt(
            title,
            color = Skerry.colors.text,
            size = 16.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        )
    }
}

/**
 * `host:port · key type` — the machine being answered for, in the terms the protocol names it.
 *
 * [host] arrives filtered and elided from the caller: an RDP server picks the name itself through
 * the redirection PDU's target FQDN, and this is the one line that says which machine the user is
 * vouching for. Unfiltered it could add its own lines under the title or push the fingerprint off
 * the bottom of the card; cut at the head alone it could hide which domain it really sits in.
 */
@Composable
private fun Endpoint(host: String, port: Int, rawKeyType: String, mono: FontFamily) {
    val keyType = sanitizeServerText(rawKeyType, MAX_UNTRUSTED_LABEL_CHARS, allowNewlines = false)
        .removePrefix("ssh-")
    val endpoint = "$host:$port" + if (keyType.isBlank()) "" else " · $keyType"
    Txt(
        endpoint,
        color = Skerry.colors.dim,
        size = 12.5.sp,
        font = mono,
        // Laid out left to right whatever it contains: a name carrying strong RTL characters is
        // reordered by the bidi algorithm on its own, and this is the line the user reads to decide
        // who they are trusting. Sanitizing takes the overrides away, not the reordering.
        textDirection = TextDirection.Ltr,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun NewKey(request: HostTrustRequest, mono: FontFamily) {
    Txt(
        stringResource(
            if (request.kind == HostTrustKind.RdpCertificate) Res.string.trust_new_cert_body else Res.string.trust_new_key_body,
        ),
        color = Skerry.colors.dim,
        size = 12.5.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 14.dp),
    )
    Caption(stringResource(Res.string.trust_fingerprint), Skerry.colors.faint, top = 14.dp)
    FingerprintBox(request.fingerprint, Skerry.colors.text, Skerry.colors.cyan14, mono)
    // What OpenSSH says in the same situation. Named types rather than a count: "already recorded
    // with ssh-ed25519" is checkable against the known-hosts panel, "1 other key" is not.
    if (request.recordedKeyTypes.isNotEmpty()) {
        Warning(stringResource(Res.string.trust_other_type_note, request.recordedKeyTypes.joinToString(", ")))
    }
}

/** A sunset-tinted note: the one thing on the card that says why this is not an ordinary question. */
@Composable
private fun Warning(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.sunset.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Sym("info", size = 15.sp, color = Skerry.colors.sunset)
        Txt(text, color = Skerry.colors.dim, size = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun ChangedKey(request: HostTrustRequest, mono: FontFamily) {
    Caption(stringResource(Res.string.lib_known_previously_recorded), Skerry.colors.moss, top = 16.dp)
    FingerprintBox(request.recordedFingerprint.orEmpty(), Skerry.colors.dim, Skerry.colors.moss.copy(alpha = 0.2f), mono)
    Caption(stringResource(Res.string.lib_known_now_offered), Skerry.colors.sunset, top = 14.dp)
    FingerprintBox(request.fingerprint, Skerry.colors.sunset, Skerry.colors.sunset.copy(alpha = 0.3f), mono)
    Warning(stringResource(Res.string.lib_known_reinstall_note))
}

/**
 * What the certificate says about itself. A Windows host signs its own certificate and names itself
 * after the machine rather than the address dialled, so neither note is a refusal — they are the two
 * facts a person weighs when deciding whether this is the server they meant.
 */
@Composable
private fun CertificateFacts(certificate: HostTrustCertificate) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        // Subject and issuer wrap under their label rather than sharing a line with it: a real
        // certificate's DN is a hundred characters of comma-separated fields, and the right-aligned
        // single line of [KeyValueRow] would elide exactly the part that names the authority.
        Name(
            stringResource(Res.string.trust_cert_subject),
            sanitizeServerText(certificate.subject, MAX_UNTRUSTED_LABEL_CHARS, allowNewlines = false),
        )
        Name(
            stringResource(Res.string.trust_cert_issuer),
            sanitizeServerText(certificate.issuer, MAX_UNTRUSTED_LABEL_CHARS, allowNewlines = false),
        )
        if (certificate.notAfterMillis > 0) {
            KeyValueRow(
                stringResource(Res.string.trust_cert_valid_until),
                fileDateText(certificate.notAfterMillis / 1000, withTime = false),
            )
        }
        if (!certificate.trustedByPlatform) Note(stringResource(Res.string.trust_cert_not_verified))
        if (!certificate.hostnameMatches) Note(stringResource(Res.string.trust_cert_name_mismatch))
    }
}

/**
 * A certificate name (subject or issuer): its label, then the whole value under it.
 *
 * Merged into one semantics node for the same reason [KeyValueRow] does it — these are the two facts
 * a person weighs before vouching for the machine, and a label announced two stops away from its
 * value is not one of them.
 */
@Composable
private fun Name(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Txt(label, color = Skerry.colors.dim, size = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Txt(
            value,
            color = Skerry.colors.text,
            size = 11.5.sp,
            lineHeight = 16.sp,
            font = LocalFonts.current.mono,
            // A DN the server wrote, for the same reason as the endpoint line.
            textDirection = TextDirection.Ltr,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
private fun Note(text: String) {
    Txt(text, color = Skerry.colors.faint, size = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Caption(text: String, color: Color, top: Dp) {
    Txt(
        text,
        color = color,
        size = 10.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = top, bottom = 6.dp),
    )
}

/**
 * [focus] lands on the refusing button, and it is the one [rememberPromptFocus] claims: the caret has
 * to end up on a node inside the card (a request that finds none leaves the keyboard on nothing at
 * all, and the question opens unannounced), and the key an accidental Enter reaches must be the one
 * that changes nothing.
 */
@Composable
private fun Actions(
    request: HostTrustRequest,
    focus: FocusRequester,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    if (request.hostAlreadyKnown) {
        // Under a possible interception the reflex is to press the first, familiar button — so that
        // one refuses, and accepting is the second, danger-styled choice.
        val reject =
            if (request.kind == HostTrustKind.RdpCertificate) Res.string.trust_reject else Res.string.lib_known_reject_block
        val accept =
            if (request.kind == HostTrustKind.RdpCertificate) {
                Res.string.trust_accept_new_cert
            } else {
                Res.string.lib_known_accept_new_key
            }
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                stringResource(reject),
                onClick = onReject,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(UiTags.FORM_CANCEL),
            )
            GhostButton(
                stringResource(accept),
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.FORM_SAVE),
                fg = Skerry.colors.sunset,
                border = Skerry.colors.sunset,
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CancelButton(
                stringResource(Res.string.trust_reject),
                onClick = onReject,
                modifier = Modifier.focusRequester(focus).testTag(UiTags.FORM_CANCEL),
            )
            PrimaryButton(stringResource(Res.string.trust_accept), onClick = onAccept, modifier = Modifier.testTag(UiTags.FORM_SAVE))
        }
    }
}

/**
 * Renders the pending trust question, if any, wherever it is placed — one call at the root of each
 * platform's chrome, like [app.skerry.ui.connection.KeyboardInteractiveHost].
 */
@Composable
fun HostTrustHost(controller: HostTrustPromptController?) {
    val question by (controller?.pending ?: return).collectAsState()
    val pending = question ?: return
    HostTrustDialog(
        questionId = pending.id,
        request = pending.request,
        onAccept = { controller.accept(pending.id) },
        onReject = { controller.refuse(pending.id) },
    )
}
