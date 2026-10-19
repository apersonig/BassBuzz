package com.example.bassbuzz

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

class DecodeException(message: String) : Exception(message)

/**
 * Decodes the first audio track of a file (MP3, AAC, FLAC, ... anything MediaCodec supports)
 * to PCM and hands it over as mono floats in the range -1..1.
 */
object AudioDecoder {

    fun decode(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean,
        onFormat: (sampleRate: Int) -> Unit,
        onSamples: (samples: FloatArray, length: Int) -> Unit,
        onProgress: (fraction: Float) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                throw DecodeException("No audio track found in this file")
            }
            extractor.selectTrack(trackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs =
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(inputFormat, null, null, 0)
            c.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val info = MediaCodec.BufferInfo()
            var announced = false
            var inputDone = false
            var outputDone = false
            var mono = FloatArray(8192)

            while (!outputDone) {
                if (isCancelled()) return

                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding =
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                    }

                    outIdx >= 0 -> {
                        if (info.size > 0 && channels > 0) {
                            if (!announced) {
                                onFormat(sampleRate)
                                announced = true
                            }
                            val out = c.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())

                            val frames: Int
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = out.asFloatBuffer()
                                frames = fb.remaining() / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var acc = 0f
                                    for (ch in 0 until channels) acc += fb.get()
                                    mono[f] = acc / channels
                                }
                            } else {
                                val sb = out.asShortBuffer()
                                frames = sb.remaining() / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var acc = 0
                                    for (ch in 0 until channels) acc += sb.get().toInt()
                                    mono[f] = acc / (channels * 32768f)
                                }
                            }
                            onSamples(mono, frames)

                            if (durationUs > 0) {
                                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }
}
