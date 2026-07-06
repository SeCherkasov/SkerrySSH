package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpProgress
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.HostKeyMismatchStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.KnownHostsStore
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.SshKeyType
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.ui.AppDependencies
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.SessionView
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.vault.DesktopCorruptedScreen
import app.skerry.ui.vault.DesktopCreateScreen
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.vault.DesktopResetScreen
import app.skerry.ui.vault.DesktopUnlockScreen
import app.skerry.ui.app.DesktopView
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.mobile.MOBILE_PREVIEW_HOSTS
import app.skerry.ui.mobile.MobileDesignApp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberSpaceGrotesk

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
    runCatching { state.showView(DesktopView.valueOf(viewName)) }
    // Terminal theme for visual review: -Dskerry.screenshot.termTheme=<id> (e.g. tokyo-night).
    System.getProperty("skerry.screenshot.termTheme")?.let { state.chooseTerminalTheme(TerminalThemes.fromId(it)) }
    when (overlay) {
        "lock" -> state.lock()
        "modal" -> state.openModal()
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
    val knownHosts = if (live) seededKnownHosts() else null
    val ai = if (live) seededAi() else null

    val content: @Composable () -> Unit = when (overlay) {
        "create" -> { { GateScreenPreview { DesktopCreateScreen(error = null, onCreate = { _, _ -> }) } } }
        "unlock" -> { { GateScreenPreview { DesktopUnlockScreen(error = null, canUseBiometric = true, onUnlock = {}, onBiometric = {}, onForgotPassword = {}) } } }
        "corrupted" -> { { GateScreenPreview { DesktopCorruptedScreen(onReset = {}) } } }
        "reset" -> { { GateScreenPreview { DesktopResetScreen(onConfirm = {}, onCancel = {}) } } }
        else -> { { DesktopDesignApp(state = state, hosts = hosts, sessions = sessions, knownHosts = knownHosts, credentials = credentials, keyGenerator = keyGenerator, certificateInspector = certificateInspector, ai = ai) } }
    }

    val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
        SkerryTheme { content() }
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
    // Known-hosts manager is seeded only in live mode; otherwise the Known screen uses a built-in mock.
    val knownHosts = if (live) seededKnownHosts() else null
    // Keychain is seeded for the sheet overlay (live) so the auth picker shows saved secrets.
    val credentials = if (live) seededVault(BouncyCastleSshKeyGenerator()) else null
    // Key generator/inspector: the Vault tab uses it to compute fingerprints of seeded keys in live mode.
    val keyGenerator = if (live) BouncyCastleSshKeyGenerator() else null
    val deps = if (hosts != null) {
        AppDependencies(hosts = hosts, knownHosts = knownHosts, credentials = credentials, keyGenerator = keyGenerator)
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
    if (overlay == "sheet") state.openNewConn() // New connection sheet over the current tab
    // Scene width/height are in pixels: 780x1688 at density 2 = logical 390x844dp (phone).
    val scene = ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
        SkerryTheme { MobileDesignApp(deps = deps, state = state, sessions = sessions, aiOverride = ai) }
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

/**
 * Points `user.home` at a throwaway directory holding a small demo tree, so the SFTP local pane
 * (real okio filesystem over `user.home`) loads instantly offscreen and shows a neutral path
 * instead of the developer's actual home. Rebuilt on each run; only used by the SFTP screenshot.
 */
private fun seedFakeHome() {
    val dir = File(System.getProperty("java.io.tmpdir"), "skerry-demo-home")
    dir.deleteRecursively()
    dir.mkdirs()
    listOf("Projects", "Downloads", "Documents", ".ssh", ".config").forEach { File(dir, it).mkdirs() }
    File(dir, "notes.md").writeText("# notes\n")
    File(dir, "backup.tar.gz").writeText("demo\n")
    File(dir, "deploy.log").writeText("ok\n")
    File(dir, ".bashrc").writeText("export PATH\n")
    System.setProperty("user.home", dir.absolutePath)
}

/** Design font provider for standalone screen renders, bypassing [DesktopDesignApp]. */
@Composable
private fun GateScreenPreview(body: @Composable () -> Unit) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    CompositionLocalProvider(LocalFonts provides fonts) { body() }
}

/**
 * In-memory host catalog with demo profiles, for the offscreen render of a live sidebar only.
 * [boundCredentialId] (if given) is attached to a pair of hosts so Vault shows "Used by hosts".
 */
private fun seededHosts(boundCredentialId: String? = null): HostManagerController {
    val store = object : HostStore {
        private val items = LinkedHashMap<String, Host>()
        override fun all(): List<Host> = items.values.toList()
        override fun put(host: Host) { items[host.id] = host }
        override fun remove(id: String) { items.remove(id) }
        override fun reorder(transform: (List<Host>) -> List<Host>) {
            val updated = transform(items.values.toList())
            items.clear()
            updated.forEach { items[it.id] = it }
        }
    }
    listOf(
        Host("h1", "prod-web-01", "192.168.1.45", 22, "root", "Production", credentialId = boundCredentialId, tags = listOf("prod", "web")),
        Host("h2", "db-master", "192.168.1.50", 22, "root", "Production", credentialId = boundCredentialId, tags = listOf("prod", "db")),
        Host("h3", "homelab-pi", "10.0.0.12", 22, "pi", "Homelab", tags = listOf("docker")),
        Host("h4", "vps-edge", "vps.example.com", 2222, "deploy", null, tags = listOf("edge")),
    ).forEach(store::put)
    var seq = 0
    return HostManagerController(store) { "gen-${seq++}" }
}

/**
 * Seeds the vault with keychain secrets ([CredentialManagerController]) over an in-memory vault so
 * the offscreen render shows a live Vault section (key/password/certificate cards, used-by-hosts)
 * with real components and no files/master password. One ed25519 key is generated by a real
 * [SshKeyGenerator]; the first secret is attached to the demo hosts by credentialId.
 */
private fun seededVault(keyGenerator: SshKeyGenerator): CredentialManagerController {
    val vault = InMemoryVault()
    var credSeq = 0
    val credentials = CredentialManagerController(CredentialStore(vault)) { "cred-${credSeq++}" }

    val key = keyGenerator.generate(SshKeyType.ED25519, comment = "alice@skerry")
    credentials.save(CredentialDraft(label = "work-laptop", kind = CredentialKind.PRIVATE_KEY, privateKeyPem = key.privateKeyPem))
    credentials.save(CredentialDraft(label = "db-admin", kind = CredentialKind.PASSWORD, password = "hunter2"))
    credentials.save(
        CredentialDraft(label = "prod-access", kind = CredentialKind.CERTIFICATE, privateKeyPem = SEED_CERT_KEY, certificate = SEED_CERT),
    )

    return credentials
}

// NOT_A_SECRET: a throwaway ed25519 key generated for offscreen test seeding; not used in the
// production build (desktopMain Screenshot only), grants access to nothing. Marker so
// gitleaks/trufflehog don't false-positive on it.
// Throwaway ed25519 certificate (CA-signed, principals alice/deploy), only for seeding the offscreen
// render of Vault -> Certificates with real components, no files/master password. Same values as
// CertificateFixtures (shared/desktopTest), which is the source of truth; update both if regenerated.
private val SEED_CERT_KEY = """
    -----BEGIN OPENSSH PRIVATE KEY-----
    b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
    QyNTUxOQAAACCHmK+eOLE/3SmTEHz2mQerUTWuK10g2yXsCeRmqBhDJwAAAJCTquJek6ri
    XgAAAAtzc2gtZWQyNTUxOQAAACCHmK+eOLE/3SmTEHz2mQerUTWuK10g2yXsCeRmqBhDJw
    AAAECj4nk0xG00zyQDEYjZzkq4DYaRGzTDQCa722CqWQsnKIeYr544sT/dKZMQfPaZB6tR
    Na4rXSDbJewJ5GaoGEMnAAAADGFsaWNlQHNrZXJyeQE=
    -----END OPENSSH PRIVATE KEY-----
""".trimIndent() + "\n"

private const val SEED_CERT =
    "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAIJ/XTmChh23PUo43PsVebZVnBUh9yVb7r8UgCo6MD2XGAAAAIIeYr544sT/dKZMQfPaZB6tRNa4rXSDbJewJ5GaoGEMnAAAAAAAAACoAAAABAAAAE3NrZXJyeS10ZXN0QGVkMjU1MTkAAAATAAAABWFsaWNlAAAABmRlcGxveQAAAABlkgCAAAAAAHhh+AAAAAAAAAAAggAAABVwZXJtaXQtWDExLWZvcndhcmRpbmcAAAAAAAAAF3Blcm1pdC1hZ2VudC1mb3J3YXJkaW5nAAAAAAAAABZwZXJtaXQtcG9ydC1mb3J3YXJkaW5nAAAAAAAAAApwZXJtaXQtcHR5AAAAAAAAAA5wZXJtaXQtdXNlci1yYwAAAAAAAAAAAAAAMwAAAAtzc2gtZWQyNTUxOQAAACDGkIM6oT/mc8hunaUIY1avJGKsnfJB6yboLBsENiQ0kAAAAFMAAAALc3NoLWVkMjU1MTkAAABAwycZAnZtpvGb6wZDhWCcA6sa4Lz7sieexLCRkC7VNcZj23iiqej1B135atUIc0G7yR/g/TIzACfk2G3DHOYLAA== alice@skerry"

/** Trivial unencrypted in-memory vault, for offscreen identity seeding only (not for the app). */
private class InMemoryVault : Vault {
    private val records = LinkedHashMap<String, VaultRecord>()
    private val payloads = LinkedHashMap<String, ByteArray>()

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Success
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
    override fun lock() = Unit
    override fun reset() { records.clear(); payloads.clear() }
    override fun records(): List<VaultRecord> = records.values.filterNot { it.deleted }
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): List<VaultRecord> = emptyList()
    override fun openPayload(id: String): ByteArray? = payloads[id]
    override fun put(id: String, type: RecordType, payload: ByteArray) {
        payloads[id] = payload
        records[id] = VaultRecord(id, type, version = 1, updatedAt = "", deviceId = "screenshot", deleted = false, blob = ByteArray(0))
    }
    override fun remove(id: String) {
        payloads.remove(id)
        records[id]?.let { records[id] = it.copy(deleted = true) }
    }
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
    override fun verifyPassword(password: CharArray): Boolean = true
}

