package app.skerry.ui.vault

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SecurityEvent
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.ui.app.LocalUserActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.skerry.ui.desktop.WithTestLifecycle
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Idle auto-lock counted from user activity. The gate is what decides when the vault locks, and
 * locking here is not a screensaver: `key(state)` drops the content subtree, so a live session dies
 * with it. Which inputs count as "the user is here" is therefore a correctness property, and it is
 * asserted through the real composable — the modifier that feeds the timer is exactly the piece
 * that was wrong (issue #291: presses only, so typing locked the vault mid-session).
 */
@OptIn(ExperimentalTestApi::class)
class VaultGateIdleLockTest {

    /** The reported bug: five minutes of typing without a single click locked the vault. */
    @Test
    fun `typing holds the idle timer off`() = runComposeUiTest {
        unlockedGate()

        mainClock.autoAdvance = false
        // Four half-windows of typing: more than twice the threshold in total, never a quiet window.
        repeat(4) {
            mainClock.advanceTimeBy(IDLE_MS / 2)
            onRoot().performKeyInput { pressKey(Key.A) }
        }
        mainClock.advanceTimeBy(IDLE_MS / 2)

        onNodeWithText(UNLOCKED).assertIsDisplayed()
    }

    /**
     * The same bug from the other input device: reading a long file with the wheel, or dragging a
     * pane divider, is a person at the desk even though nothing was ever pressed.
     */
    @Test
    fun `a moving pointer holds the idle timer off`() = runComposeUiTest {
        unlockedGate()

        mainClock.autoAdvance = false
        var x = 1f
        repeat(4) {
            mainClock.advanceTimeBy(IDLE_MS / 2)
            x += 3f
            onRoot().performMouseInput { moveTo(Offset(x, x)) }
        }
        mainClock.advanceTimeBy(IDLE_MS / 2)

        onNodeWithText(UNLOCKED).assertIsDisplayed()
    }

    /**
     * The inverse: a session repainting under a parked cursor is the remote end being busy, not the
     * user being present. (What Compose itself re-sends over a relayout — a Move at the unchanged
     * position — cannot be reproduced here: the test input dispatcher drops a move that goes
     * nowhere. That half is `PointerActivityTest`.)
     */
    @Test
    fun `a repainting session does not hold the idle timer off`() = runComposeUiTest {
        unlockedGate(content = { RepaintingContent() })
        onRoot().performMouseInput { moveTo(Offset(5f, 5f)) }

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)

        onNodeWithText(LOCKED).assertIsDisplayed()
    }

    /**
     * A soft keyboard sends neither key events nor pointer events into the composition, so on
     * Android typing is invisible to the modifier; the text fields report it themselves.
     */
    @Test
    fun `text arriving without key events holds the idle timer off`() = runComposeUiTest {
        unlockedGate(content = { TypingContent() })

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)

        onNodeWithText(UNLOCKED).assertIsDisplayed()
    }

    /** Work the user started runs unattended; locking on top of it would close the session it needs. */
    @Test
    fun `work in flight defers the lock`() = runComposeUiTest {
        var busy = true
        unlockedGate(workInFlight = { busy })

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)
        onNodeWithText(UNLOCKED).assertIsDisplayed()

        // Deferred, not cancelled: the window starts over when the work ends, and then it locks.
        busy = false
        mainClock.advanceTimeBy(IDLE_MS * 2)

        onNodeWithText(LOCKED).assertIsDisplayed()
    }

    /** A wheel notch is a person reading, with nothing pressed and the cursor never moving. */
    @Test
    fun `the wheel holds the idle timer off`() = runComposeUiTest {
        unlockedGate()
        onRoot().performMouseInput { moveTo(Offset(5f, 5f)) }

        mainClock.autoAdvance = false
        repeat(4) {
            mainClock.advanceTimeBy(IDLE_MS / 2)
            onRoot().performMouseInput { scroll(1f) }
        }
        mainClock.advanceTimeBy(IDLE_MS / 2)

        onNodeWithText(UNLOCKED).assertIsDisplayed()
    }

    /**
     * Whether work is running is a claim the timer cannot verify, so a predicate that throws fails
     * closed. The two sources wired today are plain state reads; this pins the direction for the next.
     */
    @Test
    fun `a workInFlight that throws still locks the vault`() = runComposeUiTest {
        unlockedGate(workInFlight = { error("the predicate broke") })

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)

        onNodeWithText(LOCKED).assertIsDisplayed()
    }

    /**
     * The teardown that drops decrypted secrets runs before the lock. If it throws, the lock still
     * has to happen — a vault left open because closing a tunnel failed is the worst of both — and
     * the failure must not travel out of a timer nobody asked for and take the app down.
     */
    @Test
    fun `a teardown that throws still locks the vault`() = runComposeUiTest {
        unlockedGate(onBeforeLock = { error("a tunnel refused to close") })

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)

        onNodeWithText(LOCKED).assertIsDisplayed()
    }

    /**
     * Locked but not cleaned up is a state the user has to be able to find out about: the lock screen
     * looks exactly like a clean lock, while a tunnel that refused to close still holds an SSH
     * connection opened with the vault secret. The failure is contained, so the log is the only place
     * left to say so.
     */
    @Test
    fun `an incomplete automatic lock is recorded`() = runComposeUiTest {
        val log = IdleLockSecurityLog()
        unlockedGate(onBeforeLock = { error("a tunnel refused to close") }, securityLog = log)

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)
        onNodeWithText(LOCKED).assertIsDisplayed()

        assertEquals(
            listOf(SecurityEventType.LockIncomplete),
            log.events.map { it.type }.filter { it == SecurityEventType.LockIncomplete },
            "a lock that did not finish left no trace",
        )
        assertEquals(
            listOf(null),
            log.events.filter { it.type == SecurityEventType.LockIncomplete }.map { it.detail },
            "the exception text carries host names and must not reach a plaintext log",
        )
    }

    /**
     * The report is a file write, so it can fail for the same reason the teardown did — a full disk
     * takes both. A log that cannot be written must not undo the containment it was added to report
     * on: the exception would leave the idle LaunchedEffect and reach the recomposer.
     */
    @Test
    fun `a log that cannot be written does not undo the containment`() = runComposeUiTest {
        unlockedGate(
            onBeforeLock = { error("a tunnel refused to close") },
            securityLog = FullDiskSecurityLog,
        )

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)

        onNodeWithText(LOCKED).assertIsDisplayed()
    }

    /**
     * The background lock takes the same contained path. It is the one that used to throw straight
     * into lifecycle dispatch: `ON_STOP` arrives on the platform's own callback, so a teardown
     * failure there took the app down while it was being minimised. The idle timer is off here on
     * purpose — nothing but the lifecycle event may do the locking.
     */
    @Test
    fun `a teardown that throws on the background lock is contained and recorded`() = runComposeUiTest {
        val log = IdleLockSecurityLog()
        val owner = object : LifecycleOwner {
            override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        }.also { it.lifecycle.currentState = Lifecycle.State.STARTED }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                VaultGate(
                    vault = FakeFreshVault(),
                    securityLog = log,
                    autoLockIdleMs = null,
                    onBeforeLock = { error("a tunnel refused to close") },
                    createForm = { _, onCreate, _ ->
                        LaunchedEffect(Unit) { onCreate(PASSWORD.toCharArray(), PASSWORD.toCharArray()) }
                    },
                    unlockForm = { _, _, _, _, _ -> Text(LOCKED) },
                ) { FocusedContent() }
            }
        }
        waitUntil { onAllNodesWithText(UNLOCKED).fetchSemanticsNodes().isNotEmpty() }

        owner.lifecycle.currentState = Lifecycle.State.CREATED
        waitForIdle()

        onNodeWithText(LOCKED).assertIsDisplayed()
        // VaultCreated is in the log too — the gate walked through the creation form to get here.
        assertEquals(
            listOf(SecurityEventType.LockIncomplete),
            log.events.map { it.type }.filter { it == SecurityEventType.LockIncomplete },
            "the background lock did not finish and left no trace",
        )
    }

    /** The other half of the property: silence still locks, or the timeout would be decoration. */
    @Test
    fun `silence locks the vault`() = runComposeUiTest {
        unlockedGate()

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 2)

        onNodeWithText(LOCKED).assertIsDisplayed()
    }

    /**
     * Stands the gate up on a fresh vault and walks it to [VaultGateState.Unlocked] through the
     * creation form (no biometrics, no sync form — creation leads straight into the app). Leaves the
     * clock on autoAdvance; the caller takes it over.
     */
    private fun ComposeUiTest.unlockedGate(
        workInFlight: () -> Boolean = { false },
        onBeforeLock: () -> Unit = {},
        securityLog: SecurityLog? = null,
        content: @Composable () -> Unit = { FocusedContent() },
    ) {
        setContent {
            WithTestLifecycle {
                VaultGate(
                    vault = FakeFreshVault(),
                    securityLog = securityLog,
                    autoLockIdleMs = IDLE_MS,
                    workInFlight = workInFlight,
                    onBeforeLock = onBeforeLock,
                    createForm = { _, onCreate, _ ->
                        LaunchedEffect(Unit) { onCreate(PASSWORD.toCharArray(), PASSWORD.toCharArray()) }
                    },
                    unlockForm = { _, _, _, _, _ -> Text(LOCKED) },
                ) { content() }
            }
        }
        waitUntil { onAllNodesWithText(UNLOCKED).fetchSemanticsNodes().isNotEmpty() }
    }
}

