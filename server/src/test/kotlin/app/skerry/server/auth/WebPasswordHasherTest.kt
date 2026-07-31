package app.skerry.server.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebPasswordHasherTest {

    @Test
    fun `a hash verifies against its own password and nothing else`() {
        val encoded = WebPasswordHasher.hash("correct horse battery")
        assertTrue(WebPasswordHasher.verify("correct horse battery", encoded))
        assertFalse(WebPasswordHasher.verify("correct horse batterz", encoded))
        assertFalse(WebPasswordHasher.verify("", encoded))
    }

    @Test
    fun `the same password hashes differently every time`() {
        // Per-hash salt: two accounts choosing the same password must not share a stored value,
        // otherwise a leaked table shows at a glance who reused what.
        assertNotEquals(WebPasswordHasher.hash("same"), WebPasswordHasher.hash("same"))
    }

    @Test
    fun `the encoded form carries the parameters it was produced with`() {
        // Parameters live in the record, not in this object: raising the cost later must not
        // invalidate every hash already stored.
        val encoded = WebPasswordHasher.hash("pw")
        val parts = encoded.split("$")
        assertTrue(encoded.startsWith("\$argon2id\$v=19\$m="), encoded)
        assertTrue(Regex("""m=\d+,t=\d+,p=\d+""").matches(parts[3]), encoded)
    }

    @Test
    fun `an unparseable record denies access instead of throwing`() {
        // A corrupted column is a closed door, not a 500.
        listOf("", "not-a-hash", "\$argon2id\$v=19\$m=x,t=2,p=1\$c2FsdA\$aGFzaA", "\$argon2i\$v=19\$m=8,t=1,p=1\$c2FsdA\$aGFzaA")
            .forEach { assertFalse(WebPasswordHasher.verify("pw", it), it) }
    }

    @Test
    fun `the miss path always denies`() {
        assertFalse(WebPasswordHasher.verifyMiss("anything"))
    }
}
