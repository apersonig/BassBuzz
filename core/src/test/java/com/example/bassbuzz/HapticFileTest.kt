package com.example.bassbuzz

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HapticFileTest {

    private val levels = FloatArray(500) { (it % 50) / 40f } // ramps 0..1.225, repeating
    private val audio = ByteArray(100_000) { (it * 31 + 7).toByte() }

    private fun encode(title: String = "Test Song", ext: String = "mp3"): ByteArray {
        val out = ByteArrayOutputStream()
        HapticFile.write(out, title, ext, 120, levels, audio)
        return out.toByteArray()
    }

    private fun decodeError(bytes: ByteArray): String {
        try {
            HapticFile.read(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        } catch (e: HapticFileException) {
            return e.message ?: ""
        }
        fail("expected HapticFileException")
        return ""
    }

    @Test
    fun roundTripKeepsEverything() {
        val sink = ByteArrayOutputStream()
        val c = HapticFile.read(ByteArrayInputStream(encode()), sink)

        assertEquals("Test Song", c.title)
        assertEquals("mp3", c.audioExt)
        assertEquals(HOP_MS, c.hopMs)
        assertEquals(120, c.cutoffHz)
        assertEquals(audio.size.toLong(), c.audioLength)
        assertArrayEquals(audio, sink.toByteArray()) // audio comes back byte for byte
        assertEquals(levels.size, c.levels.size)
        for (i in levels.indices) {
            assertEquals(levels[i], c.levels[i], 0.004f) // quantisation error stays tiny
        }
    }

    @Test
    fun readingWithoutSinkSkipsAudio() {
        val c = HapticFile.read(ByteArrayInputStream(encode()), null)
        assertEquals(levels.size, c.levels.size)
    }

    @Test
    fun unicodeTitleSurvives() {
        val c = HapticFile.read(ByteArrayInputStream(encode(title = "Låt – 低音 🎵")), null)
        assertEquals("Låt – 低音 🎵", c.title)
    }

    @Test
    fun oversizedLevelsAreClipped() {
        val out = ByteArrayOutputStream()
        HapticFile.write(out, "x", "mp3", 100, floatArrayOf(-1f, 0f, 1.5f, 9f), ByteArray(0))
        val c = HapticFile.read(ByteArrayInputStream(out.toByteArray()), null)
        assertEquals(0f, c.levels[0], 0f)
        assertEquals(0f, c.levels[1], 0f)
        assertEquals(1.5f, c.levels[2], 0.001f)
        assertEquals(1.5f, c.levels[3], 0.001f)
    }

    @Test
    fun badMagicIsRejected() {
        val bytes = encode()
        bytes[0] = 'X'.code.toByte()
        assertTrue(decodeError(bytes).contains("isn't a BassBuzz"))
    }

    @Test
    fun flippedHapticByteFailsChecksum() {
        val bytes = encode()
        // Somewhere inside the level data, which sits right before the 4-byte CRC and the audio.
        val i = bytes.size - audio.size - 4 - 10
        bytes[i] = (bytes[i].toInt() xor 0x55).toByte()
        assertTrue(decodeError(bytes).contains("checksum"))
    }

    @Test
    fun truncatedAudioIsDetected() {
        val bytes = encode()
        assertTrue(decodeError(bytes.copyOf(bytes.size - 1000)).contains("cut short"))
    }

    @Test
    fun truncatedHeaderIsDetected() {
        assertTrue(decodeError(encode().copyOf(10)).contains("too short"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertTrue(decodeError(ByteArray(0)).contains("too short"))
    }

    @Test
    fun odd_extensionsAreCleaned() {
        val c = HapticFile.read(ByteArrayInputStream(encode(ext = "MP3/../x")), null)
        assertEquals("mp3x", c.audioExt)
    }
}
