package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.skerry.shared.terminal.Asciicast
import app.skerry.ui.design.D
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_player_empty
import app.skerry.ui.generated.resources.term_player_speed
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.generated.resources.term_player_truncated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Plays a recording over the current screen: the same terminal renderer a live session uses, plus
 * a transport bar (play/pause, replay, seekable progress, speed).
 *
 * Playback is driven by a [CastPlayer] posing as the session, so nothing here knows it isn't live.
 * The player's scope is owned by this composable and cancelled on dispose — closing the overlay
 * must stop the replay coroutine, not leave it feeding a screen nobody looks at.
 */
@Composable
fun CastPlayerOverlay(cast: Asciicast, onDismiss: () -> Unit) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val player = remember(cast) { CastPlayer(cast, scope) }
    val terminal = remember(player) { TerminalScreenState(player, scope) }
    DisposableEffect(player) { onDispose { scope.cancel() } }
    // Start playing as soon as the recording is on screen — opening it is the request to watch it.
    LaunchedEffect(player) { player.play() }

    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .consumeClicks()
                .fillMaxSize()
                .padding(24.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(D.surface)
                .border(1.dp, D.lineStrong, RoundedCornerShape(10.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(cast.title ?: stringResource(Res.string.term_player_title), color = D.textBright, size = 13.sp, weight = FontWeight.SemiBold)
                    if (cast.truncated) Txt(stringResource(Res.string.term_player_truncated), color = D.amber, size = 11.sp)
                }
                IconBtn("close", onClick = onDismiss)
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(D.terminalBg)) {
                if (cast.events.isEmpty()) {
                    Txt(
                        stringResource(Res.string.term_player_empty),
                        color = D.faint,
                        size = 12.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    TerminalScreen(terminal, Modifier.fillMaxSize())
                }
            }
            TransportBar(player)
        }
    }
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
            tint = D.cyanBright,
        )
        IconBtn("replay", onClick = player::restart)
        Txt(formatCastTime(player.position), color = D.dim, size = 11.5.sp)
        SeekBar(player, Modifier.weight(1f))
        Txt(formatCastTime(player.duration), color = D.dim, size = 11.5.sp)
        CAST_SPEEDS.forEach { speed ->
            val label = stringResource(Res.string.term_player_speed, speedLabel(speed))
            Txt(
                label,
                color = if (player.speed == speed) D.cyanBright else D.dim,
                size = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (player.speed == speed) D.surface2 else Color.Transparent)
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
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(D.line))
        Box(
            Modifier
                .fillMaxWidth(player.progress)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(D.cyanBright),
        )
    }
}

/** `0.5` / `1` / `2` — no trailing zero on whole speeds. */
private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
