@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.skerry.ui.terminal

import app.skerry.shared.team.RecordingIdentity
import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.TeamRecordingCrypto
import app.skerry.shared.team.TeamRecordingOutbox
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.terminal.ShellTerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.connection.FakeShellChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RecordingLifecycleFixture(private val test: TestScope) {
    val fs = FakeFileSystem()
    val crypto = IonspinVaultCrypto()
    val key = crypto.newDataKey()
    val identity = RecordingIdentity(TeamScopeRef("team"), "recording", "host", "actor", 0)
    var failWrites = false
    var observeWrite: () -> Unit = {}
    val outbox = TeamRecordingOutbox("/recordings".toPath(), fs, {
        observeWrite()
        check(!failWrites) { "recording disk unavailable" }
    }, crypto)
    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(test.testScheduler))
    val channel = FakeShellChannel()
    val session = ShellTerminalSession(channel, scope)
    val capture = outbox.arm(identity, key, 80, 24, "host", 100, { 0 })
    var finished = 0
    var afterFinished: () -> Unit = {}
    fun terminal(mode: RecordingMode = RecordingMode.REQUIRED) = TerminalScreenState(
        session, scope, teamRecordingMode = mode,
        teamCapture = capture.takeIf { mode == RecordingMode.REQUIRED },
        onTeamRecordingFinished = { finished++; afterFinished() },
    )
    suspend fun recoveredOutput(): String {
        outbox.recover("") { _, _ ->
            val transfer = crypto.newTransferKey()
            try { crypto.openTransferredDataKey(transfer, crypto.sealDataKeyForTransfer(key, transfer)) }
            finally { transfer.fill(0) }
        }
        val upload = outbox.pendingUploads().single()
        val codec = TeamRecordingCrypto(crypto)
        val dek = codec.openKey(key, upload.wrappedKey, identity)!!
        return try {
            upload.chunks.joinToString("") { chunk ->
                val bytes = codec.openChunk(dek, identity, chunk.index, outbox.chunk(identity, chunk.index))!!
                try { bytes.decodeToString() } finally { bytes.fill(0) }
            }
        } finally { dek.zeroize() }
    }
    fun close() { scope.cancel(); fs.checkNoOpenFiles(); key.zeroize() }
}

