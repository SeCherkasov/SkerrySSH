package app.skerry.ui.vault

/** Desktop has no keyguard equivalent, so the vault locks when the window goes to the background. */
actual fun deviceMandatesAutoLock(): Boolean = true
