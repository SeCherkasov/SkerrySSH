package app.skerry.ui.known

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.known.shortFingerprint
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_known_accept_new_key
import app.skerry.ui.generated.resources.lib_known_col_fingerprint
import app.skerry.ui.generated.resources.lib_known_col_first_seen
import app.skerry.ui.generated.resources.lib_known_col_host
import app.skerry.ui.generated.resources.lib_known_col_key_type
import app.skerry.ui.generated.resources.lib_known_col_status
import app.skerry.ui.generated.resources.lib_known_dismiss
import app.skerry.ui.generated.resources.lib_known_empty_desc
import app.skerry.ui.generated.resources.lib_known_empty_title
import app.skerry.ui.generated.resources.lib_known_forget_key
import app.skerry.ui.generated.resources.lib_known_fp_mismatch
import app.skerry.ui.generated.resources.lib_known_key_changed_for
import app.skerry.ui.generated.resources.lib_known_mismatch_body
import app.skerry.ui.generated.resources.lib_known_now_offered
import app.skerry.ui.generated.resources.lib_known_previously_recorded
import app.skerry.ui.generated.resources.lib_known_reinstall_note
import app.skerry.ui.generated.resources.lib_known_reject_block
import app.skerry.ui.generated.resources.lib_known_review_fingerprint
import app.skerry.ui.generated.resources.lib_known_status_changed
import app.skerry.ui.generated.resources.lib_known_status_verified
import app.skerry.ui.generated.resources.lib_known_subtitle
import app.skerry.ui.generated.resources.lib_known_title
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine

private data class KnownHost(val name: String, val keyType: String, val fp: String, val seen: String, val changed: Boolean)

private val KNOWN = listOf(
    KnownHost("prod-web-01", "ed25519", "8c3F1a2bQz…pK9R", "Jan 12 2026", false),
    KnownHost("db-master", "ed25519", "2dE7bLm4xR…wQ1z", "Jan 12 2026", false),
    KnownHost("nas-truenas", "ed25519", "9aB0cTn2wE…changed", "Mar 4 2026", true),
    KnownHost("homelab-pi", "rsa", "5fG1hKp8s…vB3n", "Feb 2 2026", false),
)

/**
 * Known hosts view. With a live [KnownHostsController] ([LocalKnownHosts]) renders trusted keys
 * from the store (host/type/fingerprint/first-seen/status), key-change warnings, and a
 * fingerprint comparison panel (Accept new key / Reject & block); forgetting a key uses the
 * "Forget key" button revealed on row hover (desktop). Without a controller (offscreen
 * render/preview) shows the static [KNOWN] mock data.
 */
@Composable
fun KnownHostsView() {
    when (val controller = LocalKnownHosts.current) {
        null -> MockKnownHostsView()
        else -> LiveKnownHostsView(controller)
    }
}

@Composable
private fun KnownHostsHeader() {
    Column(Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 22.dp, vertical = 14.dp)) {
        Txt(stringResource(Res.string.lib_known_title), color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
        Txt(stringResource(Res.string.lib_known_subtitle), color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun KnownHostsTableHeader() {
    Row(
        Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KHeader(stringResource(Res.string.lib_known_col_host), Modifier.weight(1f))
        KHeader(stringResource(Res.string.lib_known_col_key_type), Modifier.width(90.dp))
        KHeader(stringResource(Res.string.lib_known_col_fingerprint), Modifier.weight(1.4f))
        KHeader(stringResource(Res.string.lib_known_col_first_seen), Modifier.width(100.dp))
        KHeader(stringResource(Res.string.lib_known_col_status), Modifier.width(80.dp))
    }
}

// Live path: trusted-keys table plus warnings/key-change panel driven by the controller.

@Composable
private fun LiveKnownHostsView(controller: KnownHostsController) {
    // The screen may open after a reconnect wrote a new key to the shared store — reload.
    LaunchedEffect(Unit) { controller.refresh() }
    val mono = LocalFonts.current.mono
    val entries = controller.entries
    val mismatches = controller.mismatches

    // Selected key-change event for the right panel: defaults to the first pending one. After
    // accept/reject it leaves the list — the selection falls to the next one (or disappears).
    var selectedKey by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    val selected = mismatches.firstOrNull { it.identity() == selectedKey } ?: mismatches.firstOrNull()

    Column(Modifier.fillMaxSize().background(D.bg)) {
        KnownHostsHeader()
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty() && mismatches.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { EmptyKnownHosts() }
            } else {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                    mismatches.forEach { mismatch ->
                        MismatchBanner(
                            mismatch = mismatch,
                            onReview = { selectedKey = mismatch.identity() },
                            onDismiss = { controller.reject(mismatch) },
                        )
                    }
                    if (entries.isEmpty()) {
                        EmptyKnownHosts()
                    } else {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, D.cyan08, RoundedCornerShape(10.dp))) {
                            KnownHostsTableHeader()
                            entries.forEach { entry ->
                                HLine()
                                KnownRowLive(entry, mono, onForget = { controller.forget(entry) })
                            }
                        }
                    }
                }
            }
            val current = selected
            if (current != null) {
                VLine(D.line)
                LiveMismatchPanel(
                    mismatch = current,
                    mono = mono,
                    onAccept = { controller.acceptNewKey(current) },
                    onReject = { controller.reject(current) },
                )
            }
        }
    }
}

