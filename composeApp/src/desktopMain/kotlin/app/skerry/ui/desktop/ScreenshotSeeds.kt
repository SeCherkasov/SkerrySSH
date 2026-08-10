package app.skerry.ui.desktop

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.host.HostStore
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.CastEvent
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.HostKeyMismatchStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.KnownHostsStore
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.TunnelStore
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.TunnelTelemetry
import app.skerry.ui.tunnel.TunnelStatus
import app.skerry.ui.tunnel.TunnelResolution
import app.skerry.ui.tunnel.TunnelUnavailable
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.SshKeyType
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.HostManagerController
import app.skerry.shared.ssh.TrustedCa
import app.skerry.shared.ssh.TrustedCaStore
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.known.TrustedCaController
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.ui.session.SessionsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Points `user.home` at a throwaway directory holding a small demo tree, so the SFTP local pane
 * (real okio filesystem over `user.home`) loads instantly offscreen and shows a neutral path
 * instead of the developer's actual home. Rebuilt on each run; only used by the SFTP screenshot.
 */
internal fun seedFakeHome() {
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

/**
 * In-memory host catalog with demo profiles, for the offscreen render of a live sidebar only.
 * [boundCredentialId] (if given) is attached to a pair of hosts so Vault shows "Used by hosts".
 */
internal fun seededHosts(boundCredentialId: String? = null): HostManagerController {
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
        // Remote desktops: their own section in the shell, so the demo catalog seeds both kinds.
        Host("h5", "lab-desktop", "10.0.0.30", 5900, "", "Homelab", connectionType = ConnectionType.VNC),
        Host("h6", "win-bench", "10.0.0.31", 5901, "", null, connectionType = ConnectionType.VNC, tags = listOf("lab")),
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
internal fun seededVault(keyGenerator: SshKeyGenerator): CredentialManagerController {
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
internal class InMemoryVault : Vault {
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
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
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

/** A short canned recording for the player render (`view=Player`). */
internal fun seededCast(): Asciicast = Asciicast(
    columns = 138,
    rows = 30,
    title = "deploy.cast",
    events = listOf(
        CastEvent(0.0, "\u001b[36mroot@prod-web-01\u001b[0m:~# ./deploy.sh\r\n"),
        CastEvent(0.2, "  \u001b[32m✓\u001b[0m build      12.4s\r\n"),
        CastEvent(0.4, "  \u001b[32m✓\u001b[0m tests      31.2s\r\n"),
        CastEvent(0.6, "  \u001b[32m✓\u001b[0m rollout     4.8s\r\n"),
        CastEvent(0.8, "\r\ndeployed \u001b[1mv0.1.9\u001b[0m to 3 nodes\r\n"),
        // Full-width rule: shows at a glance whether the recording fills the pane edge to edge.
        CastEvent(1.0, "\u001b[2m" + "\u2500".repeat(138) + "\u001b[0m"),
    ),
)

/**
 * Tunnel manager over an in-memory store and the fake transport, so the offscreen Ports render
 * shows the live table (one active forward with its browser link) and a real service scan against
 * [FakeSshConnection]'s canned `ss` output.
 */
internal fun seededTunnels(hosts: HostManagerController, scope: CoroutineScope = seedScope()): TunnelManager {
    val store = object : TunnelStore {
        private val entries = mutableListOf<Tunnel>()
        override fun all(): List<Tunnel> = entries.toList()
        override fun put(tunnel: Tunnel) {
            val i = entries.indexOfFirst { it.id == tunnel.id }
            if (i >= 0) entries[i] = tunnel else entries += tunnel
        }
        override fun remove(id: String) { entries.removeAll { it.id == id } }
    }
    val hostIds = hosts.hosts.map { it.id }
    fun hostAt(i: Int) = hostIds.getOrElse(i) { hostIds.first() }
    store.put(Tunnel("t1", "web tunnel", hostAt(0), TunnelDirection.Local, "127.0.0.1", 8080, "10.0.0.5", 80, autostart = true))
    store.put(Tunnel("t2", "app callback", hostAt(1), TunnelDirection.Remote, "0.0.0.0", 9000, "localhost", 3000))
    store.put(Tunnel("t3", "socks", hostAt(2), TunnelDirection.Dynamic, "127.0.0.1", 1080, null, null, autostart = true))
    var next = 0
    val manager = TunnelManager(
        store = store,
        transport = fakeTransport(),
        resolve = { hostId ->
            val host = hosts.find(hostId)
            if (host == null) {
                TunnelResolution.Unavailable(TunnelUnavailable.HostNotFound)
            } else {
                TunnelResolution.Ready(host.toTarget(), SshAuth.Password("demo"))
            }
        },
        scope = scope,
    ) { "seed-${next++}" }
    manager.activate("t1")
    // The scene renders one frame at t=0, so everything the one-second telemetry poll feeds — the
    // traffic column, the throughput sparkline — would be empty. Wait for the forward, then drive
    // the poll by hand to fill the window.
    runBlocking { withTimeoutOrNull(2000) { while (manager.find("t1")?.status !is TunnelStatus.Active) delay(10) } }
    repeat(TunnelTelemetry.HISTORY_CAPACITY) { manager.pollTelemetry() }
    // -Dskerry.screenshot.portsScan=true renders the Services panel instead of the tunnel editor
    // (the offscreen scene can't press Find services / Scan itself).
    if (System.getProperty("skerry.screenshot.portsScan", "false").toBoolean()) manager.services.scan(hostAt(0))
    return manager
}

/**
 * Known-hosts manager with demo keys and one unresolved key-change event, so the offscreen render
 * shows a live table (firstSeen/Verified), a warning, and a fingerprint comparison panel with real
 * components ([KnownHostsController] -> [KnownHostsView]). In-memory, no files.
 */
/**
 * Trusted certificate authorities for the offscreen render of the known-hosts screen: two entries
 * over an in-memory store, so the CA section shows real rows rather than its empty state.
 */
internal fun seededTrustedCas(): TrustedCaController {
    val store = object : TrustedCaStore {
        private val items = mutableListOf(
            TrustedCa("ca-1", "*.prod.example.com", "ssh-ed25519", "AAAA", "SHA256:Qz8kR2mVpL4tXw9nB7sJ1dF0", "Production CA", "2026-05-02T10:00:00Z"),
            TrustedCa("ca-2", "*.staging.example.com,!admin.staging.example.com", "ecdsa-sha2-nistp256", "AAAA", "SHA256:7hT4bN0xZq2wE5rY8uI3oP6a", "Staging CA", "2026-06-18T10:00:00Z"),
        )
        override fun all() = items.toList()
        override fun put(ca: TrustedCa) {
            items.removeAll { it.id == ca.id }
            items += ca
        }
        override fun remove(id: String) {
            items.removeAll { it.id == id }
        }
    }
    return TrustedCaController(store, { null }, newId = { "ca-x" }, now = { "2026-06-22T12:00:00Z" })
}

internal fun seededKnownHosts(): KnownHostsController {
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
/**
 * Live update-notice controller for offscreen renders: the "check for updates" toggle is on, and
 * `-Dskerry.screenshot.updateAvailable=true` makes the canned newer release visible (status-bar
 * item, About notice, More-row subtitle); without the flag the check finds nothing.
 */
internal fun seededUpdates(): app.skerry.ui.update.UpdateNoticeController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val available = System.getProperty("skerry.screenshot.updateAvailable", "false").toBoolean()
    var settings = app.skerry.shared.update.UpdateSettings()
    val controller = app.skerry.ui.update.UpdateNoticeController(
        initialSettings = settings,
        persist = { settings = it },
        check = {
            if (available) {
                app.skerry.shared.update.AvailableUpdate("0.2.0", "https://github.com/SeCherkasov/SkerrySSH/releases/tag/v0.2.0")
            } else {
                null
            }
        },
        scope = scope,
        reload = { settings },
    )
    controller.refresh()
    return controller
}

internal fun seededAi(scope: CoroutineScope = seedScope()): app.skerry.ui.ai.AiAssistantController {
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
internal fun seededSessions(hosts: HostManagerController, scope: CoroutineScope = seedScope()): SessionsController {
    var n = 0
    val sessions = SessionsController(
        newId = { "s${n++}" },
        controllerFactory = { ConnectionController(fakeTransport(), scope) },
        // Remote-desktop tabs render against a still fake picture, so the Desktops section can be
        // reviewed offscreen without a VNC/RDP server.
        vncControllerFactory = { RemoteDesktopController(scope) },
        openVncSession = { target, _ -> fakeRemoteDesktop(target.host) },
    )
    // Empty password: the fake transport ignores auth (see FakeConnection); there's no real handshake.
    val h = hosts.hosts.first()
    sessions.open(h.id, h.label, h.connectionSubtitle(), h.toTarget(), SshAuth.Password(""))
    hosts.hosts.getOrNull(1)?.let { h2 -> sessions.open(h2.id, h2.label, h2.connectionSubtitle(), h2.toTarget(), SshAuth.Password("")) }
    sessions.activate(sessions.tabs.first().id)
    // Seeds port forwards on the active session for a live Tunnels tab screenshot: waits for the
    // fake connection to come up (connect is async), then raises -L/-R/-D the same way the UI does
    // (PortForwardController). The fake forward is Active immediately.
    // Split grid for the pane screenshots: -Dskerry.screenshot.panes=4 fills the active tab with
    // that many connected panes and turns synchronized input on, which is what the work bar's split
    // title is there to show.
    val panes = System.getProperty("skerry.screenshot.panes", "1").toIntOrNull() ?: 1
    val tabId = sessions.tabs.first().id
    repeat((panes - 1).coerceAtLeast(0)) { i ->
        // Loud rather than a quietly under-filled picture: a grid that refuses another pane
        // means the requested -Dskerry.screenshot.panes cannot be rendered at all.
        val paneId = checkNotNull(sessions.addPane(tabId)) { "pane $i of $panes refused: grid is full" }
        val host = hosts.hosts[(i + 1) % hosts.hosts.size]
        sessions.connectPane(tabId, paneId, host.id, host.label, host.connectionSubtitle(), host.toTarget(), SshAuth.Password(""))
    }
    if (panes > 1) {
        sessions.toggleSyncInput(tabId)
        sessions.focusPane(tabId, sessions.tabs.first().panes.first().id)
    }
    val ctrl = sessions.tabs.first().focusedPane.controller
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

internal val SEEDED_SS_OUTPUT = """
    State  Recv-Q Send-Q Local Address:Port Peer Address:Port Process
    LISTEN 0      128            0.0.0.0:22        0.0.0.0:*    users:(("sshd",pid=640,fd=3))
    LISTEN 0      511            0.0.0.0:80        0.0.0.0:*    users:(("nginx",pid=901,fd=6))
    LISTEN 0      4096         127.0.0.1:5432      0.0.0.0:*    users:(("postgres",pid=812,fd=5))
    LISTEN 0      511          127.0.0.1:6379      0.0.0.0:*    users:(("redis-server",pid=733,fd=7))
    LISTEN 0      128             0.0.0.0:8080     0.0.0.0:*    users:(("java",pid=1204,fd=41))
""".trimIndent()

/**
 * Scope a seed runs its own background work in when the caller does not supply one.
 *
 * The offscreen renders live for one frame and then the JVM exits, so nothing there needs to cancel
 * it. A test run does not exit between tests: it builds a shell per test, and a manager whose
 * telemetry poll keeps waking every second would outlive its composition a hundred times over. Those
 * callers pass a scope of their own and cancel it.
 */
internal fun seedScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
