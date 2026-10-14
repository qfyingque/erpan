package com.huigu.phone10.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

internal interface PcmSink {
    fun configure(sampleRate: Int) { require(sampleRate == 24_000) }
    fun write(data: ByteArray, offset: Int, length: Int): Int
    fun playedFrames(): Long
    fun underruns(): Int = 0
    fun endPaddingBytes(): Int = 0
    fun setPaused(paused: Boolean) = Unit
    fun close()
}

class PcmPlayer internal constructor(private val sink: PcmSink, private val beforeWrite: () -> Unit = {}) {
    constructor(mediaPlayback: Boolean = false, beforeWrite: () -> Unit = {}): this(AudioTrackSink(mediaPlayback), beforeWrite)
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val userPaused = AtomicBoolean(false)
    @Volatile private var pauseStarted = 0L
    private val writerLock = Any()
    private var carry: Byte? = null
    @Volatile private var submittedBytes = 0L
    @Volatile private var drainTarget: Long? = null
    internal var captureObserver: PcmCaptureObserver? = null
    @Volatile private var finalFrames: Long? = null
    private fun capture(action: (PcmCaptureObserver) -> Unit) {
        try { captureObserver?.let(action) } catch (_: Exception) { captureObserver = null }
    }
    internal fun archiveFrames(): Long = finalFrames ?: minOf(sink.playedFrames(), drainTarget ?: (submittedBytes / 2))
    private val captionLock = Any()
    private val captionMarks = ArrayDeque<Pair<Long, Int>>()
    private var captionIndex = 0
    // Called by the ordered consumer, never by speculative synthesis.
    fun markCaptionSegment(text: String) = synchronized(writerLock) {
        checkOpen()
        check(carry == null && submittedBytes % 2 == 0L) { "PCM 段落不完整。" }
        synchronized(captionLock) { captionMarks.addLast(submittedBytes / 2 to captionIndex++) }
        VoiceDiagnostics.record("pcm_segment_${captionIndex}_start_frame_${submittedBytes/2}")
        capture { it.segment(text) }
    }
    fun playbackCaption(): Int? {
        if (closed.get()) return null
        val head = minOf(sink.playedFrames(), drainTarget ?: (submittedBytes / 2))
        if (head <= 0 || closed.get()) return null
        return synchronized(captionLock) {
            while (captionMarks.size > 1 && captionMarks.elementAt(1).first < head)
                captionMarks.removeFirst()
            captionMarks.firstOrNull()?.takeIf { it.first < head }?.second
        }
    }
    private val levelLock = Any()
    private val levelWindows = ArrayDeque<LevelWindow>()
    private var partialLevel: LevelWindow? = null
    private var levelSampleRate = 24_000
    private var levelByteCarry: Byte? = null
    private var recordedFrames = 0L
    private var binPower = 0.0
    private var binSamples = 0
    private var peakSample = 0
    private var clippedSamples = 0L
    private var largeJumps = 0L
    private var previousSample: Int? = null
    private data class LevelWindow(val startFrame: Long, val endFrame: Long, val level: Float)
    internal val bufferedLevelWindowCount: Int get() = synchronized(levelLock) {
        levelWindows.size + if (partialLevel == null) 0 else 1
    }

    fun configure(sampleRate: Int) = synchronized(writerLock) {
        checkOpen()
        require(sampleRate in 8000..48000) { "不支持的音频采样率。" }
        check(submittedBytes == 0L && carry == null && drainTarget == null) { "音频开始后不能切换格式。" }
        sink.configure(sampleRate)
        levelSampleRate = sampleRate
        capture { it.configure(sampleRate) }
    }

