package com.huigu.phone10.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class UtteranceTooLongException : IllegalStateException("一句话超过30秒，请停顿后重新开麦。")

class PcmRecorder(context: Context) {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val lock = Any()
    private var record: AudioRecord? = null
    private val effects = mutableListOf<AudioEffect>()

    @SuppressLint("MissingPermission") // Foreground owner checks RECORD_AUDIO before invoking run.
    suspend fun run(onSpeechStart: () -> Unit, onUtterance: (ByteArray) -> Unit, onReady: () -> Unit = {},
                    acceptInput: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "录音已启动。" }
        try {
            // Segmentation below owns minimum speech and trailing silence exactly once.
            VadSilero(context, SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_512,
                Mode.NORMAL, 0, 0).use { vad ->
                synchronized(lock) {
                    if (closed.get()) return@withContext
                    record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(4096, AudioRecord.getMinBufferSize(16_000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
                    val input = requireNotNull(record)
                    check(input.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风。" }
                    if (AcousticEchoCanceler.isAvailable()) enableEffect { AcousticEchoCanceler.create(input.audioSessionId) }
                    if (NoiseSuppressor.isAvailable()) enableEffect { NoiseSuppressor.create(input.audioSessionId) }
                    input.startRecording()
                    check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音。" }
                }
                onReady()
                val frame = ByteArray(1024)
                var filled = 0
                val segmenter = UtteranceSegmenter()
                while (!closed.get()) {
                    currentCoroutineContext().ensureActive()
                    val count = synchronized(lock) {
                        record?.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING) ?: 0
                    }
                    if (closed.get()) break
                    check(count >= 0) { "麦克风读取失败。" }
                    if (count == 0) { delay(5); continue }
                    filled += count
                    if (filled == frame.size) {
                        val speech = vad.isSpeech(frame)
                        if (!closed.get()) segmenter.accept(frame, speech, onSpeechStart, onUtterance, acceptInput())
                        filled = 0
                    }
                }
            }
        } finally { close() }
    }

    private fun enableEffect(create: () -> AudioEffect?) {
        // OEMs can advertise an effect and still refuse creation for this audio session.
        val effect = try { create() } catch (_: RuntimeException) { null } ?: return
        effects.add(effect)
        try { effect.enabled = true } catch (_: RuntimeException) { /* Device effect is optional. */ }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            val input = record
            record = null
            try { if (input?.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.stop() }
            finally {
                try { input?.release() }
                finally { effects.forEach { it.release() }; effects.clear() }
            }
        }
    }
}

internal class UtteranceSegmenter {
    private val preRoll = ArrayDeque<ByteArray>()
    private val utterance = ByteArrayOutputStream()
    private var active = false
    private var speechFrames = 0
    private var silenceFrames = 0
    private var failed = false
    private var waitingForSilence = false

    fun accept(frame: ByteArray, speech: Boolean, onStart: () -> Unit, onUtterance: (ByteArray) -> Unit,
               acceptInput: Boolean = true) {
        check(!failed) { "录音已停止。" }
        require(frame.size == 1024) { "录音帧大小无效。" }
        if (!acceptInput) {
            preRoll.clear(); utterance.reset()
            active = false; speechFrames = 0; silenceFrames = 0
            waitingForSilence = true
            return
        }
        // Discard speech that began during the AI turn, including its trailing audio.
        if (waitingForSilence) {
            if (!speech) waitingForSilence = false
            return
        }
        if (!active) {
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 10) preRoll.removeFirst() // 320 ms includes onset confirmation.
            speechFrames = if (speech) speechFrames + 1 else 0
            if (speechFrames < 3) return // 96 ms of consecutive human speech rejects isolated noise.
            active = true
            preRoll.forEach { utterance.write(it) }
            preRoll.clear()
            onStart()
        } else utterance.write(frame)
        silenceFrames = if (speech) 0 else silenceFrames + 1
        if (utterance.size() > 960_000) {
            failed = true
            utterance.reset()
            preRoll.clear()
            throw UtteranceTooLongException()
        }
        if (silenceFrames >= 18) { // 18 * 32 ms = 576 ms, the first complete frame after 550 ms.
            val pcm = utterance.toByteArray()
            utterance.reset()
            active = false
            speechFrames = 0
            silenceFrames = 0
            onUtterance(pcm)
        }
    }
}
