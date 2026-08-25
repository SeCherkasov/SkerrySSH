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
