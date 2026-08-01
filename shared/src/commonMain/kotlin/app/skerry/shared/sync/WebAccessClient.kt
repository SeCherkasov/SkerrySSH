package app.skerry.shared.sync

/**
 * The **web password** of an account: the credential that opens the browser account zone at
 * `/account` on the sync server. It is set, rotated and removed from the app over an authenticated
 * app session, and nowhere else — a browser signed in with it cannot change it.
 *
 * It is not the master password and derives no key. A browser holding it reads the metadata the
 * server already keeps in plaintext (device list, record sizes, activity) and can revoke a device;
 * it cannot decrypt a record. The master password never travels to the server that serves the page.
 *
 * Separate from [SyncClient] for the same reason [app.skerry.shared.team.TeamClient] is: an
 * implementation may not speak this protocol at all, and a scoped wrapper around a session (team
 * scope) has no business offering account-level credential management.
 */
interface WebAccessClient {

    /** Whether the account currently has a web password set. */
    suspend fun webAccessEnabled(session: SyncSession): Boolean

    /**
     * Sets or rotates the web password. Rotation does not close a browser already signed in — it is
     * a change of credential, and that session dies with its token.
     *
     * [password] is not wiped here: the wire needs an immutable `String`, so a copy of the password
     * outlives the call whatever this does (the same limitation as the SRP authKey hex). The caller
     * owns the array and wipes it.
     */
    suspend fun setWebPassword(session: SyncSession, password: CharArray)

    /**
     * Removes the web password and, with it, the browser session it was holding open — the server
     * revokes every `platform = "web"` device of the account in the same transaction. Removing a
     * credential has to close the door that is already open, not only the one being knocked on.
     */
    suspend fun clearWebPassword(session: SyncSession)
}

/**
 * Length bounds the sync server enforces on a web password (`POST /auth/web-password` answers 400
 * outside them). Mirrored here so the app can refuse a bad value without a round-trip; the server
 * remains the authority.
 */
const val MIN_WEB_PASSWORD_LENGTH: Int = 8

/** See [MIN_WEB_PASSWORD_LENGTH]; the ceiling only keeps an absurd input out of the server's Argon2. */
const val MAX_WEB_PASSWORD_LENGTH: Int = 256
