package app.skerry.ui.vnc

private val wheelTracing = System.getProperty("skerry.wheelTrace") == "1"

internal actual fun wheelTrace(line: () -> String) {
    // Contained by construction: a diagnostics sink must never be able to kill the pointer-input
    // loop it observes, whatever it is swapped for later.
    if (wheelTracing) runCatching { System.err.println("wheel: ${line()}") }
}
