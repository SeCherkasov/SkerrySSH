package app.skerry.shared.snippet

/**
 * Captures the machine-value providers for one snippet run: local wall-clock moment, UUID
 * generation and a secure random string source. An `expect` for the same reason as
 * [app.skerry.shared.terminal.epochMillis] — not worth a date-time dependency; tests build a
 * [SnippetRunEnvironment] by hand instead of calling this.
 */
expect fun captureSnippetRunEnvironment(): SnippetRunEnvironment
