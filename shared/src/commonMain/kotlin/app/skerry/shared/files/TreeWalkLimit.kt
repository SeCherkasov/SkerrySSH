package app.skerry.shared.files

/**
 * How deep a client-side walk over a remote tree descends before it refuses to go further.
 *
 * A remote tree is the server's answer, not a fact. A bind mount that contains itself, a FUSE mount,
 * or a server that simply lists a directory as its own child gives a tree with no bottom, and a
 * recursion over it runs until the stack or the heap gives out. 64 levels is far below any tree a
 * person keeps and far above any tree they walk by hand.
 */
const val MAX_TREE_DEPTH = 64

/**
 * How many entries one walk that builds a plan may take on.
 *
 * Depth alone does not bound a walk: a directory that lists a thousand directories, each listing a
 * thousand more, is three levels deep and a billion entries wide. The number is what a plan held
 * whole in memory costs before a byte moves — around 350 bytes an entry, so 100 000 is tens of
 * megabytes, which an Android heap can hold and ten times that cannot. It is deliberately a bound on
 * the client's own memory, not an opinion about how much a person may transfer: a tree bigger than
 * this is refused with a reason on the queue rather than taken on and dropped halfway.
 */
const val MAX_TREE_ENTRIES = 100_000

/**
 * Refuses a descent past [MAX_TREE_DEPTH]: below that a tree is a loop, not a tree.
 *
 * Every client-side recursion over a server's listing calls this, because a listing that has no
 * bottom is what they all have in common. It refuses before the level it is asked about is entered,
 * so a walk that has done nothing yet has still done nothing.
 */
fun refuseTooDeep(depth: Int) {
    if (depth > MAX_TREE_DEPTH) {
        throw FileBrowserException(FileBrowserFailure.TreeTooLarge, detail = "deeper than $MAX_TREE_DEPTH levels")
    }
}

/**
 * The budget one walk that *builds a plan* runs under: [count] once per entry it takes on, [descend]
 * before it enters a directory. One instance per walk, shared across every top-level item of it, so
 * a server that fans out cannot buy itself a fresh budget per selection.
 *
 * The entry half belongs to the transfer walks alone. They decide the whole plan before a byte
 * moves, so refusing at entry 100 001 costs nothing but the walk. A recursion that mutates as it
 * goes — the recursive delete — must not carry it: it would stop with most of the tree already
 * removed and report the tree as refused. Those recursions call [refuseTooDeep] and nothing else.
 */
class TreeWalkLimit {
    private var entries = 0

    /** Counts an entry the walk is about to take on, and refuses the one past [MAX_TREE_ENTRIES]. */
    fun count() {
        entries++
        if (entries > MAX_TREE_ENTRIES) {
            throw FileBrowserException(FileBrowserFailure.TreeTooLarge, detail = "more than $MAX_TREE_ENTRIES entries")
        }
    }

    /** @see refuseTooDeep */
    fun descend(depth: Int) = refuseTooDeep(depth)
}

/**
 * How many entries one directory listing may hold.
 *
 * [MAX_TREE_ENTRIES] bounds a walk once its listings exist; this bounds a single one of them as it
 * is read. Nothing else does: a listing is the server's answer, so how much is allocated to hold it
 * is the server's decision, and one `SSH_FXP_READDIR` loop is enough to exhaust the heap during an
 * ordinary browse — before any guard above it runs.
 *
 * The number is the peak, not the row. One entry costs several times the
 * [app.skerry.shared.sftp.SftpEntry] it ends up as: sshj holds a list node, a `RemoteResourceInfo`,
 * a `PathComponents` and a `FileAttributes` (with a map of its own) for the whole listing before it
 * returns any of it, the browser then builds a `FileItem` beside every entry and de-duplicates
 * twice, and the pane keeps a sorted copy alongside the filtered one. Around 600 bytes an entry
 * alive at once, so this is ~30 MB in flight and ~8 MB per pane afterwards.
 *
 * The floor it is weighed against is Android's, not the desktop's: the app asks for no large heap,
 * so what it gets is `dalvik.vm.heapgrowthlimit` — 128 MB on a mid-range device, less on an old one.
 * Desktop therefore pays a lower ceiling than it needs, which is the cheaper mistake: a refusal is a
 * line of text, and the alternative is an `OutOfMemoryError`, which is not a failure anyone can read
 * or retry. A directory of 50 000 rows is already past what a pane can usefully draw; past that the
 * user is told, not crashed.
 *
 * It bounds a walk as well as a browse, so it is also what a directory download may take on in one
 * directory — deliberately, for the same reason.
 *
 * It is the budget for a whole root-to-leaf path, not for one call. A recursive walk holds one
 * listing per level it is currently inside, so each level passes what is left of it down as
 * [refuseOversizedListing]'s `cap`; sixty-four levels each holding a full one is not a bound
 * at all. Breadth does not consume it: siblings are walked one after another and a sibling's
 * listings are gone before the next one starts.
 *
 * A local listing has no cap of its own. okio's `FileSystem.list` has no streaming form, so the
 * whole directory is materialised by the call itself and a limit applied to what it returns would
 * refuse after the allocation it was meant to prevent. What bounds it there is the local filesystem.
 */
const val MAX_LISTING_ENTRIES = 50_000

/**
 * Refuses a directory listing longer than [cap] — [MAX_LISTING_ENTRIES] for a walk that is not
 * holding anything else, less for one already holding the listings of the levels above it.
 *
 * Called with the size the source answered with, before that answer is mapped or de-duplicated, so
 * the refusal costs one comparison rather than a second copy of the listing. It refuses rather than
 * truncates on purpose: a listing drawn short without saying so is a directory the user believes
 * they have seen.
 */
fun refuseOversizedListing(path: String, entries: Int, cap: Int = MAX_LISTING_ENTRIES) {
    if (entries > cap) {
        throw FileBrowserException(
            FileBrowserFailure.TreeTooLarge,
            detail = "listing of $path is longer than the $cap entries there is room for",
        )
    }
}
