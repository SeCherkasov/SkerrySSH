package app.skerry.shared.local

/**
 * Android local shell. A real controlling PTY needs a native helper (`forkpty`+`setsid`+
 * `TIOCSCTTY`+`execvp` done entirely in native code — `fork()` in a JVM is unsafe to follow with a
 * Kotlin `exec`), which is delivered separately. Until then, starting a local terminal on Android
 * reports unavailable; the surrounding transport turns this into a clear connect error.
 */
actual object LocalShell {
    actual fun start(config: LocalShellConfig): LocalShellHandle =
        throw LocalShellUnavailableException("Local terminal is not yet supported on Android")
}
