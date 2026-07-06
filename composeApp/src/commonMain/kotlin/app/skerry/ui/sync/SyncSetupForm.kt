package app.skerry.ui.sync

/**
 * Testable validation for the self-hosted sync setup form: connecting needs a server URL, an account
 * id, and a master password. The password isn't stored in the model (it lives as a CharArray in the
 * composable and is wiped after submit) — only its length. Fields are normalized (trim) the same way
 * [SyncCoordinator] later receives them.
 */
data class SyncSetupForm(
    val serverUrl: String = "",
    val accountId: String = "",
) {
    /** Server URL trimmed (as it goes to [SyncCoordinator]). */
    val normalizedServerUrl: String get() = serverUrl.trim()

    /** Account id trimmed. */
    val normalizedAccountId: String get() = accountId.trim()

    /**
     * Whether the form is ready to submit: a non-empty http(s) server URL, non-empty accountId, and a
     * non-empty password. The URL scheme is checked explicitly — to avoid sending SRP to a clearly
     * wrong endpoint (ssh://, bare host) and to give a meaningful error before the network call.
     */
    fun canSubmit(passwordLength: Int): Boolean =
        passwordLength > 0 && normalizedAccountId.isNotEmpty() && isHttpUrl(normalizedServerUrl)

    /**
     * The server URL uses unencrypted `http://`. Submission isn't blocked — this is a valid mode for
     * local testing/LAN without a TLS reverse proxy. But over `http` SRP mutual authentication and
     * session tokens are defenseless against MITM, so the UI must show an explicit warning when this is `true`.
     */
    val isInsecureUrl: Boolean get() = normalizedServerUrl.startsWith("http://")

    private fun isHttpUrl(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) && url.length > "https://".length
}
