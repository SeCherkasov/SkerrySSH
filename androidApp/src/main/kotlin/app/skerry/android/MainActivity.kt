package app.skerry.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.skerry.shared.ai.local.IsolatedLlmRuntime
import app.skerry.shared.ai.local.LocalModelStore
import app.skerry.shared.ai.local.ServiceLlmHostLauncher
import app.skerry.shared.ai.local.ModelDownloader
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.ssh.FileHostKeyMismatchStore
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.ssh.ProbeHostKeyVerifier
import app.skerry.shared.ssh.RoutingTransport
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.TofuHostKeyVerifier
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.sync.FileSyncStateStore
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.tunnel.VaultTunnelStore
import app.skerry.shared.vault.AndroidBiometricKeyStore
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.FileBioArtifactStore
import app.skerry.shared.vault.FileBiometricSupportStore
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.FileSecurityLog
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.AppDependencies
import app.skerry.ui.ai.LocalAiDeps
import app.skerry.ui.mobile.MobileDesignApp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.secure.WindowBridge
import app.skerry.ui.sftp.SafBridge
import app.skerry.ui.vault.AndroidLockContext
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.i18n.AppLocaleProvider
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.resolveTunnelHost
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.ResetScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.UUID

/**
 * Android entry point. [FragmentActivity] (not `ComponentActivity`) is required by
 * `androidx.biometric.BiometricPrompt`. Builds the dependency graph: local encrypted vault in the
 * private `filesDir`, cross-platform crypto (ionspin), okio-backed store.
 */
class MainActivity : FragmentActivity() {
    // Tunnel manager scope, tied to Activity lifetime. Cancelled in onDestroy so a recreate (rotation)
    // doesn't leave the old polling scope orphaned; active tunnels are dropped in that case.
    private var tunnelScope: CoroutineScope? = null

    // External cleanup on irrecoverable vault reset. The vault itself is already wiped and locked by
    // the controller, so this only clears data outside the vault (host profiles, known_hosts, tunnels).
    // Set in [buildDependencies]; passed to [MobileDesignApp].
    private var onVaultReset: (ResetScope) -> Unit = {}

    // One-time secret migration on unlock. Field because it references the dependency graph; set in
    // [buildDependencies], invoked from [MobileDesignApp].
    private var onVaultUnlocked: () -> Unit = {}

