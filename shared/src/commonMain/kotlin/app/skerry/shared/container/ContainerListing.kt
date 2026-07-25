package app.skerry.shared.container

/**
 * One entry of the host's container catalog: a Docker container or a Kubernetes pod that can be
 * entered. [name] is what goes into [ContainerSpec.target]; [id] (Docker's short id), [image] and
 * [status] are shown next to it. [containers] lists the pod's containers (Kubernetes only) so the
 * user can tell a multi-container pod apart and fill [ContainerSpec.podContainer].
 */
data class ContainerEntry(
    val name: String,
    val id: String = "",
    val image: String = "",
    val status: String = "",
    val containers: List<String> = emptyList(),
)

/** Cap on parsed entries: a host with thousands of containers must not blow up the picker. */
const val MAX_CONTAINER_ENTRIES: Int = 500

/**
 * Parse the output of [containerListCommandLine]. The output is untrusted (a remote command's
 * stdout, possibly truncated mid-line or mixed with a CLI warning), so unparseable lines are
 * skipped instead of failing the listing, and the result is capped at [MAX_CONTAINER_ENTRIES].
 */
fun parseContainerList(runtime: ContainerRuntime, output: String): List<ContainerEntry> =
    output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            when (runtime) {
                ContainerRuntime.DOCKER -> parseDockerRow(line)
                ContainerRuntime.KUBERNETES -> parsePodRow(line)
            }
        }
        .take(MAX_CONTAINER_ENTRIES)
        .toList()

/** `<id>\t<name>\t<image>\t<status>`; trailing columns may be missing on an older docker. */
private fun parseDockerRow(line: String): ContainerEntry? {
    val cells = line.split('\t')
    if (cells.size < 2) return null
    val name = cells[1].trim()
    if (name.isEmpty()) return null
    return ContainerEntry(
        name = name,
        id = cells[0].trim(),
        image = cells.getOrElse(2) { "" }.trim(),
        status = cells.getOrElse(3) { "" }.trim(),
    )
}

/** `<name> <status> <container>[,<container>…]` from kubectl's space-padded custom columns. */
private fun parsePodRow(line: String): ContainerEntry? {
    val cells = line.split(WHITESPACE).filter { it.isNotEmpty() }
    if (cells.size < 2) return null
    return ContainerEntry(
        name = cells[0],
        status = cells[1],
        containers = cells.getOrElse(2) { "" }
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "<none>" },
    )
}

private val WHITESPACE = Regex("\\s+")
