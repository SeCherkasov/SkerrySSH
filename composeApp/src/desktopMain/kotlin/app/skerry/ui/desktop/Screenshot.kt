package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import app.skerry.ui.host.HostSection
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.Vault
import app.skerry.ui.AppDependencies
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.SessionView
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import java.io.File
import app.skerry.ui.vault.DesktopCorruptedScreen
import app.skerry.ui.vault.DesktopCreateScreen
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.vault.DesktopResetScreen
import app.skerry.ui.vault.DesktopUnlockScreen
import app.skerry.ui.app.DesktopView
import app.skerry.ui.mobile.MOBILE_PREVIEW_HOSTS
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.metrics.MetricsAvailability
import app.skerry.ui.metrics.PREVIEW_HOST_METRICS
import app.skerry.ui.metrics.PREVIEW_METRICS_HISTORY
import app.skerry.ui.metrics.PREVIEW_RX_RATE
import app.skerry.ui.metrics.PREVIEW_TX_RATE
import app.skerry.ui.mobile.MobileDesignApp
import app.skerry.ui.mobile.MobileHostMonitorSheet
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.SettingsTab

/**
 * Offscreen render of the desktop design to PNG for visual review without a window/compositor.
 * Controlled by system properties `skerry.screenshot.*`: out is the PNG path, view/overlay pick
 * what to show, `live=true` feeds seeded [HostManagerController] + [SessionsController] (live
 * sidebar, tabs, terminal over a fake transport with canned output). Not part of the app; run via
 * the Gradle task `screenshotDesign`.
 *
 * The `create`/`unlock` overlays render the live master password gate screens ([DesktopCreateScreen]/
 * [DesktopUnlockScreen]) standalone (without `VaultGateController`/lifecycle) for visual review; their
 * wiring to [app.skerry.ui.vault.VaultGate] is covered by controller tests and compilation.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // README screenshots render in a fixed UI locale (default English) regardless of the host OS
    // locale, so the published set is language-consistent. Compose string resources read the JVM
    // default locale (see LocalAppLocale), so pinning it here covers both desktop and mobile.
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag(System.getProperty("skerry.screenshot.locale", "en")))
    val out = System.getProperty("skerry.screenshot.out", "/tmp/skerry_design.png")
    val viewName = System.getProperty("skerry.screenshot.view", "Terminal")
    val overlay = System.getProperty("skerry.screenshot.overlay", "")
    val live = System.getProperty("skerry.screenshot.live", "false").toBoolean()

    // Mobile variant: renders MobileDesignApp in a narrow scene. view=MobileTab (default Hosts).
    if (System.getProperty("skerry.screenshot.device", "desktop") == "mobile") {
        renderMobile(out, viewName, overlay, live); return
    }

    val state = DesktopDesignState()
    // view is a DesktopView name, or a HostSection name for the two work-area sections
    // (Terminal / RemoteDesktops) — those are not rail "views" any more.
    runCatching { state.showSection(HostSection.valueOf(viewName)) }
        .onFailure { runCatching { state.showView(DesktopView.valueOf(viewName)) } }
    // Terminal theme for visual review: -Dskerry.screenshot.termTheme=<id> (e.g. tokyo-night).
    System.getProperty("skerry.screenshot.termTheme")?.let { state.settings.chooseTerminalTheme(TerminalThemes.fromId(it)) }
    when (overlay) {
        "lock" -> state.lock()
        "modal" -> state.openModal(state.section) // form of the section being rendered
        "settings" -> {
            state.openSettings()
            // Settings tab to render: -Dskerry.screenshot.settingsTab=Appearance.
            System.getProperty("skerry.screenshot.settingsTab")?.let { tab ->
                runCatching { state.showSettingsTab(SettingsTab.valueOf(tab)) }
            }
        }
    }

    val keyGenerator = if (live) BouncyCastleSshKeyGenerator() else null
    val certificateInspector = if (live) SshjCertificateInspector() else null
    // One-level model: keychain secrets ([CredentialManagerController]) over an in-memory vault, so
    // the Vault section renders with live components; hosts reference a secret by credentialId.
    val credentials = if (live && keyGenerator != null) seededVault(keyGenerator) else null
    val boundCredentialId = credentials?.credentials?.firstOrNull()?.id
    val hosts = if (live) seededHosts(boundCredentialId = boundCredentialId) else null
    val sessions = if (live && hosts != null) seededSessions(hosts) else null
    // SFTP is a session sub-view: the rail view alone (showView above) does not switch the active
    // session's panel, so set it explicitly for the offscreen SFTP render. The local pane reads the
    // real user.home via okio, which is slow to load offscreen and would leak a personal path, so
    // point it at a throwaway seeded home tree first.
    if (viewName == "Sftp") {
        seedFakeHome()
        sessions?.setActiveView(SessionView.Sftp)
    }
    // Assistant panel open over the live terminal (-Dskerry.screenshot.assistantPanel=true): checks
    // that the pinned action row steps aside for the panel's own header.
    if (System.getProperty("skerry.screenshot.assistantPanel", "false").toBoolean()) state.toggleAssistant()
    // A recording plays in its own tab, so it can't be reached through the rail: open one directly.
    if (viewName == "Player") sessions?.openPlayer("deploy.cast", seededCast())
    val knownHosts = if (live) seededKnownHosts() else null
    val trustedCas = if (live) seededTrustedCas() else null
    val tunnels = if (live && hosts != null) seededTunnels(hosts) else null
    val ai = if (live) seededAi() else null
    // Built once outside the content lambda: recomposition must not recreate the controller
    // (a fresh instance would restart its async check and the notice would never settle).
    val updates = if (live) seededUpdates() else null

    // Stub window chrome (-Dskerry.screenshot.windowChrome=true): draws the custom window buttons
    // in the titlebar (as in the real undecorated window); drag/minimize/maximize are no-ops offscreen.
    val windowChrome = if (System.getProperty("skerry.screenshot.windowChrome", "false").toBoolean()) {
        WindowChrome(
            isMaximized = { false },
            onMinimize = {}, onToggleMaximize = {}, onClose = {},
            dragArea = { content -> content() },
        )
    } else null

    val content: @Composable () -> Unit = when (overlay) {
        "create" -> { { GateScreenPreview { LockWindowChrome(windowChrome) { DesktopCreateScreen(error = null, onCreate = { _, _ -> }) } } } }
        "unlock" -> { { GateScreenPreview { LockWindowChrome(windowChrome) { DesktopUnlockScreen(error = null, canUseBiometric = true, onUnlock = {}, onBiometric = {}, onForgotPassword = {}) } } } }
        "corrupted" -> { { GateScreenPreview { LockWindowChrome(windowChrome) { DesktopCorruptedScreen(onReset = {}) } } } }
        "reset" -> { { GateScreenPreview { LockWindowChrome(windowChrome) { DesktopResetScreen(onConfirm = {}, onCancel = {}) } } } }
        // The F4 editor is a modal opened by a key press, which an offscreen render can't send, and
        // a Dialog has no platform layer here — so its card ([FileEditorPanel]) is rendered directly.
        "editor" -> { { GateScreenPreview { EditorPreview() } } }
        // Trash section with a seeded trash: the settings overlay can't show it (the mock graph has
        // no vault), so the list is rendered standalone against an in-memory vault.
        "trash" -> { { GateScreenPreview { TrashPreview() } } }
        // Assistant panel with a seeded conversation: the feed's bubbles and command blocks can't be
        // reached offscreen (they need a live answer), so the panel is rendered standalone.
        "assistant" -> { { GateScreenPreview { AssistantPreview() } } }
        else -> { { DesktopDesignApp(state = state, hosts = hosts, sessions = sessions, knownHosts = knownHosts, trustedCas = trustedCas, credentials = credentials, keyGenerator = keyGenerator, certificateInspector = certificateInspector, tunnels = tunnels, ai = ai, updates = updates, windowChrome = windowChrome) } }
    }

    // Theme for visual review: -Dskerry.screenshot.theme=<system|light|dark> (default dark).
    val themeMode = ThemeMode.fromId(System.getProperty("skerry.screenshot.theme", "dark"))
    val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
        SkerryTheme(mode = themeMode) { content() }
    }
    // Pumps frames with a real pause so compose-resources can load fonts (async IO) and the fake
    // session can flush its output to the terminal.
    var img = scene.render(0)
    for (i in 1..80) {
        img = scene.render(i * 16_000_000L)
        Thread.sleep(16)
    }
    val data = img.encodeToData() ?: error("encode failed")
    File(out).writeBytes(data.bytes)
    scene.close()
    sessions?.disconnectAll() // detach fake session collectors before exit
    println("screenshot → $out (${File(out).length()} bytes)")
}

/**
 * Offscreen render of the mobile variant ([MobileDesignApp]) in a narrow 390x844 (density 2) scene.
 * `view` is a [MobileTab] name (default Hosts); `live=true` feeds the seeded [seededHosts] catalog,
 * otherwise the screen uses its built-in preview data. Run via the same `screenshotDesign` task
 * with `skerry.screenshot.device=mobile`.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderMobile(out: String, viewName: String, overlay: String, live: Boolean) {
    val state = MobileDesignState()
    val hosts = if (live) seededHosts() else null
    // Live AI controller (fake provider) so the More -> AI & privacy screen renders its real form
    // instead of an empty header (without a controller the screen draws only the header).
    val ai = if (live) seededAi() else null
    // Same hoisting as the desktop path: the controller must survive recomposition.
    val updates = if (live) seededUpdates() else null
    // Known-hosts manager is seeded only in live mode; otherwise the Known screen uses a built-in mock.
    val knownHosts = if (live) seededKnownHosts() else null
    val trustedCas = if (live) seededTrustedCas() else null
    // Keychain is seeded for the sheet overlay (live) so the auth picker shows saved secrets.
    val credentials = if (live) seededVault(BouncyCastleSshKeyGenerator()) else null
    // Key generator/inspector: the Vault tab uses it to compute fingerprints of seeded keys in live mode.
    val keyGenerator = if (live) BouncyCastleSshKeyGenerator() else null
    val deps = if (hosts != null) {
        AppDependencies(hosts = hosts, knownHosts = knownHosts, trustedCas = trustedCas, credentials = credentials, keyGenerator = keyGenerator, tunnels = seededTunnels(hosts))
    } else {
        AppDependencies()
    }
    // Seeded sessions (fake transport) for a live terminal; fed into MobileDesignApp as an external
    // manager so the offscreen render shows a real TerminalScreen without a network.
    val sessions = if (live && hosts != null) seededSessions(hosts) else null
    // view is a MobileTab (root) or MobileRoute (push screen) name. HostDetail opens on the catalog's
    // first host, Terminal on the active seeded session, so the offscreen render shows a live screen.
    val tab = runCatching { MobileTab.valueOf(viewName) }.getOrNull()
    if (tab != null) {
        state.select(tab)
    } else {
        runCatching { MobileRoute.valueOf(viewName) }.getOrNull()?.let { route ->
            if (route == MobileRoute.HostDetail) {
                state.openHost(deps.hosts?.hosts?.firstOrNull()?.id ?: MOBILE_PREVIEW_HOSTS.first().id)
            } else {
                state.push(route)
            }
        }
    }
    // New connection sheet over the current tab, on that tab's section (Hosts / Desktops).
    if (overlay == "sheet") state.openNewConn(if (tab == MobileTab.Desktops) HostSection.RemoteDesktops else HostSection.Terminal)
    // Scene width/height are in pixels: 780x1688 at density 2 = logical 390x844dp (phone).
    val themeMode = ThemeMode.fromId(System.getProperty("skerry.screenshot.theme", "dark"))
    val scene = ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
        SkerryTheme(mode = themeMode) {
            MobileDesignApp(deps = deps, state = state, sessions = sessions, aiOverride = ai, updatesOverride = updates)
            // overlay=monitor: the host-monitor sheet on a fixed snapshot. The sheet is opened from
            // the terminal's menu at runtime, which an offscreen render can't tap, and the live
            // poller publishes from a background dispatcher that never reaches this scene.
            if (overlay == "monitor") {
                // Drawn beside the app, so it needs its own font set (the app provides LocalFonts
                // only inside its own tree).
                GateScreenPreview {
                    MobileHostMonitorSheet(
                        metrics = PREVIEW_HOST_METRICS,
                        history = PREVIEW_METRICS_HISTORY,
                        netRxRate = PREVIEW_RX_RATE,
                        netTxRate = PREVIEW_TX_RATE,
                        availability = MetricsAvailability.Live,
                        onDismiss = {},
                    )
                }
            }
        }
    }
    var img = scene.render(0)
    for (i in 1..80) {
        img = scene.render(i * 16_000_000L)
        Thread.sleep(16)
    }
    val data = img.encodeToData() ?: error("encode failed")
    File(out).writeBytes(data.bytes)
    scene.close()
    sessions?.disconnectAll() // detach fake session collectors before exit
    println("screenshot(mobile) → $out (${File(out).length()} bytes)")
}
