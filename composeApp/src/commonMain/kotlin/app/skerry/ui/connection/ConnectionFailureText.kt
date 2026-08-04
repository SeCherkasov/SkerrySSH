package app.skerry.ui.connection

/**
 * One banner or notice line's worth of transport diagnostics. Passed to [sanitizeServerText] with
 * newlines disallowed, so a multi-line reason folds into one line instead of being wrapped or
 * truncated mid-word.
 */
private const val MAX_CONNECTION_DETAIL_CHARS = 160

/**
 * Display-ready detail for a failed connect or reconnect, or `null` when there is nothing to say
 * (the caller then shows its plain "could not connect" / "connection lost" text).
 *
 * The message is whatever the far end sent — sshj hands the server's SSH_MSG_DISCONNECT reason
 * through verbatim — so it is sanitised here, at the single boundary where that text enters the
 * app, rather than at each of the four places that might render it. Sanitising is also what decides
 * whether there is a message at all: a reason built only from bidi overrides or control characters
 * is not blank by [String.isBlank] but cleans down to nothing, and would otherwise leave a notice
 * reading "Connection lost: " with an empty tail.
 *
 * Falling back to the exception type keeps a reason on the screen for the many transport failures
 * that carry no message. It can still be `null` for an anonymous exception class, which simply
 * restores the plain text — there is genuinely nothing to name in that case.
 */
internal fun serverFailureDetail(e: Throwable): String? =
    cleanedMessage(e)
        // sshj usually wraps the server's reason: the transport exception carries no message of its
        // own and the sentence that says what happened sits on the cause. Naming the wrapper type
        // would throw that sentence away.
        ?: e.cause?.let(::cleanedMessage)
        ?: e::class.simpleName

private fun cleanedMessage(e: Throwable): String? =
    e.message
        ?.let { sanitizeServerText(it, MAX_CONNECTION_DETAIL_CHARS, allowNewlines = false) }
        ?.takeIf { it.isNotBlank() }
