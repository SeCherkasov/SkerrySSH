package app.skerry.shared.files

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException
import app.skerry.shared.sftp.SftpProgress
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the SFTP→[FileBrowser] adapter: delegating navigation/mutations to [SftpClient], mapping
 * [SftpEntry]→[FileItem] and [SftpException]→[FileBrowserException]. Uses a recording SFTP fake.
 */
class SftpFileBrowserTest {

    private val client = RecordingSftp()
    private fun browser() = SftpFileBrowser(client, label = "prod-web-01")

    @Test
    fun `label is exposed`() {
        assertEquals("prod-web-01", browser().label)
    }

    @Test
    fun `realpath is delegated`() = runTest {
        assertEquals("/resolved/x", browser().realpath("/x"))
        assertTrue("realpath:/x" in client.calls)
    }

    @Test
    fun `list maps sftp entries to file items`() = runTest {
        client.listResult = listOf(
            SftpEntry("sub", "/d/sub", SftpEntryType.Directory, 0, 100, 0b111_101_101),
            SftpEntry("a.txt", "/d/a.txt", SftpEntryType.File, 42, 200, 0b110_100_100),
        )

        val items = browser().list("/d")

        assertEquals(2, items.size)
        assertEquals(FileItemType.Directory, items[0].type)
        assertEquals(0b111_101_101, items[0].permissions)
        val file = items[1]
        assertEquals("a.txt", file.name)
        assertEquals("/d/a.txt", file.path)
        assertEquals(FileItemType.File, file.type)
        assertEquals(42, file.size)
        assertEquals(200, file.modifiedEpochSeconds)
        assertEquals(0b110_100_100, file.permissions)
    }

    @Test
    fun `two entries under one path collapse into the first`() = runTest {
        // Nothing in SFTP promises the names in a listing are distinct: a merged/overlay filesystem,
        // a buggy server or a hostile one can all repeat one. The panel keys its rows by path, and a
        // repeat there takes the window down mid-composition (#309), so the repeat stops here.
        client.listResult = listOf(
            SftpEntry("dup", "/d/dup", SftpEntryType.File, 42, 100, 0b110_100_100),
            // A different name, so this row is collapsed by the path filter and by nothing else.
            SftpEntry("other", "/d/dup", SftpEntryType.Directory, 0, 200, 0b111_101_101),
        )

        val items = browser().list("/d")

        // The first one wins: which of the two the server meant is unknowable, and picking the later
        // one would make the listing depend on the order the packets happened to arrive in.
        assertEquals(1, items.size)
        assertEquals(FileItemType.File, items.single().type)
        assertEquals(42, items.single().size)
    }

    @Test
    fun `two entries under one name collapse too, whatever paths they claim`() = runTest {
        // A row draws the name and never the path, and every operation on a row resolves that name
        // against the directory it was listed in (#313). So a listing that repeats a name under two
        // paths draws two rows the user cannot tell apart, and deleting the second one deletes the
        // file behind the first: the duplicate-key crash one layer up, with the panel still standing.
        client.listResult = listOf(
            SftpEntry("dup", "/d/dup", SftpEntryType.File, 1, 100, 0b110_100_100),
            SftpEntry("dup", "/d/sub/dup", SftpEntryType.File, 2, 200, 0b110_100_100),
        )

        val items = browser().list("/d")

        assertEquals(1, items.size)
        assertEquals("/d/dup", items.single().path)
    }

    @Test
    fun `entries the user can act on separately all survive`() = runTest {
        client.listResult = listOf(
            SftpEntry("one", "/d/one", SftpEntryType.File, 1, 100, 0b110_100_100),
            SftpEntry("two", "/d/two", SftpEntryType.File, 2, 200, 0b110_100_100),
        )

        assertEquals(2, browser().list("/d").size)
    }

    @Test
    fun `a delete over a directory that lists itself stops instead of recursing forever`() = runTest {
        // This fixture's loop holds nothing but itself, which is why nothing is removed before the
        // refusal — see the test below for what a loop with a file beside it costs.
        // The recursive delete walks the same untrusted listing the transfer walk does, on the more
        // destructive verb: a directory listed as its own child has no bottom, the containment check
        // passes at every level because the path only grows, and the recursion ends as an Error that
        // no handler on the pane's path catches.
        client.listAnswer = { path ->
            if (path.endsWith("/loop")) listOf(SftpEntry("loop", "$path/loop", SftpEntryType.Directory, 0, 0, 0)) else null
        }

        val failure = assertFailsWith<FileBrowserException> {
            browser().delete(FileItem("loop", "/d/loop", FileItemType.Directory, 0, 0))
        }

        assertEquals(FileBrowserFailure.TreeTooLarge, failure.failure)
        assertTrue(client.calls.none { it.startsWith("remove:") || it.startsWith("rmdir:") }, "it removed something")
    }

