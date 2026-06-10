package app.skerry.shared

actual val platformName: String =
    "Desktop (${System.getProperty("os.name")} ${System.getProperty("os.arch")})"
