package app.skerry.shared.container

/**
 * Command lines for container profiles: what to run on the host to enter a container ([containerExecArgv])
 * and what to run to enumerate them ([containerListCommandLine]).
 *
 * Commands are built as argv and joined with POSIX quoting ([shellCommandLine]) — the container/pod
 * name, namespace and shell are free-form user input that ends up in a remote shell, so no value is
 * ever interpolated raw.
 */

/**
 * Argv entering [spec]'s container: `docker exec -i -t <name> <shell>` or
 * `kubectl exec -i -t <pod> [-n ns] [-c container] -- <shell>`. `-i -t` because the session already
 * owns a PTY on the SSH channel — the container's shell must get one too, otherwise there's no job
 * control and no line editing.
 *
 * @throws IllegalArgumentException [spec] names no container (callers validate before connecting)
 */
fun containerExecArgv(spec: ContainerSpec): List<String> {
    val s = spec.normalized()
    require(s.isComplete) { "Container profile has no valid container selected" }
    val shell = s.shell.ifEmpty { DEFAULT_CONTAINER_SHELL }
    return when (s.runtime) {
        ContainerRuntime.DOCKER -> listOf("docker", "exec", "-i", "-t", s.target, shell)
        ContainerRuntime.KUBERNETES -> buildList {
            addAll(listOf("kubectl", "exec", "-i", "-t", s.target))
            if (s.namespace.isNotEmpty()) addAll(listOf("-n", s.namespace))
            if (s.podContainer.isNotEmpty()) addAll(listOf("-c", s.podContainer))
            // Everything after `--` belongs to the container, not to kubectl.
            add("--")
            add(shell)
        }
    }
}

/**
 * Command listing what can be entered on the host: running Docker containers (tab-separated, parsed
 * by [parseContainerList]) or Kubernetes pods in [ContainerSpec.namespace] (fixed columns). Only
 * [ContainerSpec.runtime] and [ContainerSpec.namespace] are read — the rest of the spec is about
 * one container, this is the catalog.
 *
 * Stopped containers are deliberately out: `exec` only works on a running one, so listing them
 * would offer targets that can't be connected to.
 */
fun containerListCommandLine(spec: ContainerSpec): String {
    val s = spec.normalized()
    val argv = when (s.runtime) {
        ContainerRuntime.DOCKER ->
            listOf("docker", "ps", "--format", """{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}""")
        ContainerRuntime.KUBERNETES -> buildList {
            addAll(listOf("kubectl", "get", "pods", "--no-headers", "-o", KUBECTL_COLUMNS))
            if (s.namespace.isNotEmpty()) addAll(listOf("-n", s.namespace))
        }
    }
    return shellCommandLine(argv)
}

private const val KUBECTL_COLUMNS =
    "custom-columns=NAME:.metadata.name,STATUS:.status.phase,CONTAINERS:.spec.containers[*].name"

/**
 * Join argv into one POSIX `sh` command line, quoting every argument that isn't plainly safe
 * (single quotes, with `'` written as `'\''`). This is what keeps a container named `web; rm -rf /`
 * a container name instead of a second command.
 */
fun shellCommandLine(argv: List<String>): String = argv.joinToString(" ") { quoteForShell(it) }

private fun quoteForShell(arg: String): String =
    if (arg.isNotEmpty() && arg.all { it in SAFE_CHARS }) arg
    else "'" + arg.replace("'", """'\''""") + "'"

private val SAFE_CHARS: Set<Char> =
    (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '@', '%', '+', '=', ':', ',', '.', '/', '-')).toSet()
