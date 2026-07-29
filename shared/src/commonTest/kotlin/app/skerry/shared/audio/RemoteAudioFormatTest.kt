package app.skerry.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteAudioFormatTest {

    /**
     * A fifth of a second: enough that a scheduling hiccup does not become an audible gap, short
     * enough that the sound stays with the picture.
     */
    @Test
    fun `a device buffer holds a fifth of a second`() {
        assertEquals(35280, RemoteAudioFormat(sampleRate = 44100, channels = 2, bitsPerSample = 16).bufferBytes())
        assertEquals(1600, RemoteAudioFormat(sampleRate = 8000, channels = 1, bitsPerSample = 8).bufferBytes())
    }
}
