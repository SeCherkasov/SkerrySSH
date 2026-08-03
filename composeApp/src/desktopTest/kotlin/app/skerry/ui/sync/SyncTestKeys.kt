package app.skerry.ui.sync

import app.skerry.shared.vault.MasterKey
import app.skerry.shared.vault.VaultCrypto
import java.util.concurrent.ConcurrentHashMap

private val accountKeys = ConcurrentHashMap<Pair<String, String>, MasterKey>()

/**
 * The account master key the coordinator tests wrap their fixture dataKey under — derived once per
 * (password, accountId) for the whole test JVM instead of once per test.
 *
 * Argon2id at m = 64 MiB is the bulk of these classes' wall-clock cost and every test in them uses the
 * same pair, so repeating the derivation only makes the suite slower and more sensitive to machine load
 * (issue #141). Deliberately never zeroized: the coordinator derives and wipes its own key, this one is
 * a fixture over a hardcoded test password and only ever feeds `wrapDataKey`.
 *
 * The cache is keyed by (password, accountId) only, and a hit never touches [crypto] — fine while every
 * caller passes a real [app.skerry.shared.vault.IonspinVaultCrypto] after `initializeVaultCrypto()`, but
 * a caller handing in a different implementation would silently get the Ionspin-derived key.
 */
fun syncAccountKey(crypto: VaultCrypto, password: String, accountId: String): MasterKey =
    accountKeys.getOrPut(password to accountId) {
        crypto.deriveMasterKey(password.toCharArray(), crypto.deriveSyncSalt(accountId))
    }
