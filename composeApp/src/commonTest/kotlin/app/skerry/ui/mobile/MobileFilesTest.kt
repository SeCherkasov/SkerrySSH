package app.skerry.ui.mobile

import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.sftp.SizeParts
import app.skerry.ui.sftp.SizeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure logic for the mobile Files screen: screen mode, row meta/icons, path breadcrumb. */
class MobileFilesTest {

    private fun item(name: String, type: FileItemType, size: Long = 0): FileItem =
        FileItem(name = name, path = "/var/www/$name", type = type, size = size, modifiedEpochSeconds = 0)

    // Screen mode from session state

    @Test
    fun files_mode_picks_preview_when_no_session_manager() {
        // Preview/offscreen path with no backend.
        assertEquals(MobileFilesMode.Preview, mobileFilesMode(hasSessions = false, connected = false, connecting = false))
        assertEquals(MobileFilesMode.Preview, mobileFilesMode(hasSessions = false, connected = true, connecting = false))
    }

    @Test
    fun files_mode_is_live_only_when_connected() {
        assertEquals(MobileFilesMode.Live, mobileFilesMode(hasSessions = true, connected = true, connecting = false))
    }

    @Test
    fun files_mode_shows_connecting_while_session_opens() {
        // Session is open but still connecting; show "Connecting..." instead of flashing "No active session".
        assertEquals(
            MobileFilesMode.Connecting,
            mobileFilesMode(hasSessions = true, connected = false, connecting = true),
        )
    }

    @Test
    fun files_mode_is_no_session_when_inactive_or_failed() {
        // No active session, or it's in form/error state (neither connected nor connecting): nothing to browse.
        assertEquals(MobileFilesMode.NoSession, mobileFilesMode(hasSessions = true, connected = false, connecting = false))
    }

    // Row meta caption (direct projection of FileItem)

    @Test
    fun row_meta_shows_size_for_files_and_nothing_for_dirs() {
        assertEquals(SizeParts(SizeUnit.KB, 3, 0), mobileFileRowMeta(item("nginx.conf", FileItemType.File, size = 3072)))
        assertEquals(SizeParts(SizeUnit.Bytes, 112), mobileFileRowMeta(item("robots.txt", FileItemType.File, size = 112)))
        // The model has no permissions or item count for directories; the row has no subtitle.
        assertNull(mobileFileRowMeta(item("html", FileItemType.Directory)))
    }

    // Row leading icon

    @Test
    fun leading_icon_maps_by_type_and_script_extension() {
        assertEquals("folder", mobileFileIcon(item("html", FileItemType.Directory)))
        assertEquals("link", mobileFileIcon(item("current", FileItemType.Symlink)))
        assertEquals("description", mobileFileIcon(item("nginx.conf", FileItemType.File)))
        // Shell script: terminal icon.
        assertEquals("terminal", mobileFileIcon(item("deploy.sh", FileItemType.File)))
    }

    // Row trailing icon (the action shown by the layout)

    @Test
    fun trailing_icon_is_chevron_for_dirs_and_share_for_files() {
        assertEquals("chevron_right", mobileFileTrailingIcon(FileItemType.Directory))
        assertEquals("ios_share", mobileFileTrailingIcon(FileItemType.File))
        assertEquals("ios_share", mobileFileTrailingIcon(FileItemType.Symlink))
    }

    // Path row (breadcrumb under the switcher)

    @Test
    fun breadcrumb_joins_label_and_path() {
        assertEquals("prod-web-01 : /var/www", mobileFilesBreadcrumb("prod-web-01", "/var/www"))
        assertEquals("This Mac : /", mobileFilesBreadcrumb("This Mac", "/"))
    }
}
