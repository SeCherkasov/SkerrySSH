package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web password is a server-side credential unrelated to any vault key: set from the app over an
 * authenticated session, verified when a browser signs in. Clearing it has to close the door that is
 * already open, not only the one being knocked on.
 */
class WebPasswordRepositoryTest {

    @Test
    fun `an account starts without a web password and a set one verifies`() = withTestDb { db ->
        val accounts = AccountRepository(db)
        seedAccount(db)

        assertNull(accounts.webPasswordHash("alice@example.com"))
        assertFalse(accounts.verifyWebPassword("alice@example.com", "anything"))

        assertTrue(accounts.setWebPassword("alice@example.com", "web-pw-1"))
        assertTrue(accounts.verifyWebPassword("alice@example.com", "web-pw-1"))
        assertFalse(accounts.verifyWebPassword("alice@example.com", "web-pw-2"))
    }

    @Test
    fun `an unknown account cannot have a web password set and never verifies`() = withTestDb { db ->
        val accounts = AccountRepository(db)
        assertFalse(accounts.setWebPassword("nobody@example.com", "web-pw"))
        assertFalse(accounts.verifyWebPassword("nobody@example.com", "web-pw"))
    }

    @Test
    fun `clearing revokes the live web session and leaves other devices alone`() = withTestDb { db ->
        val accounts = AccountRepository(db)
        val devices = DeviceRepository(db)
        seedAccount(db)
        accounts.setWebPassword("alice@example.com", "web-pw")
        devices.register("alice@example.com", "laptop", "Laptop", "Linux")
        devices.register("alice@example.com", WebSession.DEVICE_ID, "browser", WebSession.PLATFORM)

        val revoked = accounts.clearWebPassword("alice@example.com")

        assertEquals(listOf(WebSession.DEVICE_ID), revoked)
        assertNull(accounts.webPasswordHash("alice@example.com"))
        assertTrue(devices.isRevoked("alice@example.com", WebSession.DEVICE_ID))
        assertFalse(devices.isRevoked("alice@example.com", "laptop"))
    }

    @Test
    fun `rotating does not revoke anything`() = withTestDb { db ->
        val accounts = AccountRepository(db)
        val devices = DeviceRepository(db)
        seedAccount(db)
        accounts.setWebPassword("alice@example.com", "web-pw-1")
        devices.register("alice@example.com", WebSession.DEVICE_ID, "browser", WebSession.PLATFORM)

        assertTrue(accounts.setWebPassword("alice@example.com", "web-pw-2"))

        // A change of credential, not a revocation: the browser holding a valid token stays signed
        // in until it expires.
        assertFalse(devices.isRevoked("alice@example.com", WebSession.DEVICE_ID))
        assertTrue(accounts.verifyWebPassword("alice@example.com", "web-pw-2"))
        assertFalse(accounts.verifyWebPassword("alice@example.com", "web-pw-1"))
    }

    @Test
    fun `clearing an unknown account reports it instead of pretending`() = withTestDb { db ->
        assertNull(AccountRepository(db).clearWebPassword("nobody@example.com"))
    }

    @Test
    fun `clearing with no browser open revokes nothing and still succeeds`() = withTestDb { db ->
        val accounts = AccountRepository(db)
        val devices = DeviceRepository(db)
        seedAccount(db)
        accounts.setWebPassword("alice@example.com", "web-pw")
        devices.register("alice@example.com", "laptop", "Laptop", "Linux")

        // Empty, not null: the account exists and its password is gone. Null means "no such
        // account", and the route answers 404 to it — the two must not collapse into one.
        assertEquals(emptyList(), accounts.clearWebPassword("alice@example.com"))
        assertNull(accounts.webPasswordHash("alice@example.com"))
        assertFalse(devices.isRevoked("alice@example.com", "laptop"))
    }
}
