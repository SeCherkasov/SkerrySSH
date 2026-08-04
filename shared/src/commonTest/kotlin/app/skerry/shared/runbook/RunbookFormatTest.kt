package app.skerry.shared.runbook

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The stored form of a runbook. Records written by earlier versions live in vaults and arrive over
 * sync, so a step without a `kind` and a runbook without a `policy` have to keep reading — a
 * payload that fails to decode is dropped by [app.skerry.shared.vault.VaultRecordCodec], and the
 * runbook would silently vanish from the library.
 */
class RunbookFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a step stored before transfer steps existed reads as a command step`() {
        val stored = """
            {"id":"rb","label":"Deploy","steps":[
              {"id":"s1","title":"Health check","command":"curl -fsS localhost/healthz",
               "confirm":false,"continueOnError":true}
            ],"tags":["ops"]}
        """.trimIndent()

        val runbook = json.decodeFromString(Runbook.serializer(), stored)

        val step = assertIs<RunbookStep.Command>(runbook.steps.single())
        assertEquals("curl -fsS localhost/healthz", step.command)
        assertEquals("Health check", step.title)
        assertEquals(false, step.confirm)
        assertEquals(true, step.continueOnError)
    }

    @Test
    fun `a step kind this version doesn't know reads as a command step`() {
        // Written by a newer client and synced back here: the unknown step still has to load, or one
        // step of a shared runbook would take the whole runbook out of the library.
        val stored = """
            {"id":"rb","label":"Deploy","steps":[
              {"kind":"http","id":"s1","title":"Warm the cache","command":"curl -fsS localhost/warm"}
            ]}
        """.trimIndent()

        val runbook = json.decodeFromString(Runbook.serializer(), stored)

        val step = assertIs<RunbookStep.Command>(runbook.steps.single())
        assertEquals("curl -fsS localhost/warm", step.command)
    }

    @Test
    fun `a runbook stored before run policy existed gets the default policy`() {
        val stored = """{"id":"rb","label":"Deploy","steps":[]}"""

        val runbook = json.decodeFromString(Runbook.serializer(), stored)

        assertEquals(RunbookPolicy(), runbook.policy)
        assertTrue(runbook.policy.stopOnFirstFailure)
        assertEquals(2, runbook.policy.watchdogMinutes)
    }

    @Test
    fun `a transfer step round-trips`() {
        val runbook = Runbook(
            id = "rb",
            label = "Deploy",
            steps = listOf(
                RunbookStep.Transfer(
                    id = "s1",
                    title = "Upload release archive",
                    localPath = "release-0.2.1.tar.gz",
                    remotePath = "/var/www/app/releases",
                ),
            ),
        )

        val decoded = json.decodeFromString(Runbook.serializer(), json.encodeToString(Runbook.serializer(), runbook))

        assertEquals(runbook, decoded)
        val step = assertIs<RunbookStep.Transfer>(decoded.steps.single())
        assertEquals(RunbookTransferDirection.UPLOAD, step.direction)
    }

    @Test
    fun `a written step carries its kind so a later version can tell the two apart`() {
        val runbook = Runbook(id = "rb", label = "Deploy", steps = listOf(RunbookStep.Command(id = "s1", command = "uptime")))

        val text = json.encodeToString(Runbook.serializer(), runbook)

        assertTrue(text.contains("\"kind\":\"command\""), text)
    }

    @Test
    fun `a policy round-trips with its own values`() {
        val runbook = Runbook(
            id = "rb",
            label = "Deploy",
            policy = RunbookPolicy(stopOnFirstFailure = false, watchdogMinutes = 5),
        )

        val decoded = json.decodeFromString(Runbook.serializer(), json.encodeToString(Runbook.serializer(), runbook))

        assertEquals(runbook.policy, decoded.policy)
    }
}
