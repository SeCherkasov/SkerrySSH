package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser

/**
 * Platform local file browser for the left pane of the two-pane SFTP view. Desktop returns
 * `LocalFileBrowser` over `FileSystem.SYSTEM` starting at the user's home directory; Android
 * returns a stub over shared storage root (used only by the desktop shell currently).
 */
expect fun platformLocalBrowser(): FileBrowser