    @Test
    fun `a loop with files beside it loses them before the refusal`() = runTest {
        // The bound ends the recursion, and that is all it ends: SFTP has no recursive delete, so a
        // walk that stops in the middle stops with what it has already removed gone. Recorded here
        // rather than left to be discovered — the refusal the user is shown is not "nothing
        // happened", and no wording can make it one.
        client.listAnswer = { path ->
            if (path.endsWith("/loop")) {
                listOf(
                    SftpEntry("keep.txt", "$path/keep.txt", SftpEntryType.File, 1, 0, 0b110_100_100),
                    SftpEntry("loop", "$path/loop", SftpEntryType.Directory, 0, 0, 0),
                )
            } else {
                null
            }
        }

        assertFailsWith<FileBrowserException> {
            browser().delete(FileItem("loop", "/d/loop", FileItemType.Directory, 0, 0))
        }

        assertEquals(MAX_TREE_DEPTH, client.calls.count { it.startsWith("remove:") })
    }

    @Test
    fun `a tree wider than a transfer plan may hold is still deleted whole`() = runTest {
        // Two things at once. The entry cap is what a plan held whole in memory costs; this walk
        // holds no plan and deletes as it goes, so the same cap would stop it with most of the tree
        // already gone and report the tree as refused, on an operation that destroyed two thirds of
        // it. And breadth must not consume the budget either: siblings are walked one after another,
        // so each one gets the same room as the last — a budget decremented per listing instead of
        // per level would delete the first two directories and refuse the third.
        val perDirectory = 34_000
        val subdirs = List(3) { SftpEntry("s$it", "/d/big/s$it", SftpEntryType.Directory, 0, 0, 0b111_101_101) }
        client.listAnswer = { path ->
            when {
                path == "/d/big" -> subdirs
                path.startsWith("/d/big/s") -> List(perDirectory) {
                    SftpEntry("f$it", "$path/f$it", SftpEntryType.File, 0, 0, 0b110_100_100)
                }
                else -> null
            }
        }

        browser().delete(FileItem("big", "/d/big", FileItemType.Directory, 0, 0))

        assertEquals(3 * perDirectory, client.calls.count { it.startsWith("remove:") })
        assertTrue(subdirs.all { "rmdir:${it.path}" in client.calls })
        assertTrue("rmdir:/d/big" in client.calls)
        // Every sibling asked for the same room: what the level above holds, and nothing more.
        assertEquals(
            listOf(MAX_LISTING_ENTRIES) + List(3) { MAX_LISTING_ENTRIES - subdirs.size },
            client.listLimits,
        )
    }

    @Test
    fun `a listing bigger than the client can hold is refused, not drawn short`() = runTest {
        // The listing is the server's answer, and nothing above this call bounds it: the transfer
        // plan's cap is checked after the listing exists, and the de-duplication passes run over it.
        // A listing cut short without saying so is a directory the user believes they have seen.
        client.listAnswer = { path -> if (path == "/d/huge") oversizedListing(path, MAX_LISTING_ENTRIES + 1) else null }

        val e = assertFailsWith<FileBrowserException> { browser().list("/d/huge") }
        assertEquals(FileBrowserFailure.TreeTooLarge, e.failure)
    }

    @Test
    fun `the browser asks the client for one entry past the cap`() = runTest {
        // How the truncation is detected at all: the client stops one over, so "full" and "cut
        // short" are distinguishable without holding the server's whole answer.
        browser().list("/d")

        assertEquals(listOf(MAX_LISTING_ENTRIES), client.listLimits)
    }

    @Test
    fun `a directory too wide to list is refused before its contents are removed`() = runTest {
        // Same shape as the depth refusal: the walk ends without promising the tree is untouched,
        // but nothing inside the directory it refused was deleted.
        client.listAnswer = { path -> if (path == "/d/huge") oversizedListing(path, MAX_LISTING_ENTRIES + 1) else null }

        val e = assertFailsWith<FileBrowserException> {
            browser().delete(FileItem("huge", "/d/huge", FileItemType.Directory, 0, 0))
        }

        assertEquals(FileBrowserFailure.TreeTooLarge, e.failure)
        assertTrue(client.calls.none { it.startsWith("remove:") || it.startsWith("rmdir:") })
    }

