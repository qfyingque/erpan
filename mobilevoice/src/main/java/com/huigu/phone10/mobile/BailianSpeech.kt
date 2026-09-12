package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** DashScope task WebSocket protocol. One socket belongs to one cancellable speech operation. */
internal class BailianSpeech(
    private val config: SpeechConfig,
    private val sockets: WebSocket.Factory,
    private val timeoutMillis: Long = 600_000,
) {
    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        val first = texts.receiveCatching().getOrNull() ?: return
        require(first.isNotBlank() && first.length <= 20_000) { "朗读内容为空或过长。" }
        exchange(null, first, onPcm, texts)
    }

    suspend fun transcribe(pcm: ByteArray): String = transcribeDetailed(pcm).text

    suspend fun transcribeDetailed(pcm: ByteArray): SpeechTranscript {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 960_000) { "录音数据无效。" }
        return exchange(pcm, null, {})
    }

    suspend fun speak(text: String, onPcm: (ByteArray) -> Unit) {
        require(text.isNotBlank() && text.length <= 20_000) { "朗读内容为空或过长。" }
        exchange(null, text, onPcm)
    }

    private sealed interface Event {
        data object Open : Event
        data class Text(val value: String) : Event
        data class Audio(val value: ByteString) : Event
    }

    private suspend fun exchange(pcm: ByteArray?, text: String?, onPcm: (ByteArray) -> Unit,
                                 remaining: ReceiveChannel<String>? = null): SpeechTranscript {
        val asr = pcm != null
        val stage = if (asr) "识别" else "合成"
        try {
            return withTimeoutOrNull(if (asr) minOf(timeoutMillis, 90_000) else timeoutMillis) {
                withContext(Dispatchers.IO) {
                    val events = Channel<Event>(16)
                    val listener = object : WebSocketListener() {
                        // Backpressure stays on OkHttp's reader; cancellation releases a full queue.
                        private fun deliver(event: Event) {
                            try { runBlocking { events.send(event) } }
                            catch (_: Exception) { /* Operation ended; never deliver late frames. */ }
                        }
                        private fun fail() { events.close(SpeechApiException("百炼${stage}连接中断或响应无效。")) }
                        override fun onOpen(webSocket: WebSocket, response: Response) = deliver(Event.Open)
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.length > 65_536) { fail(); webSocket.cancel() }
                            else deliver(Event.Text(text))
                        }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            if (bytes.size > 1_048_576) { fail(); webSocket.cancel() }
                            else deliver(Event.Audio(bytes))
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            response?.close(); fail()
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail()
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail()
                    }
                    val request = Request.Builder()
                        .url(config.bailianEndpoint(if (asr) config.sttBaseUrl else config.ttsBaseUrl))
                        .header("Authorization", "Bearer ${if (asr) config.sttKey else config.ttsKey}")
                        .header("User-Agent", "Phone10-Mobile/0.2.1").build()
                    var socket: WebSocket? = null
                    try {
                        socket = sockets.newWebSocket(request, listener)
                        consume(socket, events, pcm, text, onPcm, remaining)
                    } finally {
                        events.cancel()
                        socket?.cancel()
                    }
                }
            } ?: throw SpeechApiException("百炼${stage}超时，请检查网络后重试。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error else SpeechApiException("百炼${stage}响应处理失败。")
        }
    }

    private suspend fun consume(socket: WebSocket, events: Channel<Event>, pcm: ByteArray?, text: String?,
                                onPcm: (ByteArray) -> Unit, remaining: ReceiveChannel<String>?): SpeechTranscript = coroutineScope {
        val asr = pcm != null
        val taskId = UUID.randomUUID().toString()
        var opened = false
        var started = false
        val inputFinished = AtomicBoolean(false)
        var carry: Byte? = null
        var totalAudio = 0L
        var totalText = 0
        val sentences = TreeMap<Long, SpeechTranscript>()
        val pending = mutableSetOf<Long>()
        fun send(action: String, payload: JsonObject) {
            val message = JsonObject().apply {
                add("header", JsonObject().apply {
                    addProperty("action", action); addProperty("task_id", taskId); addProperty("streaming", "duplex")
                })
                add("payload", payload)
            }
            if (!socket.send(message.toString())) throw SpeechApiException("百炼语音发送失败。")
        }
        fun input(value: String? = null) = JsonObject().apply {
            add("input", JsonObject().apply { if (value != null) addProperty("text", value) })
        }
        for (event in events) {
            currentCoroutineContext().ensureActive()
            when (event) {
                Event.Open -> {
                    check(!opened); opened = true
                    send("run-task", input().apply {
                        addProperty("task_group", "audio"); addProperty("task", if (asr) "asr" else "tts")
                        addProperty("function", if (asr) "recognition" else "SpeechSynthesizer")
                        addProperty("model", if (asr) config.sttModel else config.ttsModel)
                        add("parameters", JsonObject().apply {
                            addProperty("format", "pcm"); addProperty("sample_rate", if (asr) 16_000 else 24_000)
                            if (!asr) { addProperty("text_type", "PlainText"); addProperty("voice", config.voice) }
                        })
                    })
                }
                is Event.Audio -> {
                    check(started && !asr)
                    val bytes = event.value.toByteArray()
                    totalAudio += bytes.size
                    check(totalAudio <= 28_800_000) { "Audio limit" } // bounded ten-minute PCM stream
                    val aligned = carry?.let { byteArrayOf(it) + bytes } ?: bytes
                    val count = aligned.size and -2
                    carry = if (count == aligned.size) null else aligned.last()
                    if (count > 0) runInterruptible { onPcm(aligned.copyOf(count)) }
                }
                is Event.Text -> {
                    val message = JsonParser.parseString(event.value).asJsonObject
                    val header = message.getAsJsonObject("header")
                    check(opened && header.string("task_id") == taskId)
                    when (header.string("event")) {
                        "task-started" -> {
                            check(!started); started = true
                            if (pcm != null) {
                                // Already delimited locally: upload buffered PCM, then explicitly flush
                                // ASR. Do not wait for the cloud VAD's default 1.3 second silence.
                                var offset = 0
                                while (offset < pcm.size) {
                                    currentCoroutineContext().ensureActive()
                                    val size = minOf(3200, pcm.size - offset)
                                    check(socket.send(pcm.toByteString(offset, size)))
                                    offset += size
                                }
                                inputFinished.set(true)
                                send("finish-task", input())
                            } else {
                                // Input and PCM output must progress independently. All fragments
                                // belong to this task; only the end of the reply finishes it.
                                send("continue-task", input(text))
                                if (remaining == null) {
                                    inputFinished.set(true)
                                    send("finish-task", input())
                                } else launch {
                                    var sentCharacters = requireNotNull(text).length
                                    for (part in remaining) {
                                        ensureActive()
                                        require(part.isNotEmpty() && part.length <= 20_000)
                                        sentCharacters += part.length
                                        require(sentCharacters <= 60_000)
                                        while (socket.queueSize() > 262_144) { delay(10); ensureActive() }
                                        send("continue-task", input(part))
                                    }
                                    inputFinished.set(true)
                                    send("finish-task", input())
                                }
                            }
                        }
                        "result-generated" -> {
                            check(started)
                            if (asr) {
                                val sentence = message.getAsJsonObject("payload").getAsJsonObject("output").getAsJsonObject("sentence")
                                if (sentence.get("heartbeat")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true) continue
                                val value = sentence.string("text").trim()
                                val begin = sentence.get("begin_time").asLong
                                check(begin >= 0)
                                val end = sentence.get("sentence_end")
                                check(end.isJsonPrimitive && end.asJsonPrimitive.isBoolean)
                                if (end.asBoolean) {
                                    pending.remove(begin)
                                    totalText += value.length - (sentences[begin]?.text?.length ?: 0)
                                    check(totalText <= 65_536 && sentences.size < 1024)
                                    sentences[begin] = SpeechTranscript.fromBailian(value, listOf(sentence))
                                } else if (value.isNotEmpty() && begin !in sentences) {
                                    pending.add(begin); check(pending.size <= 1024)
                                }
                            }
                        }
                        "task-finished" -> {
                            check(started)
                            if (asr) {
                                if (pending.isNotEmpty()) throw SpeechApiException("百炼识别结果不完整，请重新说一句。")
                                val result = SpeechTranscript.combine(sentences.values)
                                if (result.text.isEmpty()) throw SpeechApiException("没有识别到文字，请重新说一句。")
                                return@coroutineScope result
                            }
                            check(inputFinished.get()) { "TTS finished before input ended" }
                            if (totalAudio == 0L || carry != null) throw SpeechApiException("合成音频为空或 PCM 数据不完整。")
                            return@coroutineScope SpeechTranscript("")
                        }
                        "task-failed" -> throw SpeechApiException("百炼语音任务失败，请检查密钥、模型、音色及地域是否匹配。")
                        else -> throw SpeechApiException("百炼返回了不兼容的语音事件。")
                    }
                }
            }
        }
        throw SpeechApiException("百炼语音连接提前结束。")
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        check(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
}