/**
 * Known-hosts manager with demo keys and one unresolved key-change event, so the offscreen render
 * shows a live table (firstSeen/Verified), a warning, and a fingerprint comparison panel with real
 * components ([KnownHostsController] -> [KnownHostsView]). In-memory, no files.
 */
private fun seededKnownHosts(): KnownHostsController {
    val ed = "ssh-ed25519"
    val store = object : KnownHostsStore {
        private val items = mutableListOf(
            KnownHost("prod-web-01", 22, ed, "SHA256:8c3F1a2bQzABCDEFGHIJKLMNpK9R", "2026-01-12T09:00:00Z"),
            KnownHost("db-master", 22, ed, "SHA256:2dE7bLm4xRABCDEFGHIJKLMNwQ1z", "2026-01-12T09:05:00Z"),
            KnownHost("nas-truenas", 22, ed, "SHA256:9aB0cTn2wE4rXp1kLm7sQ8vZabcd", "2026-03-04T18:30:00Z"),
            KnownHost("homelab-pi", 22, "ssh-rsa", "SHA256:5fG1hKp8sXYZ0123456789vB3nqrst", "2026-02-02T11:15:00Z"),
        )
        override fun all() = items.toList()
        override fun add(host: KnownHost) { items += host }
        override fun replace(host: KnownHost) {
            items.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
            items += host
        }
        override fun remove(host: String, port: Int, keyType: String) {
            items.removeAll { it.host == host && it.port == port && it.keyType == keyType }
        }
    }
    val mismatches = object : HostKeyMismatchStore {
        private val items = mutableListOf(
            HostKeyMismatch("nas-truenas", 22, ed, "SHA256:9aB0cTn2wE4rXp1kLm7sQ8vZabcd", "SHA256:Kp3xQ9zR1tWv7nB4mL0sJ2dFefgh", "2026-06-22T08:00:00Z"),
        )
        override fun all() = items.toList()
        override fun record(mismatch: HostKeyMismatch) {
            items.removeAll { it.host == mismatch.host && it.port == mismatch.port && it.keyType == mismatch.keyType }
            items += mismatch
        }
        override fun clear(host: String, port: Int, keyType: String) {
            items.removeAll { it.host == host && it.port == port && it.keyType == keyType }
        }
    }
    return KnownHostsController(store, mismatches) { "2026-06-22T12:00:00Z" }
}

