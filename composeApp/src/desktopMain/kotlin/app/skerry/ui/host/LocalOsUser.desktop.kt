package app.skerry.ui.host

actual fun localOsUserName(): String? = System.getProperty("user.name")?.takeIf { it.isNotBlank() }
