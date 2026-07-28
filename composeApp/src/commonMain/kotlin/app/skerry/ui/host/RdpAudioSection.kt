package app.skerry.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.audio.AudioOutputs
import app.skerry.ui.app.LocalAudioOutputs
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_audio_device_default
import app.skerry.ui.generated.resources.conn_audio_output_device
import app.skerry.ui.generated.resources.conn_audio_redirect
import app.skerry.ui.generated.resources.conn_audio_redirect_desc

/**
 * Audio redirection of an RDP profile: the toggle, and — once it is on — the device to play on.
 *
 * The device picker only appears with the toggle: a list of outputs on a profile that plays no sound
 * is a control that decides nothing. The devices are read once per form, when the toggle is turned
 * on, so a headset plugged in mid-edit does not reshuffle the list under the user's finger.
 *
 * Shared by the desktop modal and the mobile sheet — the same two controls, so the second copy would
 * be the place where the two drift apart.
 */
@Composable
fun RdpAudioSection(form: NewConnectionFormState) {
    val outputs = LocalAudioOutputs.current
    val devices = remember(outputs, form.rdpAudioOutput) {
        if (form.rdpAudioOutput) outputs?.devices().orEmpty() else emptyList()
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Txt(
                    stringResource(Res.string.conn_audio_redirect),
                    color = Skerry.colors.text,
                    size = 12.5.sp,
                    weight = FontWeight.Medium,
                )
                Txt(
                    stringResource(Res.string.conn_audio_redirect_desc),
                    color = Skerry.colors.dim,
                    size = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Toggle(
                on = form.rdpAudioOutput,
                onToggle = { form.rdpAudioOutput = !form.rdpAudioOutput },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (!form.rdpAudioOutput) return@Column
        Box(Modifier.size(12.dp))
        Txt(
            stringResource(Res.string.conn_audio_output_device).uppercase(),
            color = Skerry.colors.faint,
            size = 10.5.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        val defaultLabel = stringResource(Res.string.conn_audio_device_default)
        DropdownField(
            value = form.rdpAudioDeviceId,
            // The stored device stays in the list even when it is not connected right now, or
            // opening the picker would be a one-way trip away from it.
            options = (listOf(AudioOutputs.SYSTEM_DEFAULT_ID) + devices.map { it.id } + form.rdpAudioDeviceId)
                .distinct(),
            // A stored device that is no longer connected keeps its own name in the field rather
            // than reading as the default: the profile still points at it, and it will be used again
            // once it is plugged back in.
            label = { id ->
                when {
                    id == AudioOutputs.SYSTEM_DEFAULT_ID -> defaultLabel
                    else -> devices.firstOrNull { it.id == id }?.name ?: id
                }
            },
            onPick = { form.rdpAudioDeviceId = it },
        )
    }
}
