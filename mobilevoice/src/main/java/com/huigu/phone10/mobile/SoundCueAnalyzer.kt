package com.huigu.phone10.mobile

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** One bounded consumer. Neither inference nor model initialization blocks the audio reader. */
internal class SoundCueAnalyzer(
    context: Context, scope: CoroutineScope,
    private val accepts: () -> Boolean,
    private val onCue: (SoundCue) -> Unit,
    private val onStatus: (Boolean) -> Unit,
) {
    private data class Window(val samples: FloatArray, val generation: Int, val at: Long)
    private val generation = AtomicInteger()
    private val queue = Channel<Window>(1, BufferOverflow.DROP_OLDEST)
    private var permitted = false
    @Volatile private var enabled = true
    private val windows = SoundCueWindows { samples ->
        queue.trySend(Window(samples, generation.get(), SystemClock.elapsedRealtime()))
    }
    private val worker = scope.launch(Dispatchers.Default) {
        try {
            val bytes = context.assets.open("sound/yamnet.onnx").use { it.readBytes() }
            YamnetModel(bytes).use { model ->
                ensureActive()
                onStatus(true)
                val gate = SoundCueGate()
                var epoch = -1
                for (window in queue) {
                    ensureActive()
                    if (window.generation != generation.get() || !accepts()) continue
                    if (epoch != window.generation) { gate.reset(); epoch = window.generation }
                    val scores = model.scores(window.samples)
                    ensureActive()
                    if (window.generation != generation.get() || !accepts()) continue
                    // Indices from the bundled AudioSet class map; scores are not calibrated probabilities.
                    val cue = gate.accept(window.at, maxOf(scores[36], scores[39], scores[40]), scores[23])
                    if (cue != null) withContext(Dispatchers.Main.immediate) {
                        if (window.generation == generation.get() && accepts()) onCue(cue)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            withContext(Dispatchers.Main.immediate) { onStatus(false) }
        } finally { enabled = false; queue.close() }
    }

    // Called only by the recorder's single frame reader.
    fun accept(frame: ByteArray) {
        val allowed = enabled && accepts()
        if (!allowed) {
            if (permitted) { generation.incrementAndGet(); windows.reset() }
            permitted = false
            return
        }
        permitted = true
        windows.accept(frame)
    }

    suspend fun close() {
        enabled = false
        generation.incrementAndGet()
        queue.cancel()
        worker.cancelAndJoin()
    }
}
