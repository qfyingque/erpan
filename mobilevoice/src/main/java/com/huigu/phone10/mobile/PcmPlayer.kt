package com.huigu.phone10.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

internal interface PcmSink {
    fun write(data: ByteArray, offset: Int, length: Int): Int
    fun playedFrames(): Long
    fun endPaddingBytes(): Int = 0
    fun setPaused(paused: Boolean) = Unit
    fun close()
}

class PcmPlayer internal constructor(private val sink: PcmSink, private val beforeWrite: () -> Unit = {}) {
    constructor(beforeWrite: () -> Unit = {}): this(AudioTrackSink(), beforeWrite)
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    @Volatile private var pauseStarted = 0L
    private val writerLock = Any()
    private var carry: Byte? = null
    @Volatile private var submittedBytes = 0L
    private var drainTarget: Long? = null

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
            if (paused.get()) {
                checkPauseTimeout()
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
                offset += count
                submittedBytes += count
                lastProgress = System.nanoTime()
            }
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
            if (paused.get()) {
                checkPauseTimeout()
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
    fun close() { if (closed.compareAndSet(false, true)) sink.close() }
    fun setPaused(value: Boolean) {
        if (closed.get()) return
        if (value && !paused.get()) pauseStarted = System.nanoTime()
        paused.set(value)
        sink.setPaused(value)
    }
    private fun checkPauseTimeout() {
        check(System.nanoTime() - pauseStarted < 15_000_000_000L) { "声音仍被其他应用占用，请将游戏静音后重试。" }
    }
    private fun checkOpen() { if (closed.get()) throw CancellationException("播放已停止") }
}

private class AudioTrackSink : PcmSink {
    private val lock = Any()
    private var closed = false
    private var wraps = 0L
    private var lastHead = 0L
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(4800, AudioTrack.getMinBufferSize(24_000,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .build().also {
            if (it.state != AudioTrack.STATE_INITIALIZED) { it.release(); error("无法初始化音频播放。") }
            try { it.play() } catch (error: Exception) { it.release(); throw error }
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

    override fun setPaused(paused: Boolean) = synchronized(lock) {
        if (!closed) { if (paused) track.pause() else track.play() }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { track.pause(); track.flush() } finally { track.release() }
        }
    }
}
