package app.skerry.shared.audio

/**
 * Diagnostics for the audio path, from what the connection asked for to the blocks that reach the
 * device: `SKERRY_RDP_AUDIO_TRACE=1` writes it to stderr.
 *
 * A session with no sound has to be diagnosed on the machine where it happens, against the server
 * that does it, and the interesting part is what the *server* does — whether it opens an audio
 * channel at all. Note that `./gradlew run` starts the app in a process that inherits the Gradle
 * daemon's environment rather than the shell's, so the run task forwards the variable as the system
 * property this also reads (see composeApp/build.gradle.kts); a packaged build reads the variable
 * itself.
 *
 * It lives with the players rather than with the RDP session that reads the variable's name: the
 * platform sinks trace through it too, and `rdp` already depends on `audio` — the other direction
 * would close a cycle.
 */
private val audioTracing = System.getenv("SKERRY_RDP_AUDIO_TRACE") == "1" ||
    System.getProperty("skerry.rdp.audioTrace") == "1"

internal val audioTrace: (String) -> Unit = { line -> if (audioTracing) System.err.println("rdp audio: $line") }
