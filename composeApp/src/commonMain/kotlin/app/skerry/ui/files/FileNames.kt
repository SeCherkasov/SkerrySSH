package app.skerry.ui.files

/**
 * True if a listing entry name is unsafe: empty, contains `/` or `\`, or is `.`/`..`. Guards
 * against path traversal from an untrusted server; write/delete paths must be built via
 * [childPath] from a name that passed this check, never from server-controlled `item.path`.
 */
internal fun isUnsafeListingName(name: String): Boolean =
    name.isEmpty() || "/" in name || "\\" in name || name == "." || name == ".."

/** Path of child [name] under directory [dir]. */
internal fun childPath(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"
