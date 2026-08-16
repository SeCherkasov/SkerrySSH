package app.skerry.ui.vnc

// Same JVM switch as desktop: settable via `adb shell setprop` is NOT wired — the trace targets
// the desktop reproduction from issue #265, and Android just needs a compiling actual.
private val wheelTracing = System.getProperty("skerry.wheelTrace") == "1"

internal actual fun wheelTrace(line: () -> String) {
    // Contained by construction — see the desktop actual.
    if (wheelTracing) runCatching { System.err.println("wheel: ${line()}") }
}