    override fun onDestroy() {
        tunnelScope?.cancel()
        tunnelScope = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Window handed to WindowBridge so the shared UI can toggle FLAG_SECURE on screens with
        // secrets (vault, master password entry); see SecureScreen. Weak reference, no Activity leak.
        WindowBridge.install(window)

        // Context for keyguard checks: auto-lock on background should trigger only when the device is
        // actually locked, not when a system picker is open (see deviceMandatesAutoLock).
        AndroidLockContext.appContext = applicationContext

        // Context for USB-OTG serial: the static SerialSystem reads it from here (enumerate + permission).
        app.skerry.shared.serial.SerialUsbBridge.install(applicationContext)

        // SFTP SAF pickers: launchers are registered in onCreate (ActivityResult API requires
        // registration before STARTED) and handed to SafBridge as launch lambdas so the shared UI code
        // stays Activity-independent. octet-stream for arbitrary binary downloads; text/plain for
        // key/certificate .pub export; "*/*" for any upload.
        val createDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri -> SafBridge.onCreateResult(uri) }
        val createTextDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri -> SafBridge.onCreateResult(uri) }
        val openDocument = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> SafBridge.onOpenResult(uri) }
        SafBridge.install(
            applicationContext,
            launchCreate = { name -> createDocument.launch(name) },
            launchCreateText = { name -> createTextDocument.launch(name) },
            launchOpen = { openDocument.launch(arrayOf("*/*")) },
        )

        // libsodium (ionspin) needs async init before the first VaultCrypto call; done blocking at
        // startup so the dependency graph is ready when built.
        runBlocking { initializeVaultCrypto() }

        val deps = buildDependencies()
        // Layout state with persisted collapsed host groups: the set of names survives restart.
        // Created once here and held by composition.
        val dir = filesDir
        setContent {
            // UI language lives at the root: a locale provider above MobileDesignApp reacts to
            // settings changes and recomposes the tree. onUiLanguageChange (from MobileDesignState)
            // updates this state and persists it; MobileDesignState keeps a copy for its dropdown.
            val currentUiLanguage = remember { mutableStateOf(readUiLanguage(dir)) }
            val designState = remember {
                MobileDesignState(
                    initialCollapsedGroups = readCollapsedGroups(dir),
                    onCollapsedGroupsChange = { writeCollapsedGroups(dir, it) },
                    initialTerminalFont = readTerminalFont(dir),
                    onTerminalFontChange = { writeTerminalFont(dir, it) },
                    initialTerminalFontSize = readTerminalFontSize(dir),
                    onTerminalFontSizeChange = { writeTerminalFontSize(dir, it) },
                    initialAllowServerClipboardWrite = readClipboardWrite(dir),
                    onAllowServerClipboardWriteChange = { writeClipboardWrite(dir, it) },
                    initialUiLanguage = currentUiLanguage.value,
                    onUiLanguageChange = { currentUiLanguage.value = it; writeUiLanguage(dir, it) },
                    initialAutoLock = readAutoLock(dir),
                    onAutoLockChange = { writeAutoLock(dir, it) },
                    initialTerminalScrollback = readTerminalScrollback(dir),
                    onTerminalScrollbackChange = { writeTerminalScrollback(dir, it) },
                    initialTerminalCursorStyle = readTerminalCursorStyle(dir),
                    onTerminalCursorStyleChange = { writeTerminalCursorStyle(dir, it) },
                )
            }
            AppLocaleProvider(currentUiLanguage.value) {
                MobileDesignApp(
                    deps,
                    state = designState,
                    onVaultReset = onVaultReset,
                    // Secret migration + reload + sync session restore.
                    onVaultUnlocked = onVaultUnlocked,
                )
            }
        }
    }

    /**
     * Collapsed host groups, persisted across restarts: group names in `collapsed_groups`, one per
     * line. Missing/unreadable → empty (all groups expanded). Write is best-effort.
     */
    private fun readCollapsedGroups(dir: File): Set<String> = runCatching {
        File(dir, "collapsed_groups").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    private fun writeCollapsedGroups(dir: File, groups: Set<String>) {
        // Names containing newlines can't be stored one-per-line; excluded to avoid splitting the file.
        // Snapshot taken synchronously; write happens off the UI thread.
        val snapshot = groups.filterNot { it.contains('\n') || it.contains('\r') }.joinToString("\n")
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "collapsed_groups").writeText(snapshot) }
        }
    }

    /**
     * Terminal font (More → Appearance → Font), persisted across restarts as a stable id
     * ([TerminalFont.id]) in `terminal_font`. Missing/unreadable/unknown → [TerminalFont.DEFAULT].
     * Write is best-effort, off the UI thread.
     */
    private fun readTerminalFont(dir: File): TerminalFont = runCatching {
        TerminalFont.fromId(File(dir, "terminal_font").readText().trim())
    }.getOrDefault(TerminalFont.DEFAULT)

    private fun writeTerminalFont(dir: File, font: TerminalFont) {
        val id = font.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_font").writeText(id) }
        }
    }

    /**
     * Auto-lock idle threshold (More → Security): stable [AutoLockDuration.id] in `auto_lock`.
     * Missing/unreadable/unknown → default (5 minutes). Write is best-effort, off the UI thread.
     */
    private fun readAutoLock(dir: File): AutoLockDuration = runCatching {
        AutoLockDuration.fromId(File(dir, "auto_lock").readText().trim())
    }.getOrDefault(AutoLockDuration.DEFAULT)

    private fun writeAutoLock(dir: File, duration: AutoLockDuration) {
        val id = duration.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "auto_lock").writeText(id) }
        }
    }

    /**
     * OSC 52 server clipboard-write gate (More → Appearance → Terminal): "true"/"false" in
     * `terminal_clipboard_write`. Missing/unreadable → false (off by default). Best-effort, off the UI thread.
     */
    private fun readClipboardWrite(dir: File): Boolean = runCatching {
        File(dir, "terminal_clipboard_write").readText().trim().toBoolean()
    }.getOrDefault(false)

    private fun writeClipboardWrite(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_clipboard_write").writeText(enabled.toString()) }
        }
    }

    /**
     * Terminal font size, px (More → Appearance → Font size): a number in `terminal_font_size`.
     * Missing/unreadable/outside [TERMINAL_FONT_SIZE_RANGE] → [DEFAULT_TERMINAL_FONT_SIZE].
     */
    private fun readTerminalFontSize(dir: File): Int {
        val px = runCatching { File(dir, "terminal_font_size").readText().trim().toInt() }
            .getOrDefault(DEFAULT_TERMINAL_FONT_SIZE)
        return if (px in TERMINAL_FONT_SIZE_RANGE) px else DEFAULT_TERMINAL_FONT_SIZE
    }

    private fun writeTerminalFontSize(dir: File, px: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_font_size").writeText(px.toString()) }
        }
    }

    /**
     * Scrollback depth for new sessions (More → Appearance → Terminal): a number in
     * `terminal_scrollback`. Missing/unreadable/outside [TERMINAL_SCROLLBACK_OPTIONS] →
     * [DEFAULT_TERMINAL_SCROLLBACK]. Write is best-effort, off the UI thread.
     */
    private fun readTerminalScrollback(dir: File): Int {
        val lines = runCatching { File(dir, "terminal_scrollback").readText().trim().toInt() }
            .getOrDefault(DEFAULT_TERMINAL_SCROLLBACK)
        return if (lines in TERMINAL_SCROLLBACK_OPTIONS) lines else DEFAULT_TERMINAL_SCROLLBACK
    }

    private fun writeTerminalScrollback(dir: File, lines: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_scrollback").writeText(lines.toString()) }
        }
    }

    /**
     * Default cursor style for new sessions (More → Appearance → Terminal): stable
     * [TerminalCursorStyle.id] in `terminal_cursor_style`. Missing/unreadable/unknown →
     * [TerminalCursorStyle.DEFAULT]. Write is best-effort, off the UI thread.
     */
    private fun readTerminalCursorStyle(dir: File): TerminalCursorStyle = runCatching {
        TerminalCursorStyle.fromId(File(dir, "terminal_cursor_style").readText().trim())
    }.getOrDefault(TerminalCursorStyle.DEFAULT)

    private fun writeTerminalCursorStyle(dir: File, style: TerminalCursorStyle) {
        val id = style.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_cursor_style").writeText(id) }
        }
    }

    /**
     * UI language (More → Appearance → Language): stable id ([UiLanguage.id]) in `ui_language`.
     * Missing/unreadable/unknown → [UiLanguage.DEFAULT] (System, follows OS locale). Write is
     * best-effort, off the UI thread.
     */
    private fun readUiLanguage(dir: File): UiLanguage = runCatching {
        UiLanguage.fromId(File(dir, "ui_language").readText().trim())
    }.getOrDefault(UiLanguage.DEFAULT)

    private fun writeUiLanguage(dir: File, language: UiLanguage) {
        val id = language.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "ui_language").writeText(id) }
        }
    }

    private fun buildDependencies(): AppDependencies {
        val dir = filesDir
        val crypto = IonspinVaultCrypto()
        val vault = FileVault(
            dir.resolve("vault.json").absolutePath.toPath(),
            crypto,
            deviceId(dir),
            FileSystem.SYSTEM,
        ) { System.currentTimeMillis().toString() }
        // Local (non-synced) security event log: master password changes, biometrics, unlocks. Written
        // by the controller behind the gate; read by the More → Security section. ISO instant clock so
        // securityMoment parses correctly.
        val securityLog = FileSecurityLog(
            dir.resolve("security_events.json").absolutePath.toPath(),
            FileSystem.SYSTEM,
            // File(...).toPath() (API 26) instead of Path.of (needs API 34) — compatible with minSdk 26.
            harden = { app.skerry.shared.io.PrivateConfig.harden(java.io.File(it.toString()).toPath()) },
        ) { Instant.now().toString() }
        val credentials = CredentialManagerController(CredentialStore(vault)) { UUID.randomUUID().toString() }
        // SSH transport (sshj, shared JVM source set). TOFU: a host's first key is remembered in the
        // vault (RecordType.KNOWN_HOST, synced across devices); a key change is rejected and logged to
        // the local (non-synced) known_hosts_mismatches so the known-hosts manager can warn and let the
        // user accept or reject the new key.
        val knownHostsStore = VaultKnownHostsStore(vault)
        val mismatchStore = FileHostKeyMismatchStore(dir.resolve("known_hosts_mismatches").toPath())
        // Live session transport: routes by connection type (SSH/Telnet/Serial). SSH carries the
        // TOFU verifier/known-hosts; Telnet/Serial are stateless (serial unsupported on Android).
        val transport = RoutingTransport(
            ssh = SshjTransport(
                TofuHostKeyVerifier(knownHostsStore, mismatchStore) { Instant.now().toString() },
            ),
        )
        val knownHosts = KnownHostsController(knownHostsStore, mismatchStore) { Instant.now().toString() }
        // Host profiles are HOST records in the vault; tree order lives in a layout record. The vault
        // is locked at startup (list is empty); the controller reloads on unlock via reload().
        val hostStore = VaultHostStore(vault)
        val hosts = HostManagerController(hostStore) { UUID.randomUUID().toString() }
        // Biometrics: key in AndroidKeyStore, prompt hosted by this Activity. Weak reference so the
        // store doesn't hold the Activity and returns null instead of a destroyed instance after recreate.
        val activityRef = WeakReference(this)
        val biometrics = VaultBiometrics(
            vault = vault,
            keyStore = AndroidBiometricKeyStore(applicationContext) { activityRef.get() },
            artifacts = FileBioArtifactStore(dir.resolve("vault.bio").absolutePath.toPath(), FileSystem.SYSTEM),
            deviceId = deviceId(dir),
            // Verdict for devices whose keystore never authorizes an auth-bound key (#23): persisted so
            // the settings row explains itself instead of offering a setup that cannot work.
            support = FileBiometricSupportStore(
                dir.resolve("vault.bio.unsupported").absolutePath.toPath(),
                FileSystem.SYSTEM,
                deviceId(dir),
            ),
        )
        // Global tunnels: saved forwards. Activated via a separate probe transport (read-only verifier)
        // so only an already-trusted host can be enabled, no silent TOFU here. Host/secret resolution
        // goes through the graph (hosts + credentials).
        val tunnelTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { tunnelScope = it }
        val tunnels = TunnelManager(
            store = VaultTunnelStore(vault),
            transport = tunnelTransport,
            resolve = { hostId -> resolveTunnelHost(hostId, findHost = hosts::find, findCredential = credentials::find) },
            scope = scope,
        ) { UUID.randomUUID().toString() }
        // Saved command snippets: SNIPPET records in the vault (commands may contain inline
        // credentials, hence shared encryption and E2E sync). Run into the terminal.
        val snippets = SnippetManager(VaultSnippetStore(vault)) { UUID.randomUUID().toString() }
        // Self-hosted sync: coordinator ties together the network client (Ktor+SRP), crypto, and vault.
        // Server binding is persisted in sync.json (non-secret: URL/accountId/deviceId); tokens and
        // password are not stored (re-auth via master password). deviceId is stable across records.
        // Sync cursor persists in sync-cursor.json (incremental pull after restart).
        // The teams coordinator is created after sync (it needs the session), but onSynced must call
        // it: the team key arrives via a TEAM record over the regular account sync. Late binding via var.
        var teamsForSync: app.skerry.ui.teams.TeamsCoordinator? = null
        val sync = SyncCoordinator(
            clientFactory = { url -> KtorSyncClient(url) },
            crypto = crypto,
            vault = vault,
            configStore = AndroidSyncConfigStore(File(dir, "sync.json")),
            // Persistent delta-sync cursor: survives restart, otherwise every start would be a full re-pull since 0.
            syncState = FileSyncStateStore(File(dir, "sync-cursor.json").toPath()),
            deviceIdProvider = { deviceId(dir) },
            deviceName = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Skerry Android",
            // On sign-in the account's dataKey changes; biometrics wrapped under the old key would
            // unlock to the wrong key (synced records would fail to decrypt). Disable it here; the user
            // re-enables it under the new key. Return whether biometrics was enabled, so the coordinator
            // can prompt the UI to re-register it.
            onDataKeyAdopted = {
                val wasEnabled = biometrics.isEnabled()
                biometrics.disable()
                wasEnabled
            },
            // Sync pulled records directly into the vault; reload managers on the main thread so
            // synced data appears without requiring a re-visit.
            onSynced = {
                lifecycleScope.launch(Dispatchers.Main) {
                    hosts.reload(); snippets.reload(); tunnels.reload(); knownHosts.refresh()
                }
                teamsForSync?.onAccountSynced()
            },
        )
        // Teams (zero-knowledge record sharing between accounts): coordinator on top of the same sync
        // session, per-team vaults in filesDir/teams (dataKey = teamKey from the account vault's TEAM
        // record), team-sync cursors in their own file.
        val teams = app.skerry.ui.teams.TeamsCoordinator(
            session = { sync.currentSession() },
            client = { sync.currentTeamClient() },
            vault = vault,
            crypto = crypto,
            teamVaults = app.skerry.shared.team.TeamVaults(
                dir = dir.resolve("teams").absolutePath.toPath(),
                crypto = IonspinVaultCrypto(),
                deviceId = deviceId(dir),
                fileSystem = FileSystem.SYSTEM,
                // File(...).toPath() (API 26) instead of Path.of (API 34) — compatible with minSdk 26.
                harden = { app.skerry.shared.io.PrivateConfig.harden(java.io.File(it.toString()).toPath()) },
                now = { Instant.now().toString() },
            ),
            teamState = FileSyncStateStore(File(dir, "team-cursor.json").toPath()),
            newTeamId = { UUID.randomUUID().toString() },
            onTeamsChanged = {
                lifecycleScope.launch(Dispatchers.Main) {
                    hosts.reload(); snippets.reload(); tunnels.reload()
                }
            },
        )
        teamsForSync = teams
        // A missing team key (dropped by an older client's delta sync) is only fixed by a full re-pull.
        teams.onKeyMissing = { sync.recoverFullPull() }
        sync.onTeamSignal = teams::onSignal
        // Manager reload and sync session restore on unlock (restoreSession manages its own scope).
        onVaultUnlocked = {
            hosts.reload()
            snippets.reload()
            tunnels.reload()
            knownHosts.refresh()
            sync.restoreSession()
        }
        // Clears data outside the vault on reset. The vault file is already wiped and locked, so
        // credentials aren't touched here (secrets reload when a new vault is created). Host
        // profiles/snippets/tunnels are vault records, already wiped by Vault.reset(); this only
        // clears non-vault data and reflects the now-empty vault in the managers.
        onVaultReset = { resetScope ->
            tunnels.closeAll()
            // Team keys lived in the wiped vault; team vaults can no longer be opened, so lock their in-memory trace.
            teams.lock()
            // Reset wiped the dataKey, so the biometric artifact (`vault.bio`) and the sealed sync
            // refresh token are wrapped under a dead key. Disable biometrics and disconnect sync so the
            // fingerprint doesn't point at a nonexistent key and settings don't show "Linked" with no
            // way to sign in.
            biometrics.disable()
            sync.disconnect()
            // The security event log belongs to the wiped vault; always clear it on reset.
            securityLog.clear()
            // Hosts/groups are wiped with the vault on any reset; clear their local UI trace
            // (collapsed state) too, or stale group names would remain visible.
            writeCollapsedGroups(dir, emptySet())
            // Factory reset: additionally clears trusted keys (non-vault) and terminal settings.
            if (resetScope == ResetScope.Everything) {
                knownHosts.mismatches.toList().forEach { knownHosts.reject(it) }
                knownHosts.entries.toList().forEach { knownHosts.forget(it) }
                writeTerminalFont(dir, TerminalFont.DEFAULT)
                writeTerminalFontSize(dir, DEFAULT_TERMINAL_FONT_SIZE)
                writeTerminalScrollback(dir, DEFAULT_TERMINAL_SCROLLBACK)
                writeTerminalCursorStyle(dir, TerminalCursorStyle.DEFAULT)
                writeClipboardWrite(dir, false)
                writeUiLanguage(dir, UiLanguage.DEFAULT)
                writeAutoLock(dir, AutoLockDuration.DEFAULT)
            }
            hosts.reload()
            snippets.reload()
            tunnels.reload()
        }
        // Local AI: GGUF models in the private filesDir/models (allowBackup=false so gigabyte-sized
        // weights don't break cloud backup); inference via Llamatik/llama.cpp arm64 in the ":llm"
        // process (LlmHostService) so a native abort doesn't take the app down. ctx 2048 keeps the
        // KV cache within a few hundred MiB on phone-class RAM.
        val localModelStore = LocalModelStore(FileSystem.SYSTEM, dir.resolve("models").absolutePath.toPath())
        val localAi = LocalAiDeps(
            store = localModelStore,
            downloader = ModelDownloader(FileSystem.SYSTEM, localModelStore),
            runtime = IsolatedLlmRuntime(ServiceLlmHostLauncher(this, contextLength = 2048)),
        )
        return AppDependencies(
            transport = transport,
            vncTransport = app.skerry.shared.vnc.VncTcpTransport(),
            hosts = hosts,
            vault = vault,
            credentials = credentials,
            knownHosts = knownHosts,
            // SSH key inspector/generator (BouncyCastle, shared JVM source set): fingerprints/generation in the Vault tab.
            keyGenerator = BouncyCastleSshKeyGenerator(),
            // SSH certificate inspector (sshj): Vault → Certificates parses *-cert.pub.
            certificateInspector = SshjCertificateInspector(),
            biometrics = biometrics,
            tunnels = tunnels,
            snippets = snippets,
            sync = sync,
            teams = teams,
            securityLog = securityLog,
            localAi = localAi,
        )
    }

    /** Stable device identifier for vault records (provenance + sync LWW). */
    private fun deviceId(dir: File): String {
        val file = File(dir, "device_id")
        if (file.exists()) return file.readText().trim()
        val id = UUID.randomUUID().toString()
        file.writeText(id)
        return id
    }
}