/**
 * Live AI controller for offscreen renders of AI settings (desktop tab and mobile screen): a fake
 * provider with a canned reply, the first catalog model marked "installed", a BYOK key filled in,
 * quick-chat seeded with one exchange. Provider is set via `-Dskerry.screenshot.aiProvider`
 * (CLOUD/DEVICE/OFF, default CLOUD); OFF renders the "AI disabled" state.
 */
private fun seededAi(): app.skerry.ui.ai.AiAssistantController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val kind = runCatching {
        app.skerry.shared.ai.AiProviderKind.valueOf(System.getProperty("skerry.screenshot.aiProvider", "CLOUD"))
    }.getOrDefault(app.skerry.shared.ai.AiProviderKind.CLOUD)
    val first = app.skerry.shared.ai.local.LocalModelCatalog.models.first()
    var settings = app.skerry.shared.ai.AiSettings(apiKey = "sk-demo", provider = kind, localModelId = first.id)
    val fakeProvider = object : app.skerry.shared.ai.AiProvider {
        override fun chat(request: app.skerry.shared.ai.AiChatRequest): Flow<app.skerry.shared.ai.AiDelta> = flow {
            emit(app.skerry.shared.ai.AiDelta("Use scp: scp file.txt user@host:/path/ — it copies over SSH with the same credentials."))
        }
        override suspend fun close() {}
    }
    val controller = app.skerry.ui.ai.AiAssistantController(
        initialSettings = settings,
        persist = { settings = it },
        providerFactory = { fakeProvider },
        scope = scope,
        reload = { settings },
        localInstalled = { it.id == first.id },
        models = app.skerry.ui.ai.LocalModelController(
            installed = { it.id == first.id },
            fetch = { flow {} },
            remove = {},
            scope = scope,
        ),
    )
    if (controller.enabled) controller.ask("how do I copy a file to the server?")
    return controller
}

