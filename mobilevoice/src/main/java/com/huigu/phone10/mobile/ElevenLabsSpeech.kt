package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okio.ByteString
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Official Flash TTS / v4 Turbo dialogue socket: one reply, one owner, no replay. */
internal class ElevenLabsSpeech(private val config: SpeechConfig, private val sockets: WebSocket.Factory,
    private val timeoutMillis: Long = 1_800_000, private val heartbeatMillis: Long = 15_000) {
    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        val first = texts.receiveCatching().getOrNull() ?: return
        try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) { exchange(first, texts, onPcm) }; true
            } ?: throw SpeechApiException("ElevenLabs 合成超时，本轮已停止。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error else SpeechApiException("ElevenLabs 音频或结束事件无效。")
        }
    }

    private suspend fun exchange(first: String, texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) = coroutineScope {
        val events = Channel<String>(8)
        val listener = object : WebSocketListener() {
            fun fail(message: String = "ElevenLabs 连接提前中断，本轮不会自动重播。") {
                events.close(SpeechApiException(message))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 4_194_304) { fail(); webSocket.cancel(); return }
                try { runBlocking { events.send(text) } } catch (_: Exception) { /* cancelled receiver */ }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail(); webSocket.cancel() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code; response?.close()
                fail(when (status) {
                    401, 403 -> "ElevenLabs 密钥无效或没有合成权限，请检查账号和 API Key。"
                    402, 429 -> "ElevenLabs 额度不足或请求受限，请查看平台额度。"
                    else -> "ElevenLabs 连接失败，请检查网络与账号；本轮不会自动重播。"
                })
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail()
        }
        var socket: WebSocket? = null
        var input: Job? = null
        try {
            val dialogue = config.elevenDialogueTts
            val ws = sockets.newWebSocket(Request.Builder().url(config.elevenEndpoint().toString().replaceFirst("https://", "wss://"))
                .header("xi-api-key", config.ttsKey).build(), listener).also { socket = it }
            fun send(json: JsonObject) {
                if (!ws.send(json.toString())) throw SpeechApiException("ElevenLabs 文字发送失败，本轮不会自动重发。")
            }
            fun legacyText(text: String) = send(JsonObject().apply { addProperty("text", text) })
            fun control(name: String) = send(JsonObject().apply { addProperty(name, true) })
            if (dialogue) send(JsonObject().apply { add("voices", JsonArray().apply { add(config.voice) }) })
            else legacyText(" ")
            val inputFinished = AtomicBoolean(false)
            input = launch {
                var characters = 0
                var pending = 0
                var firstFlush = true
                fun flush() {
                    control("flush")
                    pending = 0
                    firstFlush = false
                }
                suspend fun append(text: String) {
                    // Dialogue input is verbatim: token fragments must not gain or lose spaces.
                    if (text.isEmpty() || (!dialogue && text.isBlank())) return
                    characters += text.codePointCount(0, text.length)
                    require(characters <= 40_000) { "ElevenLabs 单条回复过长。" }
                    var begin = 0
                    while (begin < text.length) {
                        var end = minOf(begin + 4000, text.length)
                        if (end < text.length && text[end - 1].isHighSurrogate()) end--
                        while (ws.queueSize() > 262_144) { delay(10); ensureActive() }
                        val part = text.substring(begin, end)
                        if (dialogue) {
                            send(JsonObject().apply { add("inputs", JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", part); addProperty("voice_id", config.voice) })
                            }) })
                            pending += part.codePointCount(0, part.length)
                            val boundary = part.any { it in "，。！？；：、,.!?;:\n" }
                            // First clause starts promptly. Later clauses retain more context in this same socket.
                            if ((boundary && (firstFlush || pending >= 80)) || pending >= 160) flush()
                        } else legacyText(if (part.endsWith(' ')) part else "$part ")
                        begin = end
                    }
                }
                append(first)
                while (true) {
                    // A tool pause is not end of input. Only this producer writes to the socket.
                    val wait = if (dialogue && pending > 0) minOf(500L, heartbeatMillis) else heartbeatMillis
                    val next = withTimeoutOrNull(wait) { texts.receiveCatching() }
                    if (next == null) {
                        if (dialogue) { if (pending > 0) flush() else control("keep_alive") }
                        else legacyText(" ")
                        continue
                    }
                    next.exceptionOrNull()?.let { throw it }
                    val text = next.getOrNull() ?: break
                    append(text)
                }
                inputFinished.set(true)
                if (dialogue) control("close_socket") else legacyText("")
            }
            var carry: Byte? = null
            var total = 0L
            for (raw in events) {
                ensureActive()
                val json = JsonParser.parseString(raw).asJsonObject
                if (json.has("error") || json.has("message")) throw SpeechApiException("ElevenLabs 合成失败，请检查模型、音色、密钥与额度。")
                val audio = json["audio"]?.takeUnless { it.isJsonNull }?.asString
                if (!audio.isNullOrEmpty()) {
                    val bytes = Base64.getDecoder().decode(audio)
                    total += bytes.size; require(total <= 86_400_000)
                    val aligned = carry?.let { byteArrayOf(it) + bytes } ?: bytes
                    val even = aligned.size and -2
                    carry = if (even != aligned.size) aligned.last() else null
                    if (even > 0) runInterruptible { onPcm(aligned.copyOf(even)) }
                }
                // A dialogue turn marker is not session completion; queued text/audio still belongs here.
                if (json[if (dialogue) "is_final" else "isFinal"]?.asBoolean == true) {
                    require(inputFinished.get() && total > 0 && carry == null)
                    return@coroutineScope
                }
            }
            throw SpeechApiException("ElevenLabs 缺少结束事件。")
        } finally { input?.cancel(); events.cancel(); socket?.cancel() }
    }
}
