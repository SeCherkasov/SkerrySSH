package app.skerry.ui.host

/**
 * Local OS account name, used as the default SSH username when an imported `ssh_config` entry omits
 * `User` (OpenSSH itself falls back to the local user in that case). `null` when unavailable —
 * Android has no meaningful equivalent, so imported hosts there simply keep an empty username.
 */
expect fun localOsUserName(): String?
