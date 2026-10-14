package com.huigu.phone10.mobile

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.Locale

internal suspend fun analyzeProbe(context: Context, pcm: ByteArray): String = withContext(Dispatchers.Default) {
    try {
        val start = SystemClock.elapsedRealtime()
        val modelBytes = context.assets.open("sound/yamnet.onnx").use { it.readBytes() }
        YamnetModel(modelBytes).use { model ->
            val loadMs = SystemClock.elapsedRealtime() - start
            val windows = mutableListOf<FloatArray>()
            SoundCueWindows { windows.add(it) }.accept(pcm)
            val times = mutableListOf<Long>()
            val cues = mutableListOf<String>()
            val gate = SoundCueGate()
            var peak = 0f
            for ((index, window) in windows.withIndex()) {
                ensureActive()
                val before = System.nanoTime()
                val scores = model.scores(window)
                times.add((System.nanoTime()-before)/1_000_000)
                val breath = maxOf(scores[36],scores[39],scores[40])
                peak = maxOf(peak,breath)
                gate.accept(index*480L,breath,scores[23])?.let {
                    cues += String.format(Locale.ROOT,"%.2fs %s",index*0.48+0.975,it.label)
                }
            }
            ensureActive()
            val ordered = times.sorted()
            "模型初始化 ${loadMs}ms；${times.size}窗，单窗中位 ${ordered.getOrNull(ordered.size/2)}ms，" +
                "最慢 ${ordered.lastOrNull()}ms。呼吸最高分 ${String.format(Locale.ROOT,"%.3f",peak)}（非准确率）。\n" +
                "检测：${cues.joinToString("；").ifEmpty { "未连续检出" }}"
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { "手机模型运行失败（${error.javaClass.simpleName}）；原始收音仍可试听和导出给电脑检查。" }
}
