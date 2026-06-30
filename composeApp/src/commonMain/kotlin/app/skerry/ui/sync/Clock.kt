package app.skerry.ui.sync

/**
 * Текущее wall-clock время (epoch ms) — для обратного отсчёта до протухания кода паринга
 * ([PairingOffer.expiresAt]). Платформенное: оба таргета JVM (`System.currentTimeMillis`).
 */
expect fun nowMillis(): Long
