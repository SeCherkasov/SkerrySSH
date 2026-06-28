package app.skerry.ui.secure

// Desktop: снимки экрана не блокируем (скриншоты терминала/SFTP должны работать), флаг — no-op.
actual fun applyPlatformSecureFlag(secure: Boolean) {}
