package app.skerry.ui.vault

/**
 * Desktop: аналога keyguard нет, поэтому при уходе окна в фон запираем vault как и раньше —
 * консервативно по безопасности.
 */
actual fun deviceMandatesAutoLock(): Boolean = true
