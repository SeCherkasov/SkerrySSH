package app.skerry.shared.team

/**
 * What the record says against a fingerprint that is on screen waiting to be confirmed. [NOTHING] is
 * the quiet case — the record agrees, or there is none — and the other three each cost a second,
 * deliberate gesture before the ceremony may proceed.
 *
 * Beside the record rather than in the screens that word it: it is the question the user is asked,
 * and [TeamPeerStore.confirm] refuses a write whose question has changed since. A copy of this rule
 * on the UI side is a copy the store could not hold the write to.
 */
enum class PinNotice {
    /** The record agrees with the fingerprint, or nothing was ever recorded to disagree with it. */
    NOTHING,

    /** It differs from one a human confirmed: an honest rotation, or the server trying its luck. */
    MOVED_FROM_CONFIRMED,

    /** It differs from a first sight — nobody has confirmed either key, and neither is worth more. */
    MOVED_FROM_FIRST_SIGHT,

    /** A record exists and this device cannot read it, so there is nothing to compare against. */
    UNREADABLE,
}

/** How [pinned] measures against a [fingerprint] about to be confirmed. */
fun pinNotice(pinned: Pin, fingerprint: String): PinNotice = when {
    pinned is Pin.Known && pinned.fingerprint == fingerprint -> PinNotice.NOTHING
    pinned is Pin.Known && pinned.origin == PinOrigin.CONFIRMED -> PinNotice.MOVED_FROM_CONFIRMED
    pinned is Pin.Known -> PinNotice.MOVED_FROM_FIRST_SIGHT
    pinned == Pin.Unreadable -> PinNotice.UNREADABLE
    else -> PinNotice.NOTHING
}