/**
 * Session manager over a fake transport ([fakeTransport]) with one open tab to the first host, so
 * the offscreen render shows a live terminal/toolbar/tabs with real components
 * ([SessionsController] -> [ConnectionController] -> [TerminalScreen]), no network.
 */
private fun seededSessions(hosts: HostManagerController): SessionsController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var n = 0
    val sessions = SessionsController(newId = { "s${n++}" }, controllerFactory = { ConnectionController(fakeTransport(), scope) })
    // Empty password: the fake transport ignores auth (see FakeConnection); there's no real handshake.
    val h = hosts.hosts.first()
    sessions.open(h.id, h.label, h.connectionSubtitle(), h.toTarget(), SshAuth.Password(""))
    hosts.hosts.getOrNull(1)?.let { h2 -> sessions.open(h2.id, h2.label, h2.connectionSubtitle(), h2.toTarget(), SshAuth.Password("")) }
    sessions.activate(sessions.sessions.first().id)
    // Seeds port forwards on the active session for a live Tunnels tab screenshot: waits for the
    // fake connection to come up (connect is async), then raises -L/-R/-D the same way the UI does
    // (PortForwardController). The fake forward is Active immediately.
    val ctrl = sessions.sessions.first().controller
    scope.launch {
        // uiState is Compose snapshot state; waits for the transition to Connected via snapshotFlow
        // (not a busy-spin) to properly subscribe to the snapshot system without spinning the CPU.
        snapshotFlow { ctrl.uiState }.first { it is ConnectionUiState.Connected }
        val pf = ctrl.openPortForwards()
        pf.addLocal(bindPort = 0, destHost = "10.0.0.5", destPort = 80)
        pf.addRemote(bindPort = 9000, destHost = "localhost", destPort = 3000)
        pf.addDynamic(bindPort = 1080)
    }
    return sessions
}

/** Fake SSH transport: the shell emits a canned banner+listing, then hangs until cancelled. */
private fun fakeTransport(): SshTransport = object : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = FakeConnection(target)
}

