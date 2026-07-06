package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountAndPairingTest {

    @Test
    fun `account create is unique and round-trips wrapped data key`() = withTestDb { db ->
        val repo = AccountRepository(db)
        val wrapped = byteArrayOf(9, 8, 7, 6)
        repo.create("bob@example.com", srpSalt = "deadbeef", srpVerifier = "cafe", wrappedDataKey = wrapped)

        val found = repo.find("bob@example.com")!!
        assertEquals("deadbeef", found.srpSalt)
        assertEquals("cafe", found.srpVerifier)
        assertContentEquals(wrapped, found.wrappedDataKey)

        assertFailsWith<IllegalStateException> {
            repo.create("bob@example.com", "x", "y", byteArrayOf(0))
        }
    }

    @Test
    fun `device register is idempotent and revoke flags it`() = withTestDb { db ->
        seedAccount(db)
        val repo = DeviceRepository(db)
        repo.register("alice@example.com", "dev1", "Laptop", now = 100)
        repo.register("alice@example.com", "dev1", "Laptop renamed", now = 200)

        val list = repo.list("alice@example.com")
        assertEquals(1, list.size)
        assertEquals("Laptop renamed", list.single().name)

        assertTrue(repo.revoke("alice@example.com", "dev1"))
        assertTrue(repo.isRevoked("alice@example.com", "dev1"))
        // an unknown device is considered revoked
        assertTrue(repo.isRevoked("alice@example.com", "ghost"))
    }

    @Test
    fun `re-register after revoke re-enrolls the device`() = withTestDb { db ->
        seedAccount(db)
        val repo = DeviceRepository(db)
        repo.register("alice@example.com", "dev1", "Laptop", now = 100)
        assertTrue(repo.revoke("alice@example.com", "dev1"))
        assertTrue(repo.isRevoked("alice@example.com", "dev1"))

        // Re-registering the same device (login flow) clears the revoke: knowing the master
        // password proves account ownership, and revoke only invalidates current tokens rather
        // than locking the device out forever.
        repo.register("alice@example.com", "dev1", "Laptop", now = 300)
        assertFalse(repo.isRevoked("alice@example.com", "dev1"))
    }

    @Test
    fun `same deviceId under two accounts stays isolated`() = withTestDb { db ->
        AccountRepository(db).create("a@x", "0", "a", byteArrayOf(0))
        AccountRepository(db).create("b@x", "0", "a", byteArrayOf(0))
        val repo = DeviceRepository(db)
        repo.register("a@x", "shared-id", "A device")
        repo.register("b@x", "shared-id", "B device")

        // each account has its own device under this id
        assertEquals("A device", repo.find("a@x", "shared-id")!!.name)
        assertEquals("B device", repo.find("b@x", "shared-id")!!.name)

        // revoking in account A doesn't affect account B's device with the same id
        repo.revoke("a@x", "shared-id")
        assertTrue(repo.isRevoked("a@x", "shared-id"))
        assertFalse(repo.isRevoked("b@x", "shared-id"))
    }

    @Test
    fun `pairing consume is one-shot and honors TTL`() = withTestDb { db ->
        seedAccount(db)
        val repo = PairingRepository(db)
        val secret = byteArrayOf(4, 2)
        repo.create("code123", "alice@example.com", secret, expiresAt = 1_000)

        val first = repo.consume("code123", now = 500)!!
        assertContentEquals(secret, first.encryptedDataKey)
        // the same code cannot be redeemed twice
        assertNull(repo.consume("code123", now = 600))

        // an expired code is not issued
        repo.create("code456", "alice@example.com", secret, expiresAt = 1_000)
        assertNull(repo.consume("code456", now = 2_000))
    }
}
