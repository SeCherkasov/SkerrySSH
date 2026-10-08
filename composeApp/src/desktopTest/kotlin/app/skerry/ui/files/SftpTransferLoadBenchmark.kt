package app.skerry.ui.files

import app.skerry.shared.ssh.HostKeyRefusal
import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshjTransport
import app.skerry.ui.sftp.TransferDirection
import app.skerry.shared.host.Host
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.io.PrivateConfig
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in comparison against OpenSSH sftp on the same isolated loopback sshd and disk corpus.
 * Set SKERRY_SFTP_LOAD, SKERRY_SSH_PORT/FINGERPRINT/KEY, SKERRY_SFTP_ROOT and
 * SKERRY_SFTP_KNOWN_HOSTS. Never point the root at user data: the fixture creates its own children.
 * Samples include SSH authentication/channel setup for both clients; hashing is outside timing.
 * This is local transport throughput and publication count, not WAN or rendered-frame latency.
 */
class SftpTransferLoadBenchmark {
    /** Seed an isolated encrypted fixture for manual UI checks; never touches the user's vault. */
    @Test
    fun prepareLiveUiVault() = runBlocking {
        val folder = System.getenv("SKERRY_SFTP_UI_VAULT_DIR") ?: return@runBlocking
        val dir = Path.of(folder)
        PrivateConfig.ensureDir(dir)
        check(!Files.exists(dir.resolve("vault.json"))) { "UI fixture must be fresh" }
        initializeVaultCrypto()
        val vault = FileVault(dir.resolve("vault.json").toString().toPath(), IonspinVaultCrypto(), "sftp-ui-fixture",
            FileSystem.SYSTEM, harden = { PrivateConfig.harden(Path.of(it.toString())) }, now = { "2026-10-08T00:00:00Z" })
        try {
            vault.create("Sftp-UI-test-2026!".toCharArray())
            CredentialStore(vault).put(Credential("fixture-key", "SFTP fixture", CredentialSecret.PrivateKey(
                Files.readString(Path.of(required("SKERRY_SSH_KEY"))),
            )))
            for ((label, address) in listOf("Desktop SFTP fixture" to "127.0.0.1", "Android SFTP fixture" to "10.0.2.2")) {
                VaultHostStore(vault).put(Host(label, label, address, required("SKERRY_SSH_PORT").toInt(),
                    System.getProperty("user.name"), credentialId = "fixture-key"))
                VaultKnownHostsStore(vault).add(KnownHost(address, required("SKERRY_SSH_PORT").toInt(),
                    "ssh-ed25519", required("SKERRY_SSH_FINGERPRINT")))
            }
        } finally {
            vault.lock()
        }
    }

    @Test
    fun largeFileAndThousandSmallFiles() {
        if (System.getenv("SKERRY_SFTP_LOAD") != "1") return
        val root = Files.createDirectories(Path.of(required("SKERRY_SFTP_ROOT")))
        val corpus = Files.createTempDirectory(root, "corpus-")
        val recording = System.getenv("SKERRY_SFTP_JFR")?.let { Recording(Configuration.getConfiguration("profile")) }
        try {
            recording?.start()
            val large = corpus.resolve("large.bin")
            val block = ByteArray(1_048_576) { (it * 31).toByte() }
            Files.newOutputStream(large).use { out -> repeat(128) { out.write(block) } }
            val small = Files.createDirectory(corpus.resolve("small"))
            repeat(1_000) { Files.write(small.resolve("file-$it.bin"), block.copyOf(4_096)) }
            for (files in listOf(listOf(large), Files.list(small).use { it.sorted().toList() })) {
                val samples = mutableMapOf<String, MutableList<Long>>()
                repeat(4) { round ->
                    for (client in if (round % 2 == 0) listOf("skerry", "openssh") else listOf("openssh", "skerry")) {
                        val upload = Files.createTempDirectory(corpus, "upload-")
                        val download = Files.createTempDirectory(corpus, "download-")
                        val times = if (client == "skerry") skerry(files, upload, download) else openssh(files, upload, download)
                        for ((direction, elapsed) in times) {
                            if (round > 0) samples.getOrPut("$client-$direction") { mutableListOf() } += elapsed
                            println("LOAD SFTP client=$client files=${files.size} direction=$direction round=$round ms=$elapsed")
                        }
                        for (file in files) {
                            assertEquals(-1L, Files.mismatch(file, upload.resolve(file.fileName)))
                            assertEquals(-1L, Files.mismatch(file, download.resolve(file.fileName)))
                        }
                        upload.toFile().deleteRecursively()
                        download.toFile().deleteRecursively()
                    }
                }
                samples.forEach { (label, values) -> println("BENCH SFTP files=${files.size} $label medianMs=${values.sorted()[1]}") }
            }
        } finally {
            recording?.stop()
            System.getenv("SKERRY_SFTP_JFR")?.let { recording?.dump(Path.of(it)) }
            recording?.close()
            corpus.toFile().deleteRecursively()
        }
    }

