package app.skerry.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_unprintable_name
import org.jetbrains.compose.resources.stringResource

/**
 * A listing entry's name as it is drawn — in a row, in the icon it picks, in the confirmation that
 * acts on it, in the transfer strip.
 *
 * The name is the far side's text ([untrustedLabel] says what that costs), and a name made only of
 * the characters filtering drops leaves nothing at all: an empty row, or a delete confirmation
 * reading "«» will be removed permanently". One stand-in for every such sink, so the row and the
 * dialog opened from it always name the same thing.
 *
 * The rename field is deliberately not one of them: what a text field holds is edited and submitted,
 * so it must be the real name, not a drawing of it.
 */
@Composable
internal fun fileDisplayName(raw: String): String {
    val unprintable = stringResource(Res.string.sftp_unprintable_name)
    // remember: a listing row re-composes on every cursor move and every selection paint, and the
    // filter is a scan and an allocation the name has not earned twice.
    return remember(raw, unprintable) { untrustedLabel(raw).ifBlank { unprintable } }
}

/**
 * A directory path as it is drawn — a breadcrumb, a work-bar subtitle, the destination half of a
 * transfer confirmation.
 *
 * Assembled from the names the far side reported as the user walked into them, so it carries the
 * same tricks a single name does. Its own cap: a path is legitimately longer than a name, and the
 * one drawn next to a sanitized file name must not be the raw half of the same sentence.
 */
internal fun fileDisplayPath(raw: String): String = untrustedLabel(raw, MAX_DISPLAY_PATH_CHARS)

/** Cap on a drawn path — deep but bounded; the row ellipsizes long before this. */
private const val MAX_DISPLAY_PATH_CHARS = 300
