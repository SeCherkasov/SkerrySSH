package app.skerry.ui.vault

import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.tunnel.TunnelManager

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
 * unlock the run must be re-initiated with a fresh user intent, not resumed.
 *
 * Shared by desktop and Android so the two can't drift apart on which of these gets forgotten.
 */
fun tearDownForLock(
    tunnels: TunnelManager?,
    sessions: SessionsController?,
    sync: SyncCoordinator?,
    snippets: SnippetManager?,
) {
    tunnels?.closeAll()
    sessions?.sessions?.forEach { session ->
        session.controller.clearReconnectCredentials()
        session.splitSession?.controller?.clearReconnectCredentials()
    }
    sync?.pauseForLock()
    snippets?.dismissRun()
}