/**
 * A log on a full disk, as `FileSecurityLog.record` fails through okio. Only the lock event throws:
 * the gate walks through the creation form to reach an unlocked vault, and that path records
 * `VaultCreated` while the user is watching — a different call site with a different answer.
 */
private object FullDiskSecurityLog : SecurityLog {
    override fun record(type: SecurityEventType, detail: String?) {
        if (type == SecurityEventType.LockIncomplete) error("no space left on device")
    }

    override fun recent(limit: Int): List<SecurityEvent> = emptyList()

    override fun lastPasswordChangeAt(): String? = null

    override fun clear() = Unit
}

/** In-memory [SecurityLog]: this suite only ever asks what was written, never when. */
private class IdleLockSecurityLog : SecurityLog {
    val events = mutableListOf<SecurityEvent>()
    override fun record(type: SecurityEventType, detail: String?) {
        events += SecurityEvent(type, "t${events.size}", detail)
    }

    override fun recent(limit: Int): List<SecurityEvent> = events.asReversed().take(limit)

    override fun lastPasswordChangeAt(): String? = null

    override fun clear() = events.clear()
}

/**
 * Stands in for the app behind the gate: something that holds the keyboard, the way a terminal does
 * whenever it is being typed into. Key events reach the gate's modifier on their way down to the
 * focused node, so a composition where nothing has focus would prove nothing about typing.
 */
