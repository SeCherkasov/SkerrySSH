package app.skerry.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_known_accept
import app.skerry.ui.generated.resources.lib_known_empty_desc_mobile
import app.skerry.ui.generated.resources.lib_known_empty_title
import app.skerry.ui.generated.resources.lib_known_forget_key
import app.skerry.ui.generated.resources.lib_known_reject
import app.skerry.ui.generated.resources.lib_known_title
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/**
 * Push screen for Known hosts: back header + key-change banners (Accept/Reject inline — no
 * side-by-side fingerprint panel like desktop `KnownHostsView` on phone) + trusted key list. Over
 * the live [KnownHostsController] ([LocalKnownHosts]): banners from `mismatches`, rows from
 * `entries`, Verified/Changed status. Forgetting a key is long-press → Forget in a context sheet.
 * Without a controller (preview/offscreen), shows a static mock.
 */
@Composable
fun MobileKnownScreen(state: MobileDesignState) {
    val mono = LocalFonts.current.mono
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobilePushHeader(stringResource(Res.string.lib_known_title), onBack = state::pop, plainBack = true)
        when (val controller = LocalKnownHosts.current) {
            null -> MockMobileKnownBody(mono)
            else -> LiveMobileKnownBody(controller, mono)
        }
    }
}

// Live path.

/**
 * Live screen body over [KnownHostsController]: a banner per unresolved key-change event
 * (Accept trusts the new key, Reject rejects it and keeps the old one trusted) + trusted key list
 * with status. After Accept/Reject the controller re-reads the stores, the banner disappears, and
 * the row returns to Verified.
 */
@Composable
private fun LiveMobileKnownBody(controller: KnownHostsController, mono: FontFamily) {
    // The screen may have opened after a reconnect wrote a new key to the shared store; re-read.
    LaunchedEffect(Unit) { controller.refresh() }
    val mismatches = controller.mismatches
    val entries = controller.entries
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        mismatches.forEach { mismatch ->
            // Keyed by identity: forEach reuses slots positionally, so without a key the banner's
            // state would "move" when a sibling event clears.
            key(mismatch.host, mismatch.port, mismatch.keyType) {
                // Stabilizes handlers (mismatch is a data class, a valid remember key) so forEach
                // doesn't recreate banner lambdas on every recomposition.
                val onAccept = remember(mismatch) { { controller.acceptNewKey(mismatch) } }
                val onReject = remember(mismatch) { { controller.reject(mismatch) } }
                MobileMismatchBanner(
                    title = mobileKnownBannerTitle(mismatch),
                    body = mobileKnownBannerBody(mismatch),
                    onAccept = onAccept,
                    onReject = onReject,
                )
            }
        }
        if (entries.isEmpty()) {
            MobileEmptyKnown()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    val host = entry.host
                    key(host.host, host.port, host.keyType) {
                        // entry is an @Immutable data class, a valid remember key for a stable onForget.
                        val onForget = remember(entry) { { controller.forget(entry) } }
                        LiveKnownRow(entry, mono, onForget = onForget)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** Live trusted-key row: name + subtitle (type·fingerprint / type·changed) + status icon; long-press opens the Forget sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveKnownRow(entry: KnownHostEntry, mono: FontFamily, onForget: () -> Unit) {
    var menuOpen by remember(entry.host.host, entry.host.port, entry.host.keyType) { mutableStateOf(false) }
    KnownRowContent(
        name = entry.host.host,
        subtitle = mobileKnownSubtitle(entry),
        status = entry.status,
        mono = mono,
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
    )
    if (menuOpen) {
        MobileActionSheet(
            title = entry.host.host,
            subtitle = mobileKnownSubtitle(entry),
            actions = listOf(
                MobileSheetAction(stringResource(Res.string.lib_known_forget_key), onClick = onForget, icon = "delete", danger = true),
            ),
            onDismiss = { menuOpen = false },
        )
    }
}

/** Empty state: no trusted keys yet (the first connect will record one via TOFU). */
@Composable
private fun MobileEmptyKnown() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("fingerprint", size = 28.sp, color = D.faint)
            Txt(stringResource(Res.string.lib_known_empty_title), color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.lib_known_empty_desc_mobile), color = D.faint, size = 12.sp)
        }
    }
}

// Shared layout pieces.

/**
 * Key-change banner: coral card, gpp_maybe icon header, body text, and two full-width
 * Accept/Reject buttons. [onAccept]/[onReject] are no-ops on the mock path (preview/offscreen).
 */
@Composable
private fun MobileMismatchBanner(title: String, body: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(D.sunset.copy(alpha = 0.05f))
            .border(1.dp, D.sunset.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("gpp_maybe", size = 19.sp, color = D.sunset)
            Txt(title, color = D.sunset, size = 13.5.sp, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Txt(body, color = D.dim, size = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BannerButton(
                label = stringResource(Res.string.lib_known_accept),
                fg = D.text,
                bg = Color.Transparent,
                border = D.cyan14,
                bold = false,
                onClick = onAccept,
                modifier = Modifier.weight(1f),
            )
            BannerButton(
                label = stringResource(Res.string.lib_known_reject),
                fg = D.sunset,
                bg = D.sunset.copy(alpha = 0.12f),
                border = D.sunset.copy(alpha = 0.3f),
                bold = true,
                onClick = onReject,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BannerButton(
    label: String,
    fg: Color,
    bg: Color,
    border: Color,
    bold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = fg, size = 12.5.sp, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Trusted-key row card: name (mono) + subtitle (mono) + status icon; coral tone when changed. */
@Composable
private fun KnownRowContent(
    name: String,
    subtitle: String,
    status: KnownHostStatus,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    val changed = status == KnownHostStatus.Changed
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (changed) D.sunset.copy(alpha = 0.05f) else D.card)
            .border(1.dp, if (changed) D.sunset.copy(alpha = 0.2f) else D.cyan08, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(name, color = D.text, size = 13.sp, font = mono)
            Txt(subtitle, color = if (changed) D.sunset else D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 2.dp))
        }
        Sym(mobileKnownStatusIcon(status), size = 15.sp, color = if (changed) D.sunset else D.moss)
    }
}

// Mock (preview/offscreen).

private data class MockKnownHost(val name: String, val subtitle: String, val status: KnownHostStatus)

/** Static rows for preview/offscreen. */
private val MOCK_KNOWN = listOf(
    MockKnownHost("prod-web-01", "ed25519 · 8c3F1a…pK9R", KnownHostStatus.Verified),
    MockKnownHost("db-master", "ed25519 · 2dE7b…wQ1z", KnownHostStatus.Verified),
    MockKnownHost("nas-truenas", "ed25519 · changed", KnownHostStatus.Changed),
)

/** Mock body (preview/offscreen): key-change banner + static rows. */
@Composable
private fun MockMobileKnownBody(mono: FontFamily) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        MobileMismatchBanner(
            title = "Key changed: nas-truenas",
            body = "Fingerprint differs from Mar 4. Verify before reconnecting.",
            onAccept = {},
            onReject = {},
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MOCK_KNOWN.forEach { KnownRowContent(it.name, it.subtitle, it.status, mono) }
        }
        Spacer(Modifier.height(30.dp))
    }
}
