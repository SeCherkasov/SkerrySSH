package app.skerry.shared.container

import kotlinx.serialization.Serializable

/**
 * Container runtime a profile talks to. [DOCKER] uses the `docker` CLI on the host (Podman's CLI is
 * argument-compatible but untested, so it isn't offered); [KUBERNETES] uses `kubectl` with the
 * host's current kube context. Serialized by name (like [app.skerry.shared.ssh.ConnectionType]), so
 * enum order doesn't affect stored profiles.
 */
@Serializable
enum class ContainerRuntime { DOCKER, KUBERNETES }

/**
 * What to exec into for a [app.skerry.shared.ssh.ConnectionType.CONTAINER] profile. The profile's
 * own address/port/username/credential/jump describe the SSH hop that carries the CLI, this
 * describes what runs there.
 *
 * [target] is the container name/id ([ContainerRuntime.DOCKER]) or the pod name
 * ([ContainerRuntime.KUBERNETES]) — the only required field. [namespace] and [podContainer] are
 * Kubernetes-only (`-n` / `-c`); blank means the context default / the pod's first container.
 * [shell] is the program to run inside the container, blank → [DEFAULT_CONTAINER_SHELL] (`sh` is the
 * one binary practically every image has; `bash` is a per-profile choice, not a guess).
 *
 * Stored trimmed ([normalized]) so a stray space in the form doesn't become part of a container name
 * on the wire. All values are user input and reach a remote shell — they are quoted at command build
 * time ([shellCommandLine]), never interpolated raw.
 */
@Serializable
data class ContainerSpec(
    val runtime: ContainerRuntime = ContainerRuntime.DOCKER,
    val target: String = "",
    val namespace: String = "",
    val podContainer: String = "",
    val shell: String = "",
) {
    /** Trimmed copy; blank fields collapse to empty (the "not set" value for all of them). */
    fun normalized(): ContainerSpec = ContainerSpec(
        runtime = runtime,
        target = target.trim(),
        namespace = namespace.trim(),
        podContainer = podContainer.trim(),
        shell = shell.trim(),
    )

    /**
     * Whether there is something to exec into. A name starting with `-` is refused: shell quoting
     * doesn't help there — `docker`/`kubectl` would parse it as one of their own options — and no
     * real container or pod can be named that way (both require an alphanumeric first character).
     */
    val isComplete: Boolean get() = target.isNotBlank() && !target.trim().startsWith("-")
}

/** Shell run inside the container when the profile doesn't name one. */
const val DEFAULT_CONTAINER_SHELL: String = "sh"
