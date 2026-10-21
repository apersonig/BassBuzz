package com.example.bassbuzz

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import kotlin.math.roundToInt

class HapticFileException(message: String) : Exception(message)

/** Everything in a .bbz file except the audio bytes themselves. */
class HapticFileContent(
    val title: String,
    /** File extension of the embedded audio, e.g. "mp3". */
    val audioExt: String,
    val hopMs: Int,
    /** Low-pass cutoff that was used to isolate the bass, for information only. */
    val cutoffHz: Int,
    /** Normalised bass level per [hopMs] frame (see [HapticTrack]). */
    val levels: FloatArray,
    val audioLength: Long,
)

/**
 * The ".bbz" BassBuzz haptic file: the original audio file, unchanged, plus the bass envelope
 * that drives the motor. All numbers are big-endian. See docs/FORMAT.md.
 *
 *   magic        4 bytes   "BBHZ"
 *   version      u16       1
 *   hopMs        u16       milliseconds per haptic frame (20)
 *   cutoffHz     u16       low-pass cutoff used for the analysis
 *   reserved     u16       0
 *   frameCount   u32       number of haptic frames
 *   audioLength  u32       number of audio bytes at the end of the file
 *   titleLen     u16       followed by that many UTF-8 bytes
 *   extLen       u8        followed by that many ASCII bytes (audio file extension)
 *   levels       frameCount bytes, each u8 = round(level / 1.5 * 255)
 *   crc32        u32       CRC-32 of every byte above
 *   audio        audioLength bytes
 */
object HapticFile {
    const val MAGIC = "BBHZ"
    const val VERSION = 1
    const val EXTENSION = "bbz"

    /** Levels above this are clipped when stored. */
    const val LEVEL_SCALE = 1.5f

    private const val MAX_FRAMES = 10_000_000
    private const val MAX_TITLE_CHARS = 200

    fun quantize(level: Float): Int =
        ((level.coerceIn(0f, LEVEL_SCALE) / LEVEL_SCALE) * 255f).roundToInt().coerceIn(0, 255)

    fun dequantize(q: Int): Float = q * LEVEL_SCALE / 255f

    fun write(
        out: OutputStream,
        title: String,
        audioExt: String,
        cutoffHz: Int,
        levels: FloatArray,
        audio: ByteArray,
    ) {
        if (levels.size > MAX_FRAMES) throw HapticFileException("Song is too long")

        val block = ByteArrayOutputStream(levels.size + 256)
        val d = DataOutputStream(block)
        d.writeBytes(MAGIC)
        d.writeShort(VERSION)
        d.writeShort(HOP_MS)
        d.writeShort(cutoffHz.coerceIn(0, 0xFFFF))
        d.writeShort(0)
        d.writeInt(levels.size)
        d.writeInt(audio.size)

        val titleBytes = title.take(MAX_TITLE_CHARS).toByteArray(Charsets.UTF_8)
        d.writeShort(titleBytes.size)
        d.write(titleBytes)

        val ext = audioExt.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8)
        d.writeByte(ext.length)
        d.writeBytes(ext)

        val q = ByteArray(levels.size) { quantize(levels[it]).toByte() }
        d.write(q)
        d.flush()

        val bytes = block.toByteArray()
        val crc = CRC32().apply { update(bytes) }

        out.write(bytes)
        DataOutputStream(out).apply {
            writeInt(crc.value.toInt())
            flush()
        }
        out.write(audio)
        out.flush()
    }

    /**
     * Reads a .bbz file. If [audioOut] is given, the embedded audio is copied into it; otherwise the
     * audio is skipped. Throws [HapticFileException] with a readable message for anything wrong.
     */
    fun read(input: InputStream, audioOut: OutputStream?): HapticFileContent {
        try {
            val raw = DataInputStream(BufferedInputStream(input, 64 * 1024))
            val crc = CRC32()
            val d = DataInputStream(CheckedInputStream(raw, crc))

            val magic = ByteArray(4)
            d.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != MAGIC) {
                throw HapticFileException("This isn't a BassBuzz haptic file")
            }
            val version = d.readUnsignedShort()
            if (version > VERSION) throw HapticFileException("This file was made by a newer version of BassBuzz")
            val hop = d.readUnsignedShort()
            if (hop != HOP_MS) throw HapticFileException("Unsupported haptic frame length ($hop ms)")
            val cutoff = d.readUnsignedShort()
            d.readUnsignedShort() // reserved

            val frames = d.readInt()
            if (frames < 0 || frames > MAX_FRAMES) throw HapticFileException("File is damaged (bad length)")
            val audioLength = d.readInt().toLong() and 0xFFFFFFFFL

            val titleBytes = ByteArray(d.readUnsignedShort())
            d.readFully(titleBytes)
            val extBytes = ByteArray(d.readUnsignedByte())
            d.readFully(extBytes)

            val q = ByteArray(frames)
            d.readFully(q)

            val stored = raw.readInt()
            if (stored != crc.value.toInt()) throw HapticFileException("File is damaged (checksum mismatch)")

            var left = audioLength
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = raw.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw HapticFileException("File is damaged (audio is cut short)")
                audioOut?.write(buf, 0, n)
                left -= n
            }
            audioOut?.flush()

            return HapticFileContent(
                title = String(titleBytes, Charsets.UTF_8),
                audioExt = String(extBytes, Charsets.ISO_8859_1),
                hopMs = hop,
                cutoffHz = cutoff,
                levels = FloatArray(frames) { dequantize(q[it].toInt() and 0xFF) },
                audioLength = audioLength,
            )
        } catch (e: EOFException) {
            throw HapticFileException("File is damaged (too short)")
        }
    }
}