    fun write(bytes: ByteArray) = synchronized(writerLock) {
        checkOpen()
        if (bytes.isEmpty()) return@synchronized
        beforeWrite()
        val data = carry?.let { byteArrayOf(it) + bytes } ?: bytes
        val length = data.size and -2
        carry = if (length < data.size) data.last() else null
        var offset = 0
        var lastProgress = System.nanoTime()
        while (offset < length) {
            checkOpen()
            if (paused.get() || userPaused.get()) {
                if (paused.get()) checkPauseTimeout()
                Thread.sleep(5)
                lastProgress = System.nanoTime()
                continue
            }
            val count = sink.write(data, offset, length - offset)
            checkOpen()
            check(count >= 0 && count <= length - offset) { "音频播放失败。" }
            if (count == 0) {
                check(System.nanoTime() - lastProgress < 5_000_000_000L) { "音频播放停滞。" }
                Thread.sleep(5)
            } else {
                // drain() adds device priming silence, which is never part of a keepsake.
                if (drainTarget == null) capture { it.accept(data, offset, count) }
                recordAcceptedPcm(data, offset, count)
                offset += count
                submittedBytes += count
                lastProgress = System.nanoTime()
            }
        }
    }

    private fun recordAcceptedPcm(data: ByteArray, offset: Int, count: Int) {
        val completed = ArrayList<LevelWindow>()
        val binFrames = maxOf(1, levelSampleRate / 50) // 20 ms, independent of sink write size.
        fun accept(low: Byte, high: Byte) {
            val sample = ((high.toInt() shl 8) or (low.toInt() and 255)).toShort().toInt()
            if (drainTarget==null) {
                val amplitude=kotlin.math.abs(sample)
                peakSample=maxOf(peakSample,amplitude)
                if (amplitude>=32760) clippedSamples++
                previousSample?.let { if (kotlin.math.abs(sample-it)>=30000) largeJumps++ }
                previousSample=sample
            }
            binPower += sample.toDouble() * sample
            binSamples++
            recordedFrames++
            if (binSamples == binFrames) {
                completed.add(LevelWindow(recordedFrames - binSamples, recordedFrames,
                    normalizedLevel(binPower, binSamples)))
                binPower = 0.0
                binSamples = 0
            }
        }
        var index = offset
        val end = offset + count
        levelByteCarry?.let { low ->
            if (index < end) {
                accept(low, data[index])
                levelByteCarry = null
                index++
            }
        }
        while (index + 1 < end) {
            accept(data[index], data[index + 1])
            index += 2
        }
        if (index < end) levelByteCarry = data[index]
        val partial = if (binSamples > 0) LevelWindow(recordedFrames - binSamples, recordedFrames,
            normalizedLevel(binPower, binSamples)) else null
        synchronized(levelLock) {
            completed.forEach { levelWindows.addLast(it) }
            partialLevel = partial
            while (levelWindows.size + (if (partialLevel == null) 0 else 1) > 1_000)
                levelWindows.removeFirst()
        }
    }

    private fun normalizedLevel(power: Double, count: Int): Float =
        (sqrt(power / count) / 32768.0 * 6.0).toFloat().coerceIn(0f, 1f)

    /** Read the volume at AudioTrack's playback head, without waiting for a stalled writer. */
    fun playbackLevel(): Float {
        if (closed.get() || paused.get() || userPaused.get()) return 0f
        val frame = sink.playedFrames()
        if (frame <= 0 || closed.get() || paused.get()) return 0f
        return synchronized(levelLock) {
            while (levelWindows.isNotEmpty() && levelWindows.first().endFrame <= frame)
                levelWindows.removeFirst()
            val window = levelWindows.firstOrNull()?.takeIf { frame >= it.startFrame && frame < it.endFrame }
                ?: partialLevel?.takeIf { frame >= it.startFrame && frame < it.endFrame }
            window?.level ?: 0f
        }
    }

