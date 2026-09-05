package app.skerry.ui.vault

import app.skerry.ui.connection.KeyboardInteractivePromptController
import app.skerry.ui.runbook.RunbookRunner
import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.trust.HostTrustPromptController
import app.skerry.ui.tunnel.TunnelManager
import kotlinx.coroutines.CancellationException

/**
 * Drops everything that still holds a decrypted secret when the vault locks. Passed to
 * [VaultGate] as `onBeforeLock`, so it covers the manual lock and both automatic ones (background
 * and idle timer) — the automatic paths call the gate controller directly and never see the
 * caller's lock action.
 *
 * Tunnels are closed outright: each holds its own SSH connection opened with the secret, and a
 * saved tunnel is meaningless behind a lock screen. This also cancels an in-flight service scan
 * ([TunnelManager.closeAll]). Terminal SESSIONS deliberately survive — their sockets stay open and
 * the tabs are still there after unlocking — but their saved credentials are cleared, because an
 * auto-reconnect after a lock would re-authenticate with a stale secret against a locked vault.
 *
 * Sync is paused, not disconnected ([SyncCoordinator.pauseForLock] — the link and the session stay, only
 * the live subscriptions stop): behind a lock every sync cycle would throw inside the vault while the WS
 * kept retrying. [SyncCoordinator.resumeAfterUnlock] is the other half, wired to `onVaultUnlocked`.
 *
 * The pending snippet-variable run is dismissed too: its dialog previews vault secrets, and after
 * unlock the run must be re-initiated with a fresh user intent, not resumed. A runbook run is ended
 * outright for the same reason and one more: it holds the resolved values of every remaining step
 * (a `${{vault}}` secret among them) and would otherwise keep typing them into a shell while the
 * vault is locked.
 *
 * A host-trust question waiting for an answer is refused for the same reason a keyboard-interactive
 * prompt is cancelled: it lives in the unlocked chrome, and a handshake held open behind the lock
 * screen waits for an answer nobody can give.
 *
 * A keyboard-interactive prompt waiting for an answer is cancelled, which fails that connection
 * attempt. The dialog is rendered inside the unlocked chrome, so a lock takes it off screen while the
 * connection would go on waiting behind the lock screen for a code nobody can see — and then fail on
 * its own timeout minutes later, looking like the credentials were wrong.
 *
 * The desktop sync-setup modal is closed too, for a reason the others don't have: its open flag lives
 * above the vault gate, so the modal survives the lock and comes back on unlock — but the question inside
 * it does not, because [SyncCoordinator.pauseForLock] declines it. Left open it would return as a plain
 * connect form standing where the user's question was, with nothing said about the question being answered
 * for them. Android passes nothing here: its confirmation is a screen, and it releases itself on dispose.
 *
 * Shared by desktop and Android so the two can't drift apart on which of these gets forgotten.
 */
fun tearDownForLock(
    tunnels: TunnelManager?,
    sessions: SessionsController?,
    sync: SyncCoordinator?,
    snippets: SnippetManager?,
    runbooks: RunbookRunner? = null,
    keyboardInteractive: KeyboardInteractivePromptController? = null,
    hostTrust: HostTrustPromptController? = null,
    closeSyncSetup: (() -> Unit)? = null,
) {
    val failures = TeardownFailures()
    failures.run { tunnels?.closeAll() }
    // Guarded per pane, not per step: each pane holds its own decrypted credential, so one that
    // refuses to let go must not leave the panes after it holding theirs behind a locked vault.
    sessions?.tabs?.forEach { tab ->
        tab.panes.forEach { pane ->
            failures.run { pane.controller.clearReconnectCredentials() }
            // The same credential again: a pane's terminal holds its own copy to offer back at a
            // sudo prompt (issue #360), and it must not survive the lock the reconnect copy is
            // dropped for. Also ends any offer standing when the screen locked, so unlocking does
            // not resume one the user never re-consented to.
            failures.run { pane.liveTerminal?.applySudoOfferEnabled(false) }
        }
    }
    failures.run { sync?.pauseForLock() }
    failures.run { snippets?.dismissRun() }
    failures.run { runbooks?.close() }
    failures.run { keyboardInteractive?.cancelPending() }
    failures.run { hostTrust?.cancelPending() }
    failures.run { closeSyncSetup?.invoke() }
    failures.rethrowFirst()
}

/**
 * Keeps a failed cleanup from cancelling the ones after it. Each step here drops a different secret,
 * and they are independent: a tunnel that refuses to close must not leave a runbook's resolved
 * `${{vault:…}}` values in memory behind a locked vault. The first failure is still raised once
 * everything has had its turn, so the manual lock reports it as before.
 */
private class TeardownFailures {
    private var first: Throwable? = null

    fun run(step: () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (first == null) first = e
        }
    }

    fun rethrowFirst() {
        first?.let { throw it }
    }
}
