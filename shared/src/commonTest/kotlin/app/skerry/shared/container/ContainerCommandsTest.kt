package app.skerry.shared.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Command building for container exec: the argv handed to the transport and its quoted form for a
 * remote shell. Quoting is the security-relevant part — the container/pod name is free-form user
 * input that ends up inside a command line on the remote host.
 */
class ContainerCommandsTest {

    @Test
    fun `docker exec runs an interactive tty with the default shell`() {
        val spec = ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "web")
        assertEquals(listOf("docker", "exec", "-i", "-t", "web", "sh"), containerExecArgv(spec))
    }

    @Test
    fun `docker exec honors the configured shell`() {
        val spec = ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "web", shell = "/bin/bash")
        assertEquals(listOf("docker", "exec", "-i", "-t", "web", "/bin/bash"), containerExecArgv(spec))
    }

    @Test
    fun `docker exec ignores kubernetes-only fields`() {
        val spec = ContainerSpec(
            runtime = ContainerRuntime.DOCKER,
            target = "web",
            namespace = "prod",
            podContainer = "app",
        )
        assertEquals(listOf("docker", "exec", "-i", "-t", "web", "sh"), containerExecArgv(spec))
    }

    @Test
    fun `kubectl exec passes namespace and container and separates the shell`() {
        val spec = ContainerSpec(
            runtime = ContainerRuntime.KUBERNETES,
            target = "api-0",
            namespace = "prod",
            podContainer = "app",
            shell = "bash",
        )
        assertEquals(
            listOf("kubectl", "exec", "-i", "-t", "api-0", "-n", "prod", "-c", "app", "--", "bash"),
            containerExecArgv(spec),
        )
    }

    @Test
    fun `kubectl exec omits blank namespace and container`() {
        val spec = ContainerSpec(runtime = ContainerRuntime.KUBERNETES, target = "api-0")
        assertEquals(listOf("kubectl", "exec", "-i", "-t", "api-0", "--", "sh"), containerExecArgv(spec))
    }

    @Test
    fun `exec rejects a blank target`() {
        assertFailsWith<IllegalArgumentException> {
            containerExecArgv(ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "  "))
        }
    }

    @Test
    fun `list command asks docker for tab-separated running containers`() {
        val line = containerListCommandLine(ContainerSpec(runtime = ContainerRuntime.DOCKER))
        assertEquals("docker ps --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.Status}}'", line)
    }

    @Test
    fun `list command scopes kubernetes pods to the namespace`() {
        val line = containerListCommandLine(
            ContainerSpec(runtime = ContainerRuntime.KUBERNETES, namespace = "prod"),
        )
        assertEquals(
            "kubectl get pods --no-headers -o " +
                "'custom-columns=NAME:.metadata.name,STATUS:.status.phase,CONTAINERS:.spec.containers[*].name' " +
                "-n prod",
            line,
        )
    }

    @Test
    fun `quoting leaves plain arguments bare`() {
        assertEquals("docker exec -i -t web_1 /bin/sh", shellCommandLine(listOf("docker", "exec", "-i", "-t", "web_1", "/bin/sh")))
    }

    @Test
    fun `quoting neutralizes shell metacharacters in a container name`() {
        assertEquals(
            "docker exec -i -t 'web; rm -rf /' sh",
            shellCommandLine(listOf("docker", "exec", "-i", "-t", "web; rm -rf /", "sh")),
        )
    }

    @Test
    fun `quoting escapes an embedded single quote`() {
        assertEquals("""'we'\''b'""", shellCommandLine(listOf("we'b")))
    }

    @Test
    fun `quoting an empty argument keeps it as an argument`() {
        assertEquals("cmd ''", shellCommandLine(listOf("cmd", "")))
    }

    @Test
    fun `spec normalization trims fields and drops blanks`() {
        val spec = ContainerSpec(
            runtime = ContainerRuntime.KUBERNETES,
            target = "  api-0 ",
            namespace = "  ",
            podContainer = " app ",
            shell = "  bash ",
        ).normalized()
        assertEquals(ContainerSpec(ContainerRuntime.KUBERNETES, "api-0", "", "app", "bash"), spec)
    }

    @Test
    fun `spec is incomplete without a target`() {
        assertEquals(false, ContainerSpec(target = " ").isComplete)
        assertEquals(true, ContainerSpec(target = "web").isComplete)
    }

    @Test
    fun `a target that looks like a flag is rejected`() {
        // Quoting can't help here: `docker exec -i -t --user=root sh` would be read as an OPTION by
        // docker/kubectl themselves. Real container and pod names always start alphanumerically.
        assertEquals(false, ContainerSpec(target = "--user=root").isComplete)
        assertEquals(false, ContainerSpec(target = "  -it  ").isComplete)
        assertFailsWith<IllegalArgumentException> {
            containerExecArgv(ContainerSpec(target = "--user=root"))
        }
    }
}
