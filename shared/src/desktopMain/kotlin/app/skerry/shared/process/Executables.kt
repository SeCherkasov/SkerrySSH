package app.skerry.shared.process

import java.io.File

/**
 * Find executable [name] on PATH and return its absolute path, or `null` when it is not there.
 *
 * No `sh -c`: that would be a shell to inject into and a second process to pay for. Callers resolve
 * once and keep the absolute path, so a PATH mutated later in the process cannot retarget a binary
 * that has already been chosen.
 *
 * On Windows the name is tried with each extension in PATHEXT, because `ffmpeg` on PATH is
 * `ffmpeg.exe` on disk.
 */
fun resolveExecutableOnPath(name: String): String? {
    val directories = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotEmpty() }
    val names = if (isWindows && !name.contains('.')) listOf(name) + windowsExtensions.map { name + it } else listOf(name)
    return directories.asSequence()
        .flatMap { directory -> names.asSequence().map { File(directory, it) } }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

private val isWindows: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

private val windowsExtensions: List<String> =
    (System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD").split(';').filter { it.startsWith('.') }
