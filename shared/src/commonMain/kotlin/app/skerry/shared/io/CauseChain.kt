package app.skerry.shared.io

/**
 * [this] and its causes, outermost first. Bounded: a cause chain that loops back on itself would
 * otherwise never end, and every reason worth finding sits within a few layers of the top.
 */
fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

/** The first [T] in [causeChain]. */
inline fun <reified T : Throwable> Throwable.findCause(): T? = causeChain().firstNotNullOfOrNull { it as? T }

private const val MAX_CAUSE_DEPTH = 8