    suspend fun drain() {
        checkOpen()
        check(carry == null && submittedBytes % 2 == 0L) { "PCM 数据不完整。" }
        val target = drainTarget ?: (submittedBytes / 2).also {
            drainTarget = it
            // Streaming AudioTrack may wait for a full buffer, even for the final short clip.
            // Silence primes that threshold; only real audio must play before close discards padding.
            if (it > 0) {
                val padding = sink.endPaddingBytes()
                if (padding > 0) write(ByteArray(padding))
            }
        }
        var lastPlayed = sink.playedFrames()
        var lastProgress = System.nanoTime()
        while (lastPlayed < target) {
            delay(10)
            checkOpen()
            if (paused.get() || userPaused.get()) {
                if (paused.get()) checkPauseTimeout()
                lastProgress = System.nanoTime()
                continue
            }
            val played = sink.playedFrames()
            if (played > lastPlayed) lastProgress = System.nanoTime()
            check(System.nanoTime() - lastProgress < 5_000_000_000L) { "音频播放停滞。" }
            lastPlayed = played
        }
    }

    // Never acquire writerLock here: close must interrupt a blocked or stalled writer.
    fun close() { if (closed.compareAndSet(false, true)) {
        try {
            finalFrames = archiveFrames()
            VoiceDiagnostics.record("pcm_frames_sent_${drainTarget ?: (submittedBytes/2)}_played_$finalFrames")
            VoiceDiagnostics.record("pcm_peak_${peakSample}_clipped_${clippedSamples}_jumps_$largeJumps")
            VoiceDiagnostics.record("playback_underruns_${sink.underruns()}")
        } finally { sink.close() }
    } }
    fun setPaused(value: Boolean) {
        if (closed.get()) return
        if (value && !paused.get()) pauseStarted = System.nanoTime()
        paused.set(value)
        sink.setPaused(value || userPaused.get())
    }
    fun setUserPaused(value: Boolean) {
        if (closed.get()) return
        userPaused.set(value)
        sink.setPaused(value || paused.get())
    }
    private fun checkPauseTimeout() {
        check(System.nanoTime() - pauseStarted < 15_000_000_000L) { "声音仍被其他应用占用，请将游戏静音后重试。" }
    }
    private fun checkOpen() { if (closed.get()) throw CancellationException("播放已停止") }
}

private class AudioTrackSink(private val mediaPlayback: Boolean) : PcmSink {
    private val lock = Any()
    private var closed = false
    private var wraps = 0L
    private var lastHead = 0L
    private var sampleRate = 24_000
    private var paused = false
    private var track = createTrack(sampleRate)

    private fun createTrack(rate: Int) = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(if (mediaPlayback)
            AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(rate / 5, AudioTrack.getMinBufferSize(rate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .build().also {
            if (it.state != AudioTrack.STATE_INITIALIZED) { it.release(); error("无法初始化音频播放。") }
            try {
                it.addOnRoutingChangedListener({ routed ->
                    VoiceDiagnostics.record("playback_route_type_${routed.routedDevice?.type ?: 0}")
                }, android.os.Handler(android.os.Looper.getMainLooper()))
                it.play()
            } catch (error: Exception) { it.release(); throw error }
        }

    override fun configure(sampleRate: Int) = synchronized(lock) {
        if (closed) throw CancellationException("播放已停止")
        if (sampleRate != this.sampleRate) {
            val replacement = createTrack(sampleRate)
            try { if (paused) replacement.pause() }
            catch (error: Exception) { replacement.release(); throw error }
            track.release()
            track = replacement
            this.sampleRate = sampleRate
            wraps = 0L
            lastHead = 0L
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (closed) 0 else track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun playedFrames(): Long = synchronized(lock) {
        if (closed) return@synchronized wraps + lastHead
        val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (head < lastHead) wraps += 0x1_0000_0000L
        lastHead = head
        wraps + head
    }

    override fun endPaddingBytes(): Int = synchronized(lock) { if (closed) 0 else track.bufferSizeInFrames * 2 }
    override fun underruns(): Int = synchronized(lock) { if (closed) 0 else track.underrunCount }

    override fun setPaused(paused: Boolean) = synchronized(lock) {
        this.paused = paused
        if (!closed) { if (paused) track.pause() else track.play() }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { track.pause(); track.flush() } finally { track.release() }
        }
    }
}
