package app.skerry.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a playback device is named in the picker. ALSA is the awkward one: the mixer's name is the
 * card's short id and the product name lives in the description, so a list built from names alone
 * reads as a column of abbreviations.
 */
class JavaSoundOutputsTest {

    @Test
    fun `an ALSA device is named after the product, keeping its port`() {
        assertEquals(
            "Scarlett 2i2 USB [plughw:3,0]",
            JavaSoundOutputs.mixerLabel("UA4 [plughw:3,0]", "Direct Audio Device: Scarlett 2i2 USB, USB Audio"),
        )
    }

    @Test
    fun `a device whose description says nothing keeps the name it had`() {
        assertEquals("UA4 [plughw:3,0]", JavaSoundOutputs.mixerLabel("UA4 [plughw:3,0]", "Direct Audio Device: "))
    }

    @Test
    fun `a device that is not an ALSA port is left alone`() {
        assertEquals("Built-in Output", JavaSoundOutputs.mixerLabel("  Built-in Output  ", "Core Audio"))
    }
}
