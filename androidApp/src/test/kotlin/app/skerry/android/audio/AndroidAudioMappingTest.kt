package app.skerry.android.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import app.skerry.shared.audio.AndroidAudioMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Android side of RDP audio, as far as a host JVM can reach it: an `AudioTrack` needs a real
 * device, so what is pinned here is the mapping around it — the depths a track can be opened for,
 * how channels are routed, and the id a profile stores for an output.
 */
class AndroidAudioMappingTest {

    @Test
    fun `PCM is opened at the two depths a track has an encoding for`() {
        assertEquals(AudioFormat.ENCODING_PCM_8BIT, AndroidAudioMapping.encoding(8))
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, AndroidAudioMapping.encoding(16))
    }

    /** Nothing else is ever negotiated (see AudioChannel), and an unknown depth must not be guessed. */
    @Test
    fun `a depth the platform cannot play opens no track`() {
        assertNull(AndroidAudioMapping.encoding(24))
        assertNull(AndroidAudioMapping.encoding(32))
        assertNull(AndroidAudioMapping.encoding(0))
    }

    @Test
    fun `one channel is mono and two are stereo`() {
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, AndroidAudioMapping.channelMask(1))
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, AndroidAudioMapping.channelMask(2))
    }

    @Test
    fun `anything wider than stereo is still played as stereo`() {
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, AndroidAudioMapping.channelMask(6))
    }

    /**
     * The id survives a reconnect — an [AudioDeviceInfo.id] does not, and a profile that stored one
     * would silently fall back to the speaker the next time the headset is plugged in.
     */
    @Test
    fun `an output is identified by its type and product`() {
        assertEquals(
            "${AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}:WH-1000XM4",
            AndroidAudioMapping.outputId(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM4"),
        )
    }

    @Test
    fun `an output with no product name still has an id`() {
        assertEquals(
            "${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}:",
            AndroidAudioMapping.outputId(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null),
        )
    }

    @Test
    fun `a sink is named after its kind and its product`() {
        assertEquals(
            "USB — Scarlett 2i2",
            AndroidAudioMapping.outputLabel(AudioDeviceInfo.TYPE_USB_HEADSET, "  Scarlett 2i2  "),
        )
    }

    @Test
    fun `a sink whose product says nothing new is named once`() {
        assertEquals("Speaker", AndroidAudioMapping.outputLabel(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"))
        assertEquals("Speaker", AndroidAudioMapping.outputLabel(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null))
    }

    @Test
    fun `a sink of a kind we have no name for is still offered`() {
        assertEquals(
            "Audio output — Dock",
            AndroidAudioMapping.outputLabel(AudioDeviceInfo.TYPE_DOCK, "Dock"),
        )
    }
}