class TeamRecordingLifecycleTest {
    @Test fun requiredOutputIsPersistedBeforeItBecomesVisible() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            val terminal = f.terminal()
            var observed = false
            f.observeWrite = { observed = true; assertFalse(terminal.output.contains("first output")) }
            f.channel.emit("first output".encodeToByteArray())
            runCurrent()
            assertTrue(observed)
            assertEquals("first output", terminal.output)
            f.observeWrite = {}
            f.scope.cancel()
            runCurrent()
            assertTrue(f.recoveredOutput().contains("first output"))
            assertEquals(1, f.finished)
            assertFalse(terminal.recording)
            assertTrue(terminal.recordingToTeam)
        } finally { f.close() }
    }

    @Test fun requiredPolicyCannotBeStoppedOrReplacedByPersonalRecording() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            val terminal = f.terminal()
            terminal.startRecording("replacement")
            assertTrue(terminal.recording)
            assertNull(terminal.stopRecording())
            f.channel.emit("required evidence".encodeToByteArray())
            runCurrent()
            f.scope.cancel()
            runCurrent()
            assertTrue(f.recoveredOutput().contains("required evidence"))
            assertEquals(1, f.finished)
        } finally { f.close() }
    }

    @Test fun shellEofFinalizesRequiredRecordingWithoutAnExplicitStop() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            val terminal = f.terminal()
            f.channel.emit("last shell output".encodeToByteArray())
            f.channel.exit()
            runCurrent()
            assertEquals(1, f.finished)
            assertFalse(terminal.recording)
            assertFalse(terminal.teamRecordingFailed)
            assertEquals(1, f.outbox.pendingUploads().size)
            assertTrue(f.recoveredOutput().contains("last shell output"))
        } finally { f.close() }
    }

    @Test fun closingSessionFinalizesRequiredRecordingWithoutAnExplicitStop() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            f.terminal()
            f.channel.emit("before disconnect".encodeToByteArray())
            f.session.close()
            runCurrent()
            assertEquals(1, f.finished)
            assertTrue(f.recoveredOutput().contains("before disconnect"))
        } finally { f.close() }
    }

    @Test fun cancellationFinalizesRequiredOutputExactlyOnce() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            f.terminal()
            f.channel.emit("before cancellation".encodeToByteArray())
            f.scope.cancel()
            runCurrent()
            assertEquals(1, f.outbox.pendingUploads().size)
            assertEquals(1, f.finished)
            assertTrue(f.recoveredOutput().contains("before cancellation"))
            f.scope.cancel()
            assertEquals(1, f.finished)
        } finally { f.close() }
    }

    @Test fun persistenceFailureClosesShellWithoutRenderingUnrecordedOutputAndRetainsEvidence() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            val terminal = f.terminal()
            f.channel.emit("committed evidence".encodeToByteArray())
            runCurrent()
            f.failWrites = true
            f.channel.emit("must never render".encodeToByteArray())
            runCurrent()
            assertTrue(terminal.teamRecordingFailed)
            assertFalse(terminal.recording)
            assertTrue(f.session.state.value is TerminalState.Closed)
            assertFalse(terminal.output.contains("must never render"))
            assertEquals(1, f.finished)
            f.scope.cancel()
            runCurrent()
            f.failWrites = false
            assertTrue(f.recoveredOutput().contains("committed evidence"))
        } finally { f.close() }
    }

    @Test fun optionalAndOffPolicyKeepManualRecordingLocalWithoutAutoUpload() = runTest {
        initializeVaultCrypto()
        for (mode in listOf(RecordingMode.OFF, RecordingMode.OPTIONAL)) {
            val f = RecordingLifecycleFixture(this)
            try {
                f.capture.abort()
                val terminal = f.terminal(mode)
                f.channel.emit("before local start".encodeToByteArray())
                runCurrent()
                assertFalse(terminal.recording)
                assertFalse(terminal.recordingToTeam)
                terminal.startRecording("personal")
                f.channel.emit("personal output".encodeToByteArray())
                runCurrent()
                val cast = terminal.stopRecording()!!
                assertFalse(terminal.recordingToTeam)
                assertTrue(cast.contains("personal output"))
                assertFalse(cast.contains("before local start"))
                f.scope.cancel()
                runCurrent()
                assertEquals(0, f.finished)
                assertTrue(f.outbox.pendingUploads().isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun requiredFinalizationFailureStopsIndicatorAndNotifiesRecoveryOnce() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            val terminal = f.terminal()
            f.channel.emit("durable before finalization".encodeToByteArray())
            runCurrent()
            f.failWrites = true
            f.channel.exit()
            runCurrent()
            assertFalse(terminal.recording)
            assertTrue(terminal.teamRecordingFailed)
            assertEquals(1, f.finished)
            f.scope.cancel()
            runCurrent()
            assertEquals(1, f.finished)
            f.failWrites = false
            assertTrue(f.recoveredOutput().contains("durable before finalization"))
        } finally { f.close() }
    }

    @Test fun optionalPersistenceFailureKeepsTeamDestinationAndNotifiesOnceEvenIfCallbackThrows() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            val terminal = f.terminal(RecordingMode.OPTIONAL)
            terminal.startRecording("optional", f.capture) { f.finished++; error("retry callback refused") }
            f.channel.emit("committed optional evidence".encodeToByteArray())
            runCurrent()
            assertTrue(terminal.recordingToTeam)
            f.failWrites = true
            f.channel.emit("personal continuation".encodeToByteArray())
            runCurrent()
            assertTrue(terminal.teamRecordingFailed)
            assertTrue(terminal.recordingToTeam)
            assertEquals(1, f.finished)
            assertTrue(terminal.stopRecording()!!.contains("personal continuation"))
            assertFalse(terminal.recording)
            assertTrue(terminal.recordingToTeam)
            f.scope.cancel()
            runCurrent()
            assertEquals(1, f.finished)
            f.failWrites = false
            assertTrue(f.recoveredOutput().contains("committed optional evidence"))
        } finally { f.close() }
    }

    @Test fun requiredCompletionCallbackFailureIsVisibleAndCannotRepeatNotification() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            f.afterFinished = { error("retry callback refused") }
            val terminal = f.terminal()
            f.channel.emit("completed evidence".encodeToByteArray())
            f.channel.exit()
            runCurrent()
            assertFalse(terminal.recording)
            assertTrue(terminal.teamRecordingFailed)
            assertEquals(1, f.finished)
            assertEquals(1, f.outbox.pendingUploads().size)
            f.scope.cancel()
            runCurrent()
            assertEquals(1, f.finished)
        } finally { f.close() }
    }

    @Test fun optionalFinalizationFailurePreservesEvidenceAndNotifiesRecoveryOnce() = runTest {
        initializeVaultCrypto()
        val f = RecordingLifecycleFixture(this)
        try {
            val terminal = f.terminal(RecordingMode.OPTIONAL)
            terminal.startRecording("optional", f.capture) { f.finished++ }
            f.channel.emit("optional before finalization".encodeToByteArray())
            runCurrent()
            f.failWrites = true
            assertTrue(terminal.stopRecording()!!.contains("optional before finalization"))
            assertFalse(terminal.recording)
            assertTrue(terminal.recordingToTeam)
            assertTrue(terminal.teamRecordingFailed)
            assertEquals(1, f.finished)
            f.scope.cancel()
            runCurrent()
            assertEquals(1, f.finished)
            f.failWrites = false
            assertTrue(f.recoveredOutput().contains("optional before finalization"))
        } finally { f.close() }
    }
}