@Composable
private fun FocusedContent() {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.focusRequester(focus).focusable()) { Text(UNLOCKED) }
}

/**
 * Stands in for a session printing output: it relayouts on a timer and never touches the vault.
 * Nothing here is user input, however many pointer events Compose manufactures over it.
 */
@Composable
private fun RepaintingContent() {
    var side by remember { mutableStateOf(10) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REPAINT_MS)
            side = if (side == 10) 40 else 10
        }
    }
    Box(Modifier.size(side.dp)) { Text(UNLOCKED) }
}

/** Stands in for a text field fed by a soft keyboard: it reports typing itself, without key events. */
@Composable
private fun TypingContent() {
    val activity = LocalUserActivity.current
    LaunchedEffect(activity) {
        while (true) {
            delay(IDLE_MS / 2)
            activity()
        }
    }
    Text(UNLOCKED)
}

private const val IDLE_MS = 60_000L
private const val REPAINT_MS = 100L
private const val PASSWORD = "correct horse battery"
private const val UNLOCKED = "session"
private const val LOCKED = "locked"

/** Vault with no file yet: [create] opens it, which is all the gate needs to reach `Unlocked`. */
private class FakeFreshVault : Vault {
    private var created = false
    private var open = false

    override fun exists(): Boolean = created
    override val isUnlocked: Boolean get() = open
    override fun create(password: CharArray) { created = true; open = true }
    override fun verifyPassword(password: CharArray): Boolean = true
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Success
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
    override fun lock() { open = false }
    override fun reset() = Unit
    override fun records(): List<VaultRecord> = emptyList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
    override fun remove(id: String) = Unit
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
}
