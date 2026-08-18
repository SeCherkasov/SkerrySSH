package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import app.skerry.shared.process.isWindows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The real desktop host: a child JVM is started, connects back over its Unix socket and answers.
 * Deliberately uses a model path that does not exist — the answer travels the full pipeline
 * (spawn -> socket -> protocol -> typed failure) without needing multi-gigabyte weights. The native
 * library itself is loaded (`LlamaBridge` pulls it in before the model path is ever checked), only
 * the weights are not, which is why this is also what catches a native that cannot be loaded at
 * all: llamatik 1.10.0 died here with SIGILL on CI (dependabot PR #268).
 */
class ProcessLlmHostLauncherTest {

    /**
     * A stand-in for the app's own launcher. `sh` forks rather than execs its last command, and the
     * child inherits this JVM's stdout — an orphaned `sleep` outlives destroyForcibly() and holds
     * that pipe open until Gradle gives up on the worker, so a script that waits must `exec`.
     */
    private fun writeLauncher(path: Path, body: String) {
        Files.writeString(path, "#!/bin/sh\n$body\n")
        path.toFile().setExecutable(true)
    }

    private val request = AiChatRequest("local-it", listOf(AiMessage(AiRole.USER, "Say OK.")), maxOutputTokens = 8)

    @Test
    fun `reports a missing model through a real child process`() = runBlocking {
        val runtime = IsolatedLlmRuntime(ProcessLlmHostLauncher(contextLength = 512))

        try {
            val error = withTimeout(2.minutes) {
                assertFailsWith<AiException> {
                    runtime.generate("/nonexistent/model.gguf".toPath(), request).toList()
                }
            }

            assertEquals(AiException.Kind.INVALID_REQUEST, error.kind)
        } finally {
            // Not after the assertion: a failing one would leave the child JVM holding this worker's
            // inherited stdout until Gradle gave up on it.
            runtime.close()
        }
    }

    @Test
    fun `the jpackage restart marker does not leak into the child`() = runBlocking {
        assumeTrue(!isWindows, "POSIX shell and signals only")
        // Planted by the Gradle test task to mimic a packaged app's JVM. A child launcher that
        // inherits it feeds our --llm-host flag to the JVM and dies before reaching main.
        assertEquals("1", System.getenv("_JPACKAGE_LAUNCHER"), "the test task must plant the marker")

        val recorded = Files.createTempFile("skerry-llm-env", ".txt")
        val launcher = Files.createTempFile("skerry-fake-launcher", ".sh")
        try {
            writeLauncher(launcher, "printenv > '$recorded'")
            val runtime = IsolatedLlmRuntime(
                ProcessLlmHostLauncher(contextLength = 512, selfCommand = launcher.toString()),
            )

            // The fake launcher exits without connecting back, so generation fails; only the
            // environment it saw matters here.
            assertFailsWith<AiException> { runtime.generate("/m.gguf".toPath(), request).toList() }

            val environment = Files.readString(recorded)
            assertFalse(
                environment.lineSequence().any { it.startsWith("_JPACKAGE_LAUNCHER=") },
                "the child saw the marker:\n$environment",
            )
        } finally {
            Files.deleteIfExists(recorded)
            Files.deleteIfExists(launcher)
        }
    }

    @Test
    fun `a child that dies before connecting reports how it died`() = runBlocking {
        assumeTrue(!isWindows, "POSIX shell and signals only")
        val launcher = Files.createTempFile("skerry-fake-launcher", ".sh")
        try {
            // Kills itself the way the native does when it meets an instruction this CPU has not
            // got — the AVX-512 crash class llamatik is pinned against (see libs.versions.toml).
            writeLauncher(launcher, "kill -ILL $$")
            val runtime = IsolatedLlmRuntime(
                ProcessLlmHostLauncher(contextLength = 512, selfCommand = launcher.toString()),
            )

            val error = assertFailsWith<AiException> { runtime.generate("/m.gguf".toPath(), request).toList() }

            assertEquals(AiException.Kind.ENGINE_CRASHED, error.kind)
            // 128 + SIGILL. Without it, a host killed by an instruction the CPU has not got and a
            // host started with a broken classpath are the same sentence in a stack trace.
            assertContains(error.message.orEmpty(), "exit code 132 (signal 4)", message = error.message.orEmpty())
        } finally {
            Files.deleteIfExists(launcher)
        }
    }

    @Test
    fun `a child that never connects is given up on, not waited for`() = runBlocking {
        assumeTrue(!isWindows, "POSIX shell and signals only")
        val launcher = Files.createTempFile("skerry-fake-launcher", ".sh")
        try {
            writeLauncher(launcher, "exec sleep 30")
            val runtime = IsolatedLlmRuntime(
                ProcessLlmHostLauncher(contextLength = 512, selfCommand = launcher.toString(), startTimeoutMillis = 500),
            )

            val error = assertFailsWith<AiException> { runtime.generate("/m.gguf".toPath(), request).toList() }

            // A live child that never connects is a different fault from one that died, and saying
            // so is the whole reason the give-up path has its own branch.
            assertEquals(AiException.Kind.ENGINE_CRASHED, error.kind)
            assertContains(error.message.orEmpty(), "did not answer in 500ms", message = error.message.orEmpty())
        } finally {
            Files.deleteIfExists(launcher)
        }
    }

    @Test
    fun `cancelling a start is a cancellation, not a crashed engine`() = runBlocking {
        assumeTrue(!isWindows, "POSIX shell and signals only")
        val launcher = Files.createTempFile("skerry-fake-launcher", ".sh")
        try {
            writeLauncher(launcher, "exec sleep 30")
            val runtime = IsolatedLlmRuntime(
                ProcessLlmHostLauncher(contextLength = 512, selfCommand = launcher.toString()),
            )

            // The sibling test execs the same `sleep`, and destroyForcibly() does not wait — one of
            // its corpses can still be in `descendants()` here. Anything already running is not
            // this test's child and must not be mistaken for it.
            val strangers = sleepers().map { it.pid() }.toSet()
            var caught: Throwable? = null
            val job = launch(Dispatchers.Default) {
                runCatching { runtime.generate("/m.gguf".toPath(), request).toList() }
                    .onFailure { caught = it }
            }
            // Captured before the cancel, and watched by handle afterwards: a process leaves
            // `descendants()` both when it is killed and when it is orphaned onto init, so asking
            // the set again would call a leak a success. Polled rather than slept on: how long a
            // loaded runner takes to fork and exec is not something to guess at.
            val started = withTimeout(10.seconds) {
                var live = ours(strangers)
                while (live.isEmpty()) {
                    delay(POLL_MILLIS)
                    live = ours(strangers)
                }
                live
            }
            withTimeout(10.seconds) { job.cancelAndJoin() }

            // Abandoning a request is not the engine dying: a caller that branches on
            // CancellationException (IsolatedLlmRuntime does) must not be handed a failure instead.
            // Pins behaviour that already held — the coroutine machinery, not this file's own
            // handling of a dead child, is what makes it hold.
            assertIs<CancellationException>(caught, "cancelling the start produced $caught")
            // And the child goes with it. Without this the test passes on a launcher that leaks the
            // inference host on every abandoned answer — the exception type above is the coroutine
            // machinery's doing, the teardown is this file's.
            withTimeout(10.seconds) {
                while (started.any { it.isAlive }) delay(POLL_MILLIS)
            }
        } finally {
            Files.deleteIfExists(launcher)
        }
    }

    /** [sleepers] minus the ones that were already running, i.e. the child this test started. */
    private fun ours(strangers: Set<Long>): List<ProcessHandle> = sleepers().filterNot { it.pid() in strangers }

    /**
     * Live descendants of this JVM that are the `sleep` a fake launcher exec'd into. Matched by
     * command rather than by the script's path: `exec` replaces the process image, which is the
     * point of it — the launcher then holds the sleeper itself and can kill it. An empty result is
     * therefore evidence of nothing, and the caller checks that before it means anything.
     */
    private fun sleepers(): List<ProcessHandle> =
        ProcessHandle.current().descendants()
            .filter { it.info().command().orElse("").endsWith("/sleep") }
            .toList()

    @Test
    fun `a missing runtime fails as an engine error instead of hanging`() = runBlocking {
        val runtime = IsolatedLlmRuntime(
            ProcessLlmHostLauncher(contextLength = 512, selfCommand = "/nonexistent/bin/java"),
        )

        val error = assertFailsWith<AiException> { runtime.generate("/m.gguf".toPath(), request).toList() }

        assertEquals(AiException.Kind.ENGINE_CRASHED, error.kind)
    }

    private companion object {
        const val POLL_MILLIS = 50L
    }
}
