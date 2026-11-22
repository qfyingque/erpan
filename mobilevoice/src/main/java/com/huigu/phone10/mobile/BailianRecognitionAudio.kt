package com.huigu.phone10.mobile

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Only the 8k model gets a local, anti-aliased conversion; capture always stays PCM16/16kHz. */
internal object BailianRecognitionAudio {
    data class Input(val sampleRate: Int, val pcm: ByteArray)
    private val lowPass = DoubleArray(31) { index ->
        val n = index - 15
        val cutoff = 0.225 // 3.6kHz at 16kHz, below the new 4kHz Nyquist limit.
        val sinc = if (n == 0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
        sinc * (0.54 - 0.46 * cos(2 * PI * index / 30))
    }.let { values -> val sum = values.sum(); DoubleArray(values.size) { values[it] / sum } }

    fun prepare(model: String, pcm: ByteArray): Input {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0)
        if (model != "paraformer-realtime-8k-v2") return Input(16000, pcm)
        val count = pcm.size / 2
        fun sample(index: Int): Int {
            val offset = index.coerceIn(0, count - 1) * 2
            return ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
        }
        val output = ByteArray(((count + 1) / 2) * 2)
        for (index in output.indices step 2) {
            val center = index // Two input frames for every output frame.
            var value = 0.0
            for (tap in lowPass.indices) value += lowPass[tap] * sample(center + tap - 15)
            val aligned = value.roundToInt().coerceIn(-32768, 32767)
            output[index] = aligned.toByte(); output[index + 1] = (aligned shr 8).toByte()
        }
        return Input(8000, output)
    }
}