private fun HostKeyMismatch.identity() = Triple(host, port, keyType)

@Composable
private fun MismatchBanner(mismatch: HostKeyMismatch, onReview: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(10.dp)).background(D.sunset.copy(alpha = 0.05f)).border(1.dp, D.sunset.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym("gpp_maybe", size = 20.sp, color = D.sunset)
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.lib_known_key_changed_for, mismatch.host), color = D.sunset, size = 13.sp, weight = FontWeight.SemiBold)
            Txt(
                stringResource(Res.string.lib_known_mismatch_body, displayKeyType(mismatch.keyType).uppercase()),
                color = D.dim, size = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableSmallButton(stringResource(Res.string.lib_known_review_fingerprint), D.sunset, D.sunset.copy(alpha = 0.4f), bold = true, onClick = onReview)
                // "Dismiss" here means reject (the banner hides, the key stays blocked); the label
                // is honest so users don't block a legitimate key change thinking it just hides the notice.
                ClickableSmallButton(stringResource(Res.string.lib_known_reject_block), D.dim, D.cyan14, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun KnownRowLive(entry: KnownHostEntry, mono: FontFamily, onForget: () -> Unit) {
    val host = entry.host
    val changed = entry.status == KnownHostStatus.Changed
    // Forget is a row action revealed on mouse hover (desktop): at rest the table reads like the
    // mock, on hover the row highlights and a "Forget key" button slides in at the right end.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (hovered) D.cyan.copy(alpha = 0.04f) else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt(host.host, color = D.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
            Txt(displayKeyType(host.keyType), color = D.dim, size = 12.sp, modifier = Modifier.width(90.dp))
            Txt(shortFingerprint(host.fingerprint), color = if (changed) D.sunset else D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1.4f))
            Txt(displayFirstSeen(host.firstSeen), color = D.faint, size = 12.sp, modifier = Modifier.width(100.dp))
            // Status hides on hover, making room for the Forget button (which overlaps its right edge).
            Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!hovered) {
                    if (changed) {
                        Sym("error", size = 14.sp, color = D.sunset)
                        Txt(stringResource(Res.string.lib_known_status_changed), color = D.sunset, size = 11.sp)
                    } else {
                        Sym("verified", size = 14.sp, color = D.moss)
                        Txt(stringResource(Res.string.lib_known_status_verified), color = D.moss, size = 11.sp)
                    }
                }
            }
        }
        if (hovered) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(D.sunset.copy(alpha = 0.12f))
                    .border(1.dp, D.sunset.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable(onClick = onForget)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("delete", size = 14.sp, color = D.sunset)
                Txt(stringResource(Res.string.lib_known_forget_key), color = D.sunset, size = 11.5.sp)
            }
        }
    }
}

@Composable
private fun EmptyKnownHosts() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym("fingerprint", size = 26.sp, color = D.faint)
        Txt(stringResource(Res.string.lib_known_empty_title), color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        Txt(stringResource(Res.string.lib_known_empty_desc), color = D.faint, size = 11.5.sp)
    }
}

