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
        // неизвестное устройство считается отозванным
        assertTrue(repo.isRevoked("alice@example.com", "ghost"))
    }

    @Test
    fun `re-register after revoke re-enrolls the device`() = withTestDb { db ->
        seedAccount(db)
        val repo = DeviceRepository(db)
        repo.register("alice@example.com", "dev1", "Laptop", now = 100)
        assertTrue(repo.revoke("alice@example.com", "dev1"))
        assertTrue(repo.isRevoked("alice@example.com", "dev1"))

        // Повторная аутентификация тем же устройством (register на входе/логине) снимает отзыв:
        // знание мастер-пароля = владение аккаунтом, revoke лишь гасит текущие токены, а не блокирует
        // навсегда. Иначе устройство с верным паролем оставалось бы запертым (register=409, sync=401).
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

        // у каждого аккаунта своё устройство с этим id
        assertEquals("A device", repo.find("a@x", "shared-id")!!.name)
        assertEquals("B device", repo.find("b@x", "shared-id")!!.name)

        // отзыв в аккаунте A не трогает одноимённое устройство аккаунта B
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
        // повторная выдача того же кода невозможна
        assertNull(repo.consume("code123", now = 600))

        // истёкший код не выдаётся
        repo.create("code456", "alice@example.com", secret, expiresAt = 1_000)
        assertNull(repo.consume("code456", now = 2_000))
    }
}