    @Test
    fun `listings held on the way down share one budget, not one each`() = runTest {
        // The walk keeps every listing above it alive while it works on the level below, so a level
        // allowed a full cap of its own would hold as many caps as the tree is deep. The first child
        // is the directory, so the refusal lands before anything beside it is removed.
        val held = 30_000
        client.listAnswer = { path ->
            when (path) {
                "/d/deep" -> oversizedListing(path, held, firstIsDirectory = true)
                "/d/deep/f0" -> oversizedListing(path, MAX_LISTING_ENTRIES)
                else -> null
            }
        }

        val e = assertFailsWith<FileBrowserException> {
            browser().delete(FileItem("deep", "/d/deep", FileItemType.Directory, 0, 0))
        }

        assertEquals(FileBrowserFailure.TreeTooLarge, e.failure)
        // The cap minus what the level above is holding: the nested listing is asked for the rest.
        assertEquals(listOf(MAX_LISTING_ENTRIES, MAX_LISTING_ENTRIES - held), client.listLimits)
        assertTrue(client.calls.none { it.startsWith("remove:") || it.startsWith("rmdir:") })
    }

    @Test
    fun `mkdir and rename are delegated`() = runTest {
        browser().mkdir("/d/new")
        browser().rename("/d/a", "/d/b")

        assertTrue("mkdir:/d/new" in client.calls)
        assertTrue("rename:/d/a->/d/b" in client.calls)
    }

    @Test
    fun `delete uses rmdir for empty directories and remove for files`() = runTest {
        client.listings["/d/sub"] = emptyList() // explicitly empty directory: rmdir directly, no content removal
        browser().delete(FileItem("sub", "/d/sub", FileItemType.Directory, 0, 0))
        browser().delete(FileItem("a.txt", "/d/a.txt", FileItemType.File, 1, 0))

        assertEquals(listOf("list:/d/sub", "rmdir:/d/sub", "remove:/d/a.txt"), client.calls)
    }

    @Test
    fun `delete rejects a listing entry whose path escapes the directory`() = runTest {
        // Server returned a listing entry outside the directory being deleted — recursion must not delete it.
        client.listings["/d/sub"] = listOf(
            SftpEntry("evil", "/etc/passwd", SftpEntryType.File, 0, 0, 0),
        )

        assertFailsWith<FileBrowserException> {
            browser().delete(FileItem("sub", "/d/sub", FileItemType.Directory, 0, 0))
        }
        assertFalse("remove:/etc/passwd" in client.calls)
    }

    @Test
    fun `delete of a non-empty directory clears contents recursively then rmdir`() = runTest {
        // /d/sub: a file, a symlink, and a nested non-empty directory.
        client.listings["/d/sub"] = listOf(
            SftpEntry("a.txt", "/d/sub/a.txt", SftpEntryType.File, 1, 0, 0),
            SftpEntry("link", "/d/sub/link", SftpEntryType.Symlink, 0, 0, 0),
            SftpEntry("inner", "/d/sub/inner", SftpEntryType.Directory, 0, 0, 0),
        )
        client.listings["/d/sub/inner"] = listOf(
            SftpEntry("b.txt", "/d/sub/inner/b.txt", SftpEntryType.File, 2, 0, 0),
        )

        browser().delete(FileItem("sub", "/d/sub", FileItemType.Directory, 0, 0))

        // Contents are cleared before the directory itself; a nested directory before its parent;
        // a symlink is removed as a link (remove), without following its target.
        assertEquals(
            listOf(
                "list:/d/sub",
                "remove:/d/sub/a.txt",
                "remove:/d/sub/link",
                "list:/d/sub/inner",
                "remove:/d/sub/inner/b.txt",
                "rmdir:/d/sub/inner",
                "rmdir:/d/sub",
            ),
            client.calls,
        )
    }

    @Test
    fun `stat maps an entry and reports a missing path as null`() = runTest {
        client.stats["/d/a.txt"] = SftpEntry("a.txt", "/d/a.txt", SftpEntryType.File, 42, 200, 0b110_100_100)

        val item = browser().stat("/d/a.txt")

        assertEquals("a.txt", item?.name)
        assertEquals(42, item?.size)
        assertEquals(200, item?.modifiedEpochSeconds)
        assertEquals(0b110_100_100, item?.permissions)
        assertEquals(FileItemType.File, item?.type)
        assertNull(browser().stat("/d/missing"))
    }

    @Test
    fun `readFile returns the file bytes`() = runTest {
        client.contents["/d/a.txt"] = "hello".encodeToByteArray()

        assertEquals("hello", browser().readFile("/d/a.txt", maxBytes = 1024).decodeToString())
    }