@Composable
private fun LiveMismatchPanel(mismatch: HostKeyMismatch, mono: FontFamily, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(Modifier.width(322.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("policy", size = 18.sp, color = D.sunset)
            Txt(stringResource(Res.string.lib_known_fp_mismatch), color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        Txt("${mismatch.host} · ${displayKeyType(mismatch.keyType)}", color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
        Txt(stringResource(Res.string.lib_known_previously_recorded), color = D.moss, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp))
        FpBox(mismatch.recordedFingerprint, D.dim, D.moss.copy(alpha = 0.2f), mono)
        Txt(stringResource(Res.string.lib_known_now_offered), color = D.sunset, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FpBox(mismatch.offeredFingerprint, D.sunset, D.sunset.copy(alpha = 0.3f), mono)
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp).clip(RoundedCornerShape(7.dp)).background(D.sunset.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Sym("info", size = 15.sp, color = D.sunset)
            Txt(stringResource(Res.string.lib_known_reinstall_note), color = D.dim, size = 11.sp, lineHeight = 16.sp)
        }
        // The safe choice is primary and first: under a possible interception, the user reflexively
        // hits the top/familiar button, so that must be "Reject & block", not "Accept". Accepting a
        // new key is the dangerous action, styled second in the danger (sunset) style.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(Res.string.lib_known_reject_block), onClick = onReject, modifier = Modifier.fillMaxWidth())
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.sunset.copy(alpha = 0.12f)).border(1.dp, D.sunset, RoundedCornerShape(7.dp)).clickable(onClick = onAccept).padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.lib_known_accept_new_key), color = D.sunset, size = 12.5.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** Key type as shown in the mock: strips the `ssh-` prefix (ed25519, rsa, …). */
private fun displayKeyType(keyType: String): String = keyType.removePrefix("ssh-")

/** First-trust date from ISO-8601 (date part only); empty falls back to "—". */
private fun displayFirstSeen(firstSeen: String): String = firstSeen.substringBefore('T').ifEmpty { "—" }

// Mock path (offscreen render/preview): static table plus the fingerprint comparison panel from the mock.

/** Known hosts view (mock): header, key-change warning, table, comparison panel. */
@Composable
private fun MockKnownHostsView() {
    val mono = LocalFonts.current.mono
    Column(Modifier.fillMaxSize().background(D.bg)) {
        KnownHostsHeader()
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(10.dp)).background(D.sunset.copy(alpha = 0.05f)).border(1.dp, D.sunset.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Sym("gpp_maybe", size = 20.sp, color = D.sunset)
                    Column(Modifier.weight(1f)) {
                        Txt("Host key changed for nas-truenas", color = D.sunset, size = 13.sp, weight = FontWeight.SemiBold)
                        Txt("The ED25519 fingerprint differs from the one recorded on Mar 4. This could be a re-install — or a man-in-the-middle. Verify before reconnecting.", color = D.dim, size = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton(stringResource(Res.string.lib_known_review_fingerprint), D.sunset, D.sunset.copy(alpha = 0.4f), bold = true)
                            SmallButton(stringResource(Res.string.lib_known_dismiss), D.dim, D.cyan14)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, D.cyan08, RoundedCornerShape(10.dp))) {
                    KnownHostsTableHeader()
                    KNOWN.forEach { host ->
                        HLine()
                        KnownRow(host, mono)
                    }
                }
            }
            VLine(D.line)
            MismatchPanel(mono)
        }
    }
}

@Composable
private fun KHeader(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Txt(text, color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun KnownRow(host: KnownHost, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Txt(host.name, color = D.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
        Txt(host.keyType, color = D.dim, size = 12.sp, modifier = Modifier.width(90.dp))
        Txt(host.fp, color = if (host.changed) D.sunset else D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1.4f))
        Txt(host.seen, color = D.faint, size = 12.sp, modifier = Modifier.width(100.dp))
        Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (host.changed) {
                Sym("error", size = 14.sp, color = D.sunset)
                Txt(stringResource(Res.string.lib_known_status_changed), color = D.sunset, size = 11.sp)
            } else {
                Sym("verified", size = 14.sp, color = D.moss)
                Txt(stringResource(Res.string.lib_known_status_verified), color = D.moss, size = 11.sp)
            }
        }
    }
}

@Composable
private fun MismatchPanel(mono: FontFamily) {
    Column(Modifier.width(322.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("policy", size = 18.sp, color = D.sunset)
            Txt(stringResource(Res.string.lib_known_fp_mismatch), color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        Txt("nas-truenas · ed25519", color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
        Txt("PREVIOUSLY RECORDED · MAR 4", color = D.moss, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp))
        FpBox("SHA256:9aB0cTn2wE4rXp1kLm7sQ8vZ", D.dim, D.moss.copy(alpha = 0.2f), mono)
        Txt("NOW OFFERED · TODAY", color = D.sunset, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FpBox("SHA256:Kp3xQ9zR1tWv7nB4mL0sJ2dF", D.sunset, D.sunset.copy(alpha = 0.3f), mono)
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp).clip(RoundedCornerShape(7.dp)).background(D.sunset.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Sym("info", size = 15.sp, color = D.sunset)
            Txt(stringResource(Res.string.lib_known_reinstall_note), color = D.dim, size = 11.sp, lineHeight = 16.sp)
        }
        // Mirrors the safe ordering of the live panel (see LiveMismatchPanel): Reject is primary and first.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(Res.string.lib_known_reject_block), onClick = {}, modifier = Modifier.fillMaxWidth())
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.sunset.copy(alpha = 0.12f)).border(1.dp, D.sunset, RoundedCornerShape(7.dp)).padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.lib_known_accept_new_key), color = D.sunset, size = 12.5.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FpBox(text: String, color: Color, border: Color, mono: FontFamily) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.terminalBg).border(1.dp, border, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Txt(text, color = color, size = 10.5.sp, font = mono)
    }
}

@Composable
private fun SmallButton(label: String, fg: Color, border: Color, bold: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, border, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Txt(label, color = fg, size = 11.5.sp, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** [SmallButton] with a click handler, for live banners (Review fingerprint / Dismiss). */
@Composable
private fun ClickableSmallButton(label: String, fg: Color, border: Color, bold: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, border, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Txt(label, color = fg, size = 11.5.sp, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}
