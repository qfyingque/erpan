package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/** Official bidi protocol; one reply owns one task/socket. Never retry submitted text. */
internal class MiniMaxSpeech(private val config: SpeechConfig, private val sockets: WebSocket.Factory,
    private val timeoutMillis: Long = 600_000) {
    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        val first = texts.receiveCatching().getOrNull() ?: return
        try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) { exchange(first, texts, onPcm) }
                true
            } ?: throw SpeechApiException("MiniMax 合成超时。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error else SpeechApiException("MiniMax 语音响应无效。")
        }
    }

    private suspend fun exchange(first: String, texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) = coroutineScope {
        val events = Channel<String>(16)
        val listener = object : WebSocketListener() {
            private fun fail() { events.close(SpeechApiException("MiniMax 语音连接中断。")) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 4_194_304) { fail(); webSocket.cancel(); return }
                try { runBlocking { events.send(text) } } catch (_: Exception) { /* canceled operation */ }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail(); webSocket.cancel() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); fail() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail()
        }
        var socket: WebSocket? = null
        try {
            val ws = sockets.newWebSocket(Request.Builder().url(config.minimaxEndpoint(config.ttsBaseUrl))
                .header("Authorization", "Bearer ${config.ttsKey}").build(), listener).also { socket = it }
            fun send(event: String, configure: JsonObject.() -> Unit = {}) {
                val json = JsonObject().apply { addProperty("event", event); configure() }
                if (!ws.send(json.toString())) throw SpeechApiException("MiniMax 语音发送失败。")
            }
            var connected = false
            var started = false
            val inputFinished = AtomicBoolean(false)
            var carry: Byte? = null
            var total = 0L
            for (raw in events) {
                ensureActive()
                val message = JsonParser.parseString(raw).asJsonObject
                val code = message.getAsJsonObject("base_resp")?.get("status_code")?.asInt ?: 0
                if (code != 0) throw SpeechApiException("MiniMax 合成失败（状态码 $code），请核对模型、音色、额度与密钥。")
                val event = message["event"]?.asString
                when (event) {
                    "connected_success" -> {
                        check(!connected); connected = true
                        send("task_start") {
                            addProperty("model", config.ttsModel)
                            add("voice_setting", JsonObject().apply { addProperty("voice_id", config.voice) })
                            add("audio_setting", JsonObject().apply {
                                addProperty("sample_rate", 24_000); addProperty("format", "pcm"); addProperty("channel", 1)
                            })
                        }
                    }
                    "task_started" -> {
                        check(connected && !started); started = true
                        launch {
                            var characters = 0
                            suspend fun input(text: String) {
                                require(text.isNotEmpty())
                                characters += text.length
                                require(characters <= 60_000)
                                // Protocol permits at most 10,000 characters per continue. Preserve
                                // surrogate pairs; server, not this boundary, decides speech sentences.
                                var begin = 0
                                while (begin < text.length) {
                                    var end = minOf(begin + 8_000, text.length)
                                    if (end < text.length && text[end - 1].isHighSurrogate()) end--
                                    while (ws.queueSize() > 262_144) { delay(10); ensureActive() }
                                    send("task_continue") { addProperty("text", text.substring(begin, end)) }
                                    begin = end
                                }
                            }
                            input(first)
                            for (text in texts) { ensureActive(); input(text) }
                            inputFinished.set(true)
                            send("task_finish")
                        }
                    }
                    "task_failed" -> throw SpeechApiException("MiniMax 合成任务失败。")
                    "task_finished" -> {
                        check(started && inputFinished.get())
                        if (total == 0L || carry != null) throw SpeechApiException("MiniMax 音频为空或 PCM 不完整。")
                        return@coroutineScope
                    }
                    "task_continued", "sentence_start", "sentence_end", null -> check(started)
                    else -> throw SpeechApiException("MiniMax 返回不兼容的语音事件。")
                }
                message.getAsJsonObject("extra_info")?.let { info ->
                    info["audio_sample_rate"]?.let { check(it.asInt == 24_000) }
                    info["audio_channel"]?.let { check(it.asInt == 1) }
                    info["audio_format"]?.let { check(it.asString == "pcm") }
                }
                val hex = message.getAsJsonObject("data")?.get("audio")?.asString.orEmpty()
                if (hex.isNotEmpty()) {
                    check(started)
                    val bytes = decodeHex(hex)
                    total += bytes.size
                    check(total <= 28_800_000)
                    val aligned = carry?.let { byteArrayOf(it) + bytes } ?: bytes
                    val size = aligned.size and -2
                    carry = if (size == aligned.size) null else aligned.last()
                    if (size > 0) runInterruptible { onPcm(aligned.copyOf(size)) }
                }
                // is_final ends one audio request; sentence_end ends one sentence. Neither closes this task.
            }
            throw SpeechApiException("MiniMax 未返回完整的任务结束事件。")
        } finally { events.cancel(); socket?.cancel() }
    }

    companion object {
        internal fun decodeHex(value: String): ByteArray {
            require(value.length % 2 == 0)
            return ByteArray(value.length / 2) { index ->
                val high = value[index * 2].digitToIntOrNull(16) ?: error("Invalid hex")
                val low = value[index * 2 + 1].digitToIntOrNull(16) ?: error("Invalid hex")
                (high * 16 + low).toByte()
            }
        }
    }
}
