package app.skerry.shared.rdp.egfx

/**
 * Diagnostics for the H.264 path: `SKERRY_RDP_H264_TRACE=1` writes it to stderr.
 *
 * Whether this codec is used at all is decided between the local machine and the server, and both
 * halves of that decision are otherwise invisible — a desktop with no `ffmpeg` looks exactly like a
 * server that prefers the progressive codec, and both look like a working session. These lines are
 * what tell them apart on the machine where it happens.
 *
 * `./gradlew run` starts the app in a process that inherits the Gradle daemon's environment rather
 * than the shell's, so the run task forwards the variable as a system property as well (see
 * composeApp/build.gradle.kts); a packaged build reads the variable itself.
 */
private val h264Tracing = System.getenv("SKERRY_RDP_H264_TRACE") == "1" ||
    System.getProperty("skerry.rdp.h264Trace") == "1"

val h264Trace: (String) -> Unit = { line -> if (h264Tracing) System.err.println("rdp h264: $line") }
