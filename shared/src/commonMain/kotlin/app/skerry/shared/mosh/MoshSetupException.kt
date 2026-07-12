package app.skerry.shared.mosh

import app.skerry.shared.ssh.SshException

/**
 * Typed Mosh session-setup failure. The [reason] lets the UI explain the situation in the
 * user's language — crucial for Mosh, where the most common failures are server-side
 * (Skerry ships only the client; `mosh-server` must be installed on the remote host) and a
 * raw exception string would leave the user guessing. [message] is an English fallback.
 */
class MoshSetupException(
    val reason: Reason,
    val detail: String? = null,
    message: String,
) : SshException(message) {

    enum class Reason {
        /** `mosh-server` is not installed (or not on PATH for non-interactive shells). */
        SERVER_NOT_INSTALLED,

        /** The host offers no UTF-8 locale that `mosh-server` can run under. */
        LOCALE_UNSUPPORTED,

        /** `mosh-server` started but nothing came back over UDP; [detail] is the port. */
        UDP_UNREACHABLE,

        /** `mosh-server` failed to start; [detail] carries its output. */
        BOOTSTRAP_FAILED,
    }
}
