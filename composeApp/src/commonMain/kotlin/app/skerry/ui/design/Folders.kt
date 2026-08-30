package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_group_unnamed
import app.skerry.ui.generated.resources.shtail_ungrouped
import org.jetbrains.compose.resources.stringResource

/**
 * One folder of a list: the group's name and what it holds, in the order the source list had them.
 *
 * The host sidebar has had folders since the beginning ([app.skerry.ui.host.HostFolder]); this is
 * the same idea for the lists that got them later — the snippet library, the runbooks and the
 * keychain. They stay separate types because a host folder also carries drag-and-drop targets and an
 * empty-folder side channel, which none of the others has.
 */
@Immutable
data class Folder<T>(val name: String, val items: List<T>)

/**
 * Technical key of the synthetic bucket holding the records with no folder. Not localized: it is the
 * grouping key *and* part of the key the fold state is filed under, so a locale change must not
 * split one bucket into two. For display, use [folderLabel].
 *
 * Leading NUL so that no stored name can equal it ([app.skerry.shared.text.normalizeGroup] drops
 * NUL, and [foldersOf] rejects it besides): a user is free to call a folder `Ungrouped`, and it then
 * becomes a folder like any other instead of a second section sharing the bucket's name, its fold
 * state and its Compose key.
 */
const val UNGROUPED_FOLDER: String = "\u0000ungrouped"

/** Localized label of the [UNGROUPED_FOLDER] bucket (display only). */
@Composable
fun ungroupedFolderLabel(): String = stringResource(Res.string.shtail_ungrouped)

/**
 * What a folder name draws as, in a header and in the "Group" picker alike: the localized label for
 * the synthetic bucket, otherwise the stored name put through [untrustedLabel] — a folder name can
 * arrive over sync from a client that never normalized it, and a bidi override in it must not be
 * able to make one folder read as another.
 *
 * A name that filters away to nothing (zero-width characters only) falls back to a label rather than
 * to a blank one, the way [app.skerry.ui.design.spaceLabel] does: a nameless header with a count
 * badge reads as a rendering fault, and there would be no way to tell two of them apart.
 */
@Composable
fun folderLabel(name: String): String = when (name) {
    UNGROUPED_FOLDER -> ungroupedFolderLabel()
    else -> remember(name) { untrustedLabel(name) }.ifBlank { stringResource(Res.string.shtail_group_unnamed) }
}

/**
 * Split [items] into folders by [group]: named folders in order of first appearance,
 * then the [UNGROUPED_FOLDER] bucket for everything whose group is `null` or blank. Items keep
 * their source order inside a folder, and an empty [items] gives no folders at all.
 *
 * First-appearance matches host folders: the list has an order the user drags into shape
 * and its folders inherit it, supporting manual drag-and-drop group reordering across devices.
 *
 * Pure function (no Compose), shared by the desktop sections and the mobile ones.
 */
fun <T> foldersOf(items: List<T>, group: (T) -> String?): List<Folder<T>> {
    val named = LinkedHashMap<String, MutableList<T>>()
    val ungrouped = mutableListOf<T>()
    for (item in items) {
        val name = storedFolderName(group(item))
        if (name == null) ungrouped += item else named.getOrPut(name) { mutableListOf() }.add(item)
    }
    return buildList {
        named.forEach { (name, list) -> add(Folder(name, list)) }
        if (ungrouped.isNotEmpty()) add(Folder(UNGROUPED_FOLDER, ungrouped))
    }
}

/**
 * Whether anything in [items] is filed at all. With nothing filed the list renders flat: a single
 * "Ungrouped" header over the whole library is pure chrome, and it would appear the day the feature
 * shipped for every user who never asked for folders.
 */
fun <T> hasFolders(items: List<T>, group: (T) -> String?): Boolean =
    items.any { storedFolderName(group(it)) != null }

/**
 * The folder a stored [group] actually names, or `null` for a record that belongs in the bucket.
 *
 * Rejecting [UNGROUPED_FOLDER] here is what makes the sentinel unforgeable rather than merely
 * unwritable: every form normalizes the NUL away before saving, but a record decoded from sync was
 * written by a client this one has no say over, and a name equal to the sentinel would otherwise
 * draw a second bucket beside the real one — same header, same Compose key, same fold state.
 */
private fun storedFolderName(group: String?): String? =
    group?.takeIf { it.isNotBlank() && it != UNGROUPED_FOLDER }

/**
 * The folder names already in use, case-insensitively sorted and deduplicated — what the "Group"
 * select offers so a folder is picked rather than retyped (and misspelled into a second one).
 * Values stay case-exact: two names differing only in case are two folders, as they are for hosts.
 */
fun folderNames(groups: List<String?>): List<String> =
    groups.mapNotNull(::storedFolderName)
        .distinct()
        .sortedWith(compareBy({ it.lowercase() }, { it }))

/**
 * Key a folder's fold state is filed under in the single collapsed set the app persists
 * ([FolderCollapse]). [scope] names the list ("snippet", "vault/PASSWORDS"), so a `Production`
 * folder in the keychain and a `Production` folder of hosts fold independently — the same reason the
 * team sections of the host sidebar carry a prefix of their own.
 *
 * The name itself never reaches the key. That set is persisted to a plain file beside the config,
 * outside the vault and readable while it is locked, and a keychain folder called `client-acme` is
 * precisely the metadata the payload is encrypted to keep ([app.skerry.shared.vault.Credential]).
 * A view preference is no reason to hand it back in cleartext, so the name goes in as a digest —
 * with the limits [nameDigest] states.
 */
fun folderCollapseKey(scope: String, name: String): String = "\u0000$scope\u0000${nameDigest(name)}"

/**
 * FNV-1a over the name's UTF-8, hex. Not a security primitive and not meant as one: it only has to
 * be stable across runs and collide with nothing among the handful of folders one list has.
 *
 * It is unkeyed, unsalted and cheap to invert — anyone willing to write the code gets a short name
 * back out of it. What it buys is that the file does not *spell the folders out* to whoever opens it,
 * and that no name, however pasted or synced, can forge the key of a folder in another list. A file
 * that keeps a secret would have to be keyed on something held in the vault, and the fold state is
 * read before the vault is unlocked.
 */
private fun nameDigest(name: String): String {
    var hash = FNV_OFFSET_BASIS
    for (byte in name.encodeToByteArray()) {
        hash = hash xor byte.toUByte().toULong()
        hash *= FNV_PRIME
    }
    return hash.toString(HEX)
}

private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325UL
private const val FNV_PRIME: ULong = 0x100000001b3UL
private const val HEX = 16

/**
 * Fold state of the folder headers as a list section sees it, implemented by both design states
 * (desktop and mobile), which persist it per device — the collapsed set is a view preference, not
 * something to carry to another machine. The name alone is not the key: it comes from
 * [folderCollapseKey].
 */
interface FolderCollapse {
    fun isGroupCollapsed(name: String): Boolean
    fun toggleGroupCollapsed(name: String)
    fun expandGroup(name: String) {
        if (isGroupCollapsed(name)) toggleGroupCollapsed(name)
    }
    fun collapseGroup(name: String) {
        if (!isGroupCollapsed(name)) toggleGroupCollapsed(name)
    }
}
