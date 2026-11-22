package com.huigu.phone10.mobile

import kotlinx.coroutines.*

/** Independent opt-in: upgrading an old enabled hint switch alone never authorizes a second upload. */
data class EmotionSourceConfig(val enabled: Boolean = false, val baseUrl: String = QwenAsrSpeech.BASE,
    val key: String = "", val model: String = QwenAsrSpeech.MODEL) {
    fun validationErrors(): List<String> = buildList {
        try { QwenAsrSpeech.endpoint(baseUrl) } catch (_: IllegalArgumentException) { add("情绪补充地址不正确，请填写百炼兼容模式地址。") }
        if (!QwenAsrSpeech.validModel(model)) add("情绪补充模型请使用 qwen3-asr-flash 或对应日期版本。")
        if (key.isEmpty() || key.length > 4096 || key.any { it.code !in 33..126 } || key.startsWith("sk-sp-"))
            add("情绪补充请填写对应地域的普通百炼 API Key，Token Plan 套餐 Key 不适用。")
    }
    override fun toString() = "EmotionSourceConfig(credentials=redacted)"
}

internal class EmotionRecognition(
    private val enabled: Boolean,
    private val source: EmotionSourceConfig?,
    private val primary: suspend (ByteArray) -> SpeechTranscript,
    private val analyze: suspend (EmotionSourceConfig, ByteArray) -> SpeechTranscript,
    private val diagnose: (String) -> Unit = VoiceDiagnostics::record,
    private val timeoutMillis: Long = 8000,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private var generation = 0L
    private var microphoneEnabled = true
    private val supplements = linkedSetOf<Job>()

    /** Mute invalidates only optional analysis; an already accepted utterance keeps its words. */
    fun cancelSupplement() {
        val pending = synchronized(lock) { generation++; microphoneEnabled = false; supplements.toList() }
        pending.forEach { it.cancel() }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (!enabled) cancelSupplement()
        else synchronized(lock) { microphoneEnabled = true }
    }

    /** Capture the permission epoch when audio is accepted, before old-turn cleanup is awaited. */
    fun prepare(pcm: ByteArray): suspend () -> SpeechTranscript {
        val ticket = synchronized(lock) { generation to microphoneEnabled }
        return { transcribe(pcm, ticket) }
    }

    suspend fun transcribe(pcm: ByteArray): SpeechTranscript = prepare(pcm).invoke()

    private suspend fun transcribe(pcm: ByteArray, accepted: Pair<Long, Boolean>): SpeechTranscript {
        val began = clockMillis()
        val ticket = accepted.first
        val original = primary(pcm)
        currentCoroutineContext().ensureActive()
        if (!enabled || original.hasEmotions || source?.enabled != true) return original
        if (!accepted.second || synchronized(lock) { !microphoneEnabled || generation != ticket }) return original
        if (source.validationErrors().isNotEmpty()) {
            diagnose("emotion_supplement_not_configured_plain_text_kept")
            return original
        }
        // The existing interruption path has a 15s deadline. Optional analysis
        // must not consume the remainder and discard already recognized words.
        val remaining = 14_000L - maxOf(0L, clockMillis() - began)
        val budget = minOf(timeoutMillis, remaining)
        if (remaining < 500 || budget <= 0) {
            diagnose("emotion_supplement_budget_exhausted_plain_text_kept")
            return original
        }
        val extra = try {
            supervisorScope {
                val work = async(start = CoroutineStart.LAZY) { withTimeoutOrNull(budget) { analyze(source, pcm) } }
                val registered = synchronized(lock) {
                    if (generation != ticket) false else { supplements.add(work); true }
                }
                if (!registered) { work.cancel(); null }
                else try {
                    work.start(); work.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null // Our optional child was muted; external cancellation still propagates.
                } finally { synchronized(lock) { supplements.remove(work) } }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            diagnose("emotion_supplement_failed_plain_text_kept")
            return original
        }
        currentCoroutineContext().ensureActive()
        if (synchronized(lock) { generation != ticket }) {
            diagnose("emotion_supplement_muted_plain_text_kept")
            return original
        }
        if (extra == null) diagnose("emotion_supplement_timeout_plain_text_kept")
        else if (!extra.hasEmotions) diagnose("emotion_supplement_empty_plain_text_kept")
        else diagnose("emotion_supplement_complete")
        return if (extra == null) original else original.withEmotionsFrom(extra)
    }
}
