package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.host.Host
import app.skerry.ui.files.FileEditController
import app.skerry.ui.files.FileEditorScreen
import app.skerry.shared.vault.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.TrashStore
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.TrashController
import app.skerry.ui.vault.TrashList
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberUiFont

/** Design font provider for standalone screen renders, bypassing [DesktopDesignApp]. */
/** Canned nginx.conf for the [FileEditorPanel] preview, over an in-memory source. */
@Composable
internal fun EditorPreview() {
    val scope = rememberCoroutineScope()
    val controller = remember {
        val text = """
            server {
                listen 443 ssl http2;
                server_name skerry.app;

                ssl_certificate     /etc/letsencrypt/live/skerry.app/fullchain.pem;
                ssl_certificate_key /etc/letsencrypt/live/skerry.app/privkey.pem;

                location / {
                    proxy_pass http://127.0.0.1:8080;
                    proxy_set_header Host ${'$'}host;
                }
            }
        """.trimIndent()
        val item = FileItem("nginx.conf", "/etc/nginx/sites-enabled/nginx.conf", FileItemType.File, text.length.toLong(), 0)
        FileEditController(PreviewFileSource(item, text), item, readOnly = false, scope = scope).also { it.open() }
    }
    FileEditorScreen(controller, onClose = {}, modifier = Modifier.fillMaxSize())
}

/** In-memory single-file source for [EditorPreview]. */
private class PreviewFileSource(private val item: FileItem, private val text: String) : FileContentBrowser {
    override val label = "prod-web-01"
    override suspend fun realpath(path: String) = path
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun stat(path: String): FileItem = item
    override suspend fun readFile(path: String, maxBytes: Long): ByteArray = text.encodeToByteArray()
    override suspend fun writeFile(path: String, data: ByteArray) = Unit
}

/**
 * The assistant panel with a canned conversation, for visual review of the feed: an answer carrying
 * a command block, a block holding several separately runnable commands, and a destructive command
 * (which renders in the warning colour and asks twice before running).
 */
@Composable
internal fun AssistantPreview() {
    val controller = remember {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var reply = ""
        val provider = object : app.skerry.shared.ai.AiProvider {
            override fun chat(request: app.skerry.shared.ai.AiChatRequest): Flow<app.skerry.shared.ai.AiDelta> =
                flow { emit(app.skerry.shared.ai.AiDelta(reply)) }
            override suspend fun close() {}
        }
        app.skerry.ui.ai.SessionAssistantController(
            app.skerry.shared.ai.AiPolicy.Balanced,
            settings = { app.skerry.shared.ai.AiSettings(apiKey = "sk-demo") },
            providerFactory = { provider },
            scope = scope,
        ).apply {
            reply = "The journal is the largest single consumer: **5.9 GiB** in `/var/log/journal`, " +
                "on a filesystem that is 87% full. Docker layers take another 21 GiB.\n" +
                "```bash\njournalctl --vacuum-size=500M\ndocker system prune -af\n```"
            ask("Which service is eating the disk?", outputs = emptyList())
            reply = "21 GiB in `/var/lib/docker`, of which 12.4 GiB are dangling layers built on this " +
                "host. Pruning frees roughly 12 GiB without touching running containers.\n" +
                "```\ndocker image prune -a --filter \"until=168h\"\n```"
            ask("And the docker layers?", outputs = emptyList())
            // One block holding several commands: what a model does when asked for a list, and each
            // line has to stay separately runnable.
            reply = "These are the ones worth having:\n```\ntop\nhtop\nvmstat 1 5\n```"
            ask("Give me the basic load commands", outputs = emptyList())
            // A genuinely destructive one, so the preview actually shows the warning colour and the
            // two-click arming the doc above promises.
            reply = "That frees the rotated logs, but it deletes them outright — there is no undo.\n" +
                "```\nrm -rf /var/log/*.gz\n```"
            ask("Can I just delete the rotated logs?", outputs = listOf("# ls /var/log", "# du -sh /var/log"))
        }
    }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize())
        app.skerry.ui.ai.AssistantPanel(
            controller = controller,
            terminal = null,
            modelLabel = "local · Qwen2.5 Coder 7B",
        )
    }
}

/**
 * Standalone render of the Trash list ([TrashList]) over an in-memory vault with a few deletions,
 * for visual review of Settings -> Trash (and the identical Android screen). The clock is fixed so
 * the "days left" column is stable between runs.
 */
@Composable
internal fun TrashPreview() {
    val controller = remember {
        val vault = InMemoryVault()
        var clock = 1_800_000_000_000L
        val trash = TrashStore(vault, now = { clock })
        VaultHostStore(vault, trash = trash).apply {
            put(Host(id = "h-1", label = "staging-web", address = "10.0.0.14", port = 22, username = "deploy"))
            remove("h-1")
        }
        clock -= 9L * 24 * 60 * 60 * 1000
        CredentialStore(vault, trash).apply {
            put(Credential("c-1", "db-admin", CredentialSecret.Password("hunter2")))
            remove("c-1")
        }
        clock -= 17L * 24 * 60 * 60 * 1000
        VaultSnippetStore(vault, trash).apply {
            put(Snippet("s-1", "tail syslog", "tail -f /var/log/syslog"))
            remove("s-1")
        }
        TrashController(trash, now = { 1_800_000_000_000L })
    }
    Box(Modifier.fillMaxSize().background(Skerry.colors.surfaceDeep).padding(32.dp)) {
        TrashList(controller)
    }
}

@Composable
internal fun GateScreenPreview(body: @Composable () -> Unit) {
    val fonts = DesignFonts(
        ui = rememberUiFont(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    CompositionLocalProvider(LocalFonts provides fonts) { body() }
}
