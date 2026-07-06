package app.skerry.ui.sync

/**
 * Current wall-clock time (epoch ms), used for the pairing code expiry countdown
 * ([PairingOffer.expiresAt]). Platform-specific; both targets are JVM (`System.currentTimeMillis`).
 */
expect fun nowMillis(): Long
