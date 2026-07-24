package app.skerry.ui.host

// Android has no SSH-relevant local account name; imported hosts keep an empty username instead.
actual fun localOsUserName(): String? = null
