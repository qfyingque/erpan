package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VoiceConversation(
    private val scope: CoroutineScope,
    private val transcribe: suspend (ByteArray)->String,
    private val reply: suspend (String, suspend (String)->Unit)->Unit,
    private val speak: suspend (String)->Unit,
    private val stopPlayback: ()->Unit,
    private val report: (String)->Unit,
    private val streamSpeak: (suspend (ReceiveChannel<String>)->Unit)? = null,
    private val judgeEnd: (suspend (String)->Boolean?)? = null,
    private val continuationMillis: Long = 1500,
    private val transcribeDetailed: (suspend (ByteArray)->SpeechTranscript)? = null,
) {
    @Volatile private var active: Job? = null
    @Volatile private var voiceInterruptionEnabled = true
    private var pendingPcm = byteArrayOf()
    private var ignoreUtterance = false

    fun setVoiceInterruptionEnabled(enabled: Boolean) { voiceInterruptionEnabled = enabled }
    fun acceptsSpeech(): Boolean = voiceInterruptionEnabled || active?.isActive != true

    /** Call on the service's main dispatcher. No transcript or audio survives the turn. */
    fun submit(pcm: ByteArray): Job {
        if (ignoreUtterance || !acceptsSpeech()) {
            ignoreUtterance = false
            return Job().apply { complete() }
        }
        val previous = active
        previous?.cancel()
        stopPlayback()
        val recording = if (judgeEnd != null) (pendingPcm + pcm).also { pendingPcm = it } else pcm
        return scope.launch {
            // Operit cancellation cleanup must finish before a new send can claim the chat.
            previous?.join()
            try {
                if (judgeEnd != null && recording.size > 960_000) {
                    pendingPcm = byteArrayOf()
                    report("连续表达超过 30 秒，本轮未提交，请分段重说。")
                    return@launch
                }
                report("正在识别…")
                val transcript = stage("语音识别失败，请检查识别服务配置与网络。") {
                    transcribeDetailed?.invoke(recording) ?: SpeechTranscript(transcribe(recording))
                }
                val text = transcript.text.trim()
                if (text.isEmpty()) { pendingPcm = byteArrayOf(); report("没有识别到文字，继续聆听。" ); return@launch }
                var unavailable = false
                if (judgeEnd != null) {
                    report("正在判断是否说完…")
                    when (judgeEnd.invoke(text)) {
                        false -> { report("似乎还没说完，等你接着说…"); delay(continuationMillis) }
                        null -> { unavailable = true }
                        true -> Unit
                    }
                }
                currentCoroutineContext().ensureActive()
                pendingPcm = byteArrayOf()
                report(if (unavailable) "智能判断不可用，已按静音提交；等待 Operit 回复…" else "等待 Operit 回复…")
                streamReply(transcript.forChat())
                report(if (judgeEnd == null) "正在聆听 · 约 0.55 秒静音提交" else "正在聆听 · 智能结束判断")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: TurnFailure) {
                currentCoroutineContext().ensureActive()
                pendingPcm = byteArrayOf()
                stopPlayback(); report(failure.message.orEmpty())
            }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                pendingPcm = byteArrayOf()
                stopPlayback(); report("本轮语音失败，继续聆听；本轮不会自动重发。")
            }
        }.also { active = it }
    }

    fun interrupt() {
        ignoreUtterance = false
        pendingPcm = byteArrayOf()
        active?.cancel()
        stopPlayback()
        report("正在聆听…")
    }

    fun speechStarted() {
        ignoreUtterance = !acceptsSpeech()
        if (ignoreUtterance) return
        // A resumed utterance retains buffered audio until it has actually been sent to Operit.
        // Manual interruption/mute uses interrupt(), which explicitly discards pending input.
        active?.cancel()
        stopPlayback()
        report("正在聆听…")
    }

    private suspend fun streamReply(text: String) = coroutineScope {
        // Model text must drain independently of speech playback. Bound memory by
        // total readable characters, not by 32 sentences that can fill in a second.
        val queue = Channel<String>(Channel.UNLIMITED)
        var readableCharacters = 0
        suspend fun enqueue(segments: List<String>) {
            for (segment in segments) {
                readableCharacters += segment.length
                if (readableCharacters > 60_000) throw TurnFailure("回复过长，语音已停止；完整文字请在 Operit 查看。")
                queue.send(segment)
            }
        }
        val buffer = SpeechText(preserveSpacing = streamSpeak != null)
        val mutex = Mutex()
        val speaker = launch {
            if (streamSpeak != null) {
                stage("语音合成或播放失败，请检查合成服务配置与网络。") { streamSpeak.invoke(queue) }
            } else for (segment in queue) {
                report("正在播放 Operit 的回复…")
                stage("语音合成或播放失败，请检查合成服务配置与网络。") { speak(segment) }
            }
        }
        val timer = launch {
            while (isActive) {
                delay(30)
                mutex.withLock { enqueue(buffer.flushReady()) }
            }
        }
        try {
            stage("Operit 回复失败，请检查 Operit 连接与聊天状态；本轮不会自动重发。") {
                reply(text) { delta -> mutex.withLock {
                    enqueue(buffer.push(delta))
                } }
            }
            timer.cancelAndJoin()
            mutex.withLock { enqueue(buffer.flush()) }
            queue.close()
            speaker.join()
        } finally { timer.cancel(); queue.cancel() }
    }

    private suspend fun <T> stage(message: String, action: suspend ()->T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: TurnFailure) { throw failure }
    catch (_: Exception) { throw TurnFailure(message) }

    private class TurnFailure(message: String) : Exception(message)
}
