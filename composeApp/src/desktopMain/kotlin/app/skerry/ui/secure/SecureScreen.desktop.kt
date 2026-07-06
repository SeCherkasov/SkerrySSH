package app.skerry.ui.secure

// Desktop does not block screenshots (terminal/SFTP screenshots should work); no-op flag.
actual fun applyPlatformSecureFlag(secure: Boolean) {}