    @Test
    fun `readFile refuses a file larger than the cap without reading it`() = runTest {
        client.stats["/d/big.log"] = SftpEntry("big.log", "/d/big.log", SftpEntryType.File, 5_000, 0, 0)
        client.contents["/d/big.log"] = ByteArray(5_000)

        val e = assertFailsWith<FileBrowserException> { browser().readFile("/d/big.log", maxBytes = 1024) }

        assertEquals(FileBrowserFailure.TooLarge, e.failure)
        assertFalse("read:/d/big.log" in client.calls)
    }

    @Test
    fun `readFile refuses oversized content even when the server understated the size`() = runTest {
        // A server may report any size it likes: the cap must also hold against the bytes actually read.
        client.stats["/d/liar"] = SftpEntry("liar", "/d/liar", SftpEntryType.File, 1, 0, 0)
        client.contents["/d/liar"] = ByteArray(5_000)

        val e = assertFailsWith<FileBrowserException> { browser().readFile("/d/liar", maxBytes = 1024) }

        assertEquals(FileBrowserFailure.TooLarge, e.failure)
    }

    @Test
    fun `writeFile is delegated`() = runTest {
        browser().writeFile("/d/a.txt", "new".encodeToByteArray())

        assertTrue("write:/d/a.txt" in client.calls)
        assertEquals("new", client.contents["/d/a.txt"]?.decodeToString())
    }

    @Test
    fun `sftp errors are wrapped in FileBrowserException`() = runTest {
        client.failList = true

        // The sshj text is diagnostic detail only; the user-facing reason is the typed failure.
        val e = assertFailsWith<FileBrowserException> { browser().list("/d") }
        assertEquals(FileBrowserFailure.Sftp, e.failure)
    }

    @Test
    fun `cancellation is not wrapped`() = runTest {
        client.cancelList = true

        // The guard only catches SftpException — cancellation must pass through unwrapped.
        assertFailsWith<CancellationException> { browser().list("/d") }
    }
}

/** Recording fake [SftpClient]: tracks calls in [calls], returns a configured listing. */
private class RecordingSftp : SftpClient {
    val calls = mutableListOf<String>()
    var listResult: List<SftpEntry> = emptyList()
    val listings = mutableMapOf<String, List<SftpEntry>>()
    val stats = mutableMapOf<String, SftpEntry>()
    val contents = mutableMapOf<String, ByteArray>()
    var failList = false
    var cancelList = false

    /** Listings a map cannot hold — a directory that lists itself as its own child. */
    var listAnswer: ((String) -> List<SftpEntry>?)? = null

    /** Limits the browser asked for, in call order — the contract is "at most limit + 1 entries". */
    val listLimits = mutableListOf<Int>()

    override suspend fun list(path: String, limit: Int): List<SftpEntry> {
        if (cancelList) throw CancellationException("cancelled")
        if (failList) throw SftpException("boom")
        calls += "list:$path"
        listLimits += limit
        val all = listAnswer?.invoke(path) ?: listings[path] ?: listResult
        // Long: the contract is "one past the limit", and a limit of Int.MAX_VALUE must not wrap
        // into a negative ceiling here any more than it does in the real client.
        return if (all.size.toLong() > limit.toLong() + 1) all.subList(0, limit + 1) else all
    }

    override suspend fun stat(path: String): SftpEntry? {
        calls += "stat:$path"
        return stats[path] ?: contents[path]?.let { SftpEntry(path.substringAfterLast('/'), path, SftpEntryType.File, it.size.toLong(), 0, 0) }
    }

    override suspend fun realpath(path: String): String {
        calls += "realpath:$path"
        return "/resolved$path"
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        calls += "read:$path"
        return contents[path] ?: throw SftpException("No file $path")
    }

    override suspend fun write(path: String, data: ByteArray) {
        calls += "write:$path"
        contents[path] = data
    }
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) {}
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) {}
    override suspend fun mkdir(path: String) { calls += "mkdir:$path" }
    override suspend fun remove(path: String) { calls += "remove:$path" }
    override suspend fun rmdir(path: String) { calls += "rmdir:$path" }
    override suspend fun rename(from: String, to: String) { calls += "rename:$from->$to" }
    override suspend fun close() {}
}

/**
 * A listing too big to hold in a test, generated on demand: the browser must decide on its size
 * before it maps it, so nothing here is ever materialised.
 */
private fun oversizedListing(path: String, count: Int, firstIsDirectory: Boolean = false): List<SftpEntry> =
    object : AbstractList<SftpEntry>() {
        override val size = count
        override fun get(index: Int) = SftpEntry(
            "f$index",
            "$path/f$index",
            if (index == 0 && firstIsDirectory) SftpEntryType.Directory else SftpEntryType.File,
            0,
            0,
            0b110_100_100,
        )
    }
