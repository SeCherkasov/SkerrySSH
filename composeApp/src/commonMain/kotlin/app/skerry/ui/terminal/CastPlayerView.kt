package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.Asciicast
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_player_empty
import app.skerry.ui.generated.resources.term_player_speed
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.generated.resources.term_player_truncated
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Plays a recording over the current screen (mobile, and the desktop mock without live sessions):
 * the same terminal renderer a live session uses, plus a transport bar.
 *
 * The playback is owned by this composable and stopped on dispose — closing the overlay must stop
 * the replay coroutine, not leave it feeding a screen nobody looks at. On the desktop a recording
 * opens in its own tab instead, where the session owns it ([CastPlayerView]).
 */
@Composable
fun CastPlayerOverlay(cast: Asciicast, onDismiss: () -> Unit) {
    val playback = remember(cast) { CastPlayback(cast) }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    LaunchedEffect(playback) { playback.start() }

    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .consumeClicks()
                .fillMaxSize()
                .padding(24.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Skerry.colors.surface)
                .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(10.dp)),
        ) {
            CastPlayerContent(playback, onClose = onDismiss)
        }
    }
}

/**
 * A recording as a work-area view: the player tab's content ([app.skerry.ui.session.SessionView.Player]).
 * Playback lives on the tab ([app.skerry.ui.session.Session.playback]), so switching tabs pauses
 * nothing and coming back continues where the recording stood; closing the tab stops it.
 */
@Composable
fun CastPlayerView() {
    val playback = LocalSessions.current?.active?.playback ?: return
    // Start on first display only ([CastPlayback.start]) — returning to the tab must not rewind.
    LaunchedEffect(playback) { playback.start() }
    Column(Modifier.fillMaxSize().background(Skerry.colors.surface)) {
        CastPlayerContent(playback, onClose = null)
    }
}

/**
 * Title strip, the replayed terminal and the transport bar. [onClose] draws a close button (the
 * overlay's only way out); in a tab the chip's own cross closes it, so it is `null` there.
 *
 * Playback is driven by a [CastPlayer] posing as the session, so nothing here knows it isn't live.
 */
@Composable
private fun ColumnScope.CastPlayerContent(playback: CastPlayback, onClose: (() -> Unit)?) {
    val cast = playback.cast
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(cast.title ?: stringResource(Res.string.term_player_title), color = Skerry.colors.textBright, size = 13.sp, weight = FontWeight.SemiBold)
            if (cast.truncated) Txt(stringResource(Res.string.term_player_truncated), color = Skerry.colors.amber, size = 11.sp)
        }
        if (onClose != null) IconBtn("close", onClick = onClose)
    }
    Box(Modifier.weight(1f).fillMaxWidth().background(Skerry.colors.terminalBg)) {
        if (cast.events.isEmpty()) {
            Txt(
                stringResource(Res.string.term_player_empty),
                color = Skerry.colors.faint,
                size = 12.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // Pinned to the geometry the recording was taken at, with the font scaled to fill the
            // pane: re-flowing a recording would leave empty columns in a wide window and wrap its
            // lines in a narrow one.
            TerminalScreen(
                playback.terminal,
                Modifier.fillMaxSize(),
                fixedGrid = PtySize(cols = cast.columns, rows = cast.rows),
            )
        }
    }
    TransportBar(playback.player)
}

/** Play/pause, replay, seekable progress and speed — everything the player is driven by. */
@Composable
private fun TransportBar(player: CastPlayer) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBtn(
            if (player.playing) "pause" else "play_arrow",
            onClick = player::toggle,
            tint = Skerry.colors.cyanBright,
        )
        IconBtn("replay", onClick = player::restart)
        Txt(formatCastTime(player.position), color = Skerry.colors.dim, size = 11.5.sp)
        SeekBar(player, Modifier.weight(1f))
        Txt(formatCastTime(player.duration), color = Skerry.colors.dim, size = 11.5.sp)
        CAST_SPEEDS.forEach { speed ->
            val label = stringResource(Res.string.term_player_speed, speedLabel(speed))
            Txt(
                label,
                color = if (player.speed == speed) Skerry.colors.cyanBright else Skerry.colors.dim,
                size = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (player.speed == speed) Skerry.colors.surface2 else Color.Transparent)
                    .pointerInput(speed) { detectTapGestures { player.changeSpeed(speed) } }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Progress line; a tap anywhere on it seeks there (playback replays up to that point). */
@Composable
private fun SeekBar(player: CastPlayer, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(20.dp)
            .pointerInput(player) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    player.seekTo(player.duration * fraction)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Skerry.colors.line))
        Box(
            Modifier
                .fillMaxWidth(player.progress)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Skerry.colors.cyanBright),
        )
    }
}

/** `0.5` / `1` / `2` — no trailing zero on whole speeds. */
private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
