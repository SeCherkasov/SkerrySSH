package app.skerry.ui.vault

import app.skerry.shared.vault.Credential
import app.skerry.ui.design.folderNames

/**
 * Names a category's folders in the one collapsed set the app persists
 * ([app.skerry.ui.design.folderCollapseKey]). The kind is part of the scope because it is the outer
 * level of the keychain: a `client-acme` folder of SSH keys and a `client-acme` folder of passwords
 * are two sections on two screens, and folding one must not fold the other.
 */
fun vaultFolderScope(kind: VaultCategoryKind): String = "vault/${kind.name}"

/**
 * Folders the keychain already uses — what the edit dialog's "Group" select offers. Taken across
 * every kind rather than the open one: a folder is the customer or the environment a secret belongs
 * to, and filing a password into the folder its key already lives in must not require retyping the
 * name (which is how `client-acme` and `Client-Acme` become two folders).
 *
 * The name is plaintext the vault holds inside the encrypted payload ([Credential.group]) — it is
 * never read from a record header, so this list exists only behind an unlocked vault.
 */
fun credentialFolders(credentials: List<Credential>): List<String> =
    folderNames(credentials.map { it.group })
