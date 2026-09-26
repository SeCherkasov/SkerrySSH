package app.skerry.shared.jumpshell

import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * A jump host's shell that types [command] once the shell first settles at a prompt.
 *
 * Settled means [PromptWatch] sees a prompt and nothing more arrives for [quietMillis]: a login
 * banner, a browser-login message or a script still printing does not count, and whatever the jump
 * host asks before its prompt the person answers by hand, in this same terminal. The command is
 * typed at most once — a prompt the destination shows later is the destination's.
 *
 * The person driving comes first. A keystroke at a prompt means they have taken over (typed their
 * own command, or are reading the jump host for a reason), so nothing is typed after it. A
 * keystroke while no prompt is up — the Enter a login script waits for — does not count. Neither do
 * the terminal's own answers to the shell's queries, which begin with ESC.
 */
internal class PromptLaunchChannel(
    private val inner: ShellChannel,
    command: String,
    private val quietMillis: Long = DEFAULT_QUIET_MILLIS,
) : ShellChannel by inner {

    private val line = "$command\r".encodeToByteArray()
    private val watch = PromptWatch()

    // Guards the watch and orders the typed command against the person's writes so the two can
    // never interleave on the wire.
    private val lock = Mutex()

    // Cleared under the lock, and only once the command's write is done, so a caller that reads it
    // unlocked and sees `false` has nothing left to be ordered against: the rest of the session is a
    // plain pass-through, with no lock and no watch.
    @Volatile
    private var armed = true

    override val output: Flow<ByteArray> = channelFlow {
        var settle: Job? = null
        inner.output.collect { chunk ->
            send(chunk)
            if (!armed) return@collect
            val candidate = lock.withLock {
                watch.feed(chunk)
                armed && watch.atPrompt()
            }
            settle?.cancel()
            settle = if (candidate) launch { delay(quietMillis); launchCommand() } else null
        }
        // The shell is gone; a command still waiting for its quiet period has nowhere to go.
        settle?.cancel()
    }

    override suspend fun write(data: ByteArray) {
        if (!armed) return inner.write(data)
        lock.withLock {
            val takesOver = armed && watch.atPrompt() && !data.isTerminalReply()
            try {
                inner.write(data)
            } finally {
                if (takesOver) armed = false
            }
        }
    }

    private suspend fun launchCommand() = lock.withLock {
        if (!armed || !watch.atPrompt()) return@withLock
        try {
            inner.write(line)
        } catch (_: SshException) {
            // The channel broke under the write. Its output ends, and that is what reports the loss
            // to the session; the command has no one else to tell.
        } finally {
            armed = false
        }
    }

    private fun ByteArray.isTerminalReply(): Boolean = isNotEmpty() && this[0] == ESC

    private companion object {
        const val ESC: Byte = 0x1b
        const val DEFAULT_QUIET_MILLIS = 400L
    }
}