    private fun skerry(files: List<Path>, upload: Path, download: Path): Map<String, Long> = runBlocking {
        val port = required("SKERRY_SSH_PORT").toInt()
        val fingerprint = required("SKERRY_SSH_FINGERPRINT")
        val verifier = HostKeyVerifier { offer ->
            if (offer.host == "127.0.0.1" && offer.port == port && offer.fingerprint == fingerprint) null
            else HostKeyRefusal.KeyChanged
        }
        val target = SshTarget("127.0.0.1", port, System.getProperty("user.name"))
        val auth = SshAuth.PublicKey(Files.readString(Path.of(required("SKERRY_SSH_KEY"))))
        val result = mutableMapOf<String, Long>()
        for (direction in listOf(TransferDirection.Upload, TransferDirection.Download)) {
            val start = System.nanoTime()
            val connection = SshjTransport(verifier).connect(target, auth)
            try {
                println("BENCH SFTP cipher=${connection.cipher}")
                val client = connection.openSftp()
                try {
                    val queue = TransferQueue()
                    queue.activate(queue.enqueue(direction, files.first().fileName.toString()))
                    var callbacks = 0
                    var publications = 0
                    for ((index, file) in files.withIndex()) {
                        val name = file.fileName.toString()
                        val size = Files.size(file)
                        queue.step(name, index + 1, files.size, 0, size)
                        val progress: (Long, Long) -> Unit = { done, total ->
                            callbacks++
                            val before = queue.list.single()
                            queue.step(name, index + 1, files.size, done, total)
                            if (before !== queue.list.single()) publications++
                        }
                        if (direction == TransferDirection.Upload) client.upload(file.toString(), upload.resolve(name).toString(), progress)
                        else client.download(upload.resolve(name).toString(), download.resolve(name).toString(), progress)
                        queue.fileFinished(size)
                    }
                    queue.end(TransferStatus.Done)
                    assertEquals(files.sumOf { Files.size(it) }, queue.list.single().bytesDone)
                    println("BENCH SFTP telemetry files=${files.size} direction=$direction callbacks=$callbacks publications=$publications")
                } finally {
                    client.close()
                }
            } finally {
                connection.disconnect()
            }
            result[direction.toString()] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }
        result
    }

    private fun openssh(files: List<Path>, upload: Path, download: Path): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (direction in listOf(TransferDirection.Upload, TransferDirection.Download)) {
            val batch = Files.createTempFile("skerry-sftp-batch-", ".txt")
            val log = Files.createTempFile("skerry-sftp-cli-", ".log")
            try {
                Files.writeString(batch, files.joinToString("\n") { file ->
                    val name = file.fileName
                    if (direction == TransferDirection.Upload) "put \"$file\" \"${upload.resolve(name)}\""
                    else "get \"${upload.resolve(name)}\" \"${download.resolve(name)}\""
                } + "\n")
                val start = System.nanoTime()
                val process = ProcessBuilder("sftp", "-q", "-b", batch.toString(), "-P", required("SKERRY_SSH_PORT"),
                    "-i", required("SKERRY_SSH_KEY"), "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes",
                    "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=${required("SKERRY_SFTP_KNOWN_HOSTS")}",
                    "${System.getProperty("user.name")}@127.0.0.1")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                awaitOpenSsh(process, log)
                result[direction.toString()] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            } finally {
                Files.deleteIfExists(batch)
                Files.deleteIfExists(log)
            }
        }
        return result
    }

    private fun awaitOpenSsh(process: Process, log: Path) {
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "OpenSSH transfer timed out")
            assertEquals(0, process.exitValue(), Files.readString(log).takeLast(2_000))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun required(name: String): String = checkNotNull(System.getenv(name)) { "Missing $name" }
}
