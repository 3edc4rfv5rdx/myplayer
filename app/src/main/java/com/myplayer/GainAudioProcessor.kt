package com.myplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Applies a per-track linear gain to 16-bit PCM audio, with hard-clip protection.
 *  gain = 1.0 is a transparent passthrough (used when ReplayGain is off or no tag is present). */
class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var gain: Float = 1f

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val g = gain
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)
        if (g == 1f) {
            output.put(inputBuffer)
        } else {
            val input = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val out = output.order(ByteOrder.nativeOrder()).asShortBuffer()
            while (input.hasRemaining()) {
                val scaled = (input.get() * g).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.put(scaled.toShort())
            }
            inputBuffer.position(inputBuffer.limit())
        }
        output.flip()
    }
}
