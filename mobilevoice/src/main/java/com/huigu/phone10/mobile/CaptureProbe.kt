package com.huigu.phone10.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.google.gson.Gson

internal enum class ProbeMode(val title: String) {
    CURRENT("A · 当前气声通道"), MINIMAL("B · 少处理通道")
}

internal data class ProbeMetrics(val seconds: Double, val peak: Double, val zeroFraction: Double, val longestZeroMillis: Double)
internal data class ProbeSample(val pcm: ByteArray, val details: String, val metrics: ProbeMetrics,
    val modelReport: String)
internal data class ProbeState(val busy: Boolean = false, val message: String = "先结束通话，再分别录两组。",
    val samples: Map<ProbeMode, ProbeSample> = emptyMap())

/** At most two eight-second recordings, in app memory until explicitly exported or cleared. */
internal object CaptureProbe {
    private val mutable = MutableStateFlow(ProbeState())
    val state = mutable.asStateFlow()
    fun busy(message: String) { mutable.value = mutable.value.copy(busy = true, message = message) }
    fun finish(message: String) { mutable.value = mutable.value.copy(busy = false, message = message) }
    fun result(mode: ProbeMode, sample: ProbeSample) {
        mutable.value = mutable.value.copy(samples = mutable.value.samples + (mode to sample), message = "${mode.title}已录好")
    }
    fun clear() { if (!mutable.value.busy) mutable.value = ProbeState() }

    fun metrics(pcm: ByteArray): ProbeMetrics {
        require(pcm.size % 2 == 0)
        var peak = 0; var zeros = 0; var streak = 0; var longest = 0
        for (i in pcm.indices step 2) {
            val value = ((pcm[i+1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(value))
            if (value == 0) { zeros++; streak++; longest = maxOf(longest, streak) } else streak = 0
        }
        val frames = pcm.size / 2
        return ProbeMetrics(frames / 16000.0, peak / 32768.0,
            if (frames == 0) 0.0 else zeros.toDouble()/frames, longest/16.0)
    }

    fun wav(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0)
        val out = java.nio.ByteBuffer.allocate(44+pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()); out.putInt(36+pcm.size); out.put("WAVEfmt ".toByteArray())
        out.putInt(16); out.putShort(1); out.putShort(1); out.putInt(16000); out.putInt(32000)
        out.putShort(2); out.putShort(16); out.put("data".toByteArray()); out.putInt(pcm.size); out.put(pcm)
        return out.array()
    }

    fun export(output: OutputStream, samples: Map<ProbeMode, ProbeSample>) {
        require(samples.isNotEmpty())
        ZipOutputStream(output).use { zip ->
            for ((mode, sample) in samples) {
                zip.putNextEntry(ZipEntry("${mode.name.lowercase()}.wav")); zip.write(wav(sample.pcm)); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("report.json"))
            zip.write(Gson().toJson(samples.mapKeys { it.key.name }.mapValues { (_, v) ->
                mapOf("capture" to v.details, "metrics" to v.metrics, "model" to v.modelReport)
            }).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }
}