private class FakeConnection(private val target: SshTarget) : SshConnection {
    override val isConnected: Boolean = true
    override val cipher: String? = "chacha20-poly1305@openssh.com"

    // Realistic METRICS_COMMAND output so the offscreen render of the info panel shows live
    // metrics and host facts (CPU/memory/disk/uptime/load/OS/kernel/CPU) instead of "..." placeholders.
    override suspend fun exec(command: String): ExecResult = ExecResult(
        exitCode = 0,
        stdout = """
            cpu  100 0 100 800 0 0 0 0
            cpu  168 0 132 900 0 0 0 0
            @MEM
            Mem:     4000000000  2100000000  1000000000
            @DISK
            /dev/sda1  51475068 42000000 6900000 87% /
            @UPTIME
            372765.42 1488907.15
            @LOAD
            0.42 0.51 0.48 1/512 28931
            @OS
            PRETTY_NAME="Ubuntu 22.04.4 LTS"
            @KERNEL
            Linux 5.15.0-105-generic x86_64
            @CPU
            4
        """.trimIndent(),
        stderr = "",
    )
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeChannel(target)
    override suspend fun openSftp(): SftpClient = FakeSftpClient()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 50080)
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 9000)
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 1080)
    override suspend fun disconnect() {}
}

/** Fake forward: immediately "active", port echoed from the spec, for the offscreen tunnels table. */
private class FakePortForward(override val boundPort: Int) : PortForward {
    override val isActive: Boolean = true
    override var isPaused: Boolean = false
        private set
    override val bytesUp: Long = 0
    override val bytesDown: Long = 0
    override suspend fun pause() { isPaused = true }
    override suspend fun resume() { isPaused = false }
    override suspend fun close() = Unit
}

private class FakeChannel(target: SshTarget) : ShellChannel {
    private val prompt = "${target.username}@${target.host.substringBefore('.')}:~# "
    private val banner =
        "Last login: Sat Jun 21 14:22:10 2026 from 10.0.0.15\r\n" +
            "$prompt" + "ls -la\r\n" +
            "total 24\r\n" +
            "drwxr-xr-x  5 root root 4096 Jun 21 14:02 app\r\n" +
            "drwxr-xr-x  2 root root 4096 Jun 21 09:11 deploy\r\n" +
            "-rw-r--r--  1 root root  812 Jun 20 23:40 backup.tar.gz\r\n" +
            "$prompt" + "df -h /\r\n" +
            "Filesystem      Size  Used Avail Use% Mounted on\r\n" +
            "/dev/sda1        50G   42G  5.2G  87% /\r\n" +
            prompt

    override val isOpen: Boolean = true
    override val output: Flow<ByteArray> = flow {
        emit(banner.encodeToByteArray())
        awaitCancellation()
    }

    override suspend fun write(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}

/** Fake SFTP client with a canned `/var/www` listing, for the offscreen render of a live panel. */
private class FakeSftpClient : SftpClient {
    private val listing = listOf(
        SftpEntry("html", "/var/www/html", SftpEntryType.Directory, 4096, 0, 0b111_101_101),
        SftpEntry("releases", "/var/www/releases", SftpEntryType.Directory, 4096, 0, 0b111_101_101),
        SftpEntry("nginx.conf", "/var/www/nginx.conf", SftpEntryType.File, 3174, 0, 0b110_100_100),
        SftpEntry("robots.txt", "/var/www/robots.txt", SftpEntryType.File, 112, 0, 0b110_100_100),
        SftpEntry("deploy.sh", "/var/www/deploy.sh", SftpEntryType.File, 1843, 0, 0b111_101_101),
    )

    override suspend fun list(path: String): List<SftpEntry> = listing
    override suspend fun stat(path: String): SftpEntry? = null
    override suspend fun realpath(path: String): String = "/var/www"
    override suspend fun read(path: String): ByteArray = ByteArray(0)
    override suspend fun write(path: String, data: ByteArray) = Unit
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) = Unit
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) = Unit
    override suspend fun mkdir(path: String) = Unit
    override suspend fun remove(path: String) = Unit
    override suspend fun rmdir(path: String) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun close() = Unit
}
