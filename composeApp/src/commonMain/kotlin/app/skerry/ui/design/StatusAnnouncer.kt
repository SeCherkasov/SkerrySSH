package app.skerry.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A node that exists only to be heard: it draws nothing and cannot be focused, and it carries [message] as
 * its own semantics under a polite live region.
 *
 * State that changes on its own — a sync cycle finishing, a connection dropping — is visible to a sighted
 * user and silent to everyone else (WCAG 4.1.3). A live region is what fixes that, but only on a node that
 * (a) survives the change and (b) carries the changing text itself: Compose sources the accessibility
 * event from the node whose own semantics changed, so a region wrapped around a container whose children
 * hold the text announces nothing, and one that appears together with its message is an insertion rather
 * than a change. Hence: keep this composed across the states it describes, above whatever `when` picks the
 * card, and pass the empty string for the states worth no announcement.
 *
 * It takes [ANNOUNCER_SIZE] rather than nothing, and that is load-bearing: Android builds the
 * accessibility tree from the semantics nodes whose bounds intersect the space not yet accounted for, so a
 * 0dp node is pruned before a live-region event is ever emitted for it. The Compose test tree keeps every
 * node regardless of bounds — which is why only a bounds assertion catches this, not a semantics one.
 *
 * What goes in [message] is the STATE, not its telemetry: a string that is equal to the previous one is
 * not a change and stays silent, which is what keeps a background cycle from re-announcing the card every
 * few seconds.
 */
@Composable
fun StatusAnnouncer(message: String) {
    Box(
        Modifier.size(ANNOUNCER_SIZE).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = message
        },
    )
}

/** The smallest non-empty box: enough to survive the accessibility tree, too little to see. */
val ANNOUNCER_SIZE = 1.dp
