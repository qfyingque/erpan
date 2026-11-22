package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/** Uses the existing locally delimited utterance; never submits partial ASR text to chat. */
internal class VolcengineRecognition(private val config: SpeechConfig, private val sockets: WebSocket.Factory,
    private val emotionHints: Boolean = false, private val timingHints: Boolean = false) {
    internal fun request(): Request = Request.Builder().url(config.volcengineRecognitionEndpoint(config.sttBaseUrl)).apply {
        val id = UUID.randomUUID().toString()
        header("X-Api-Resource-Id", config.effectiveSttVolcResource)
        header("X-Api-Request-Id", id); header("X-Api-Connect-Id", id)
        if (config.sttVolcAppId.isNullOrBlank()) header("X-Api-Key", config.sttKey)
        else { header("X-Api-App-Key", config.sttVolcAppId); header("X-Api-Access-Key", config.sttKey) }
    }.build()

    suspend fun transcribe(pcm: ByteArray): String = transcribeDetailed(pcm).text

    suspend fun transcribeDetailed(pcm: ByteArray): SpeechTranscript {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 960_000) { "录音数据无效。" }
        try {
            return withTimeoutOrNull(90_000) { withContext(Dispatchers.IO) { exchange(pcm) } }
                ?: throw SpeechApiException("火山语音识别超时。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error else SpeechApiException("火山语音识别响应无效。")
        }
    }

    internal fun parameters() = JsonObject().apply {
        add("user", JsonObject().apply { addProperty("uid", "erpan") })
        add("audio", JsonObject().apply {
            addProperty("format", "pcm"); addProperty("codec", "raw")
            addProperty("rate", 16000); addProperty("bits", 16); addProperty("channel", 1)
        })
        add("request", JsonObject().apply {
            addProperty("model_name", config.sttModel); addProperty("result_type", "full")
            addProperty("enable_itn", true); addProperty("enable_punc", true)
            if (emotionHints) addProperty("enable_emotion_detection", true)
            if (emotionHints || timingHints) addProperty("show_utterances", true)
        })
    }

    private suspend fun exchange(pcm: ByteArray): SpeechTranscript = coroutineScope {
        val events = Channel<ByteString>(16)
        val listener = object : WebSocketListener() {
            fun fail(message: String) { events.close(SpeechApiException(message)) }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (bytes.size > VolcengineRecognitionProtocol.MAX) { fail("火山识别响应过大。"); ws.cancel(); return }
                try { runBlocking { events.send(bytes) } } catch (_: Exception) { /* canceled request */ }
            }
            override fun onMessage(ws: WebSocket, text: String) { fail("火山识别未返回兼容的二进制结果。"); ws.cancel() }
            override fun onFailure(ws: WebSocket, error: Throwable, response: Response?) {
                val status = response?.code; response?.close()
                fail(if (status == null) "火山识别网络连接失败。" else "火山识别连接失败（HTTP $status），请检查密钥、资源权限与额度。")
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) = fail("火山识别在最终结果前断开。")
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = fail("火山识别在最终结果前断开。")
        }
        var socket: WebSocket? = null
        var producer: Job? = null
        val inputFinished = AtomicBoolean(false)
        try {
            val ws = sockets.newWebSocket(request(), listener).also { socket = it }
            val parameters = parameters().toString().toByteArray(Charsets.UTF_8)
            fun send(bytes: ByteString) {
                if (!ws.send(bytes)) throw SpeechApiException("火山识别音频发送失败。")
            }
            send(VolcengineRecognitionProtocol.encode(0x10, parameters))
            producer = launch {
                var offset = 0
                while (offset < pcm.size) {
                    ensureActive()
                    while (ws.queueSize() > 262_144) { delay(10); ensureActive() }
                    val end = minOf(offset + 6400, pcm.size)
                    val last = end == pcm.size
                    if (last) inputFinished.set(true)
                    send(VolcengineRecognitionProtocol.encode(if (last) 0x22 else 0x20, pcm.copyOfRange(offset, end)))
                    offset = end
                }
            }
            for (raw in events) {
                ensureActive()
                val frame = VolcengineRecognitionProtocol.decode(raw)
                frame.error?.let { throw SpeechApiException("火山识别失败（错误码 $it），请核对识别服务配置。") }
                val json = JsonParser.parseString(frame.payload.toString(Charsets.UTF_8)).asJsonObject
                val code = json["code"]?.asInt
                if (code != null && code != 20000000) throw SpeechApiException("火山识别失败（错误码 $code）。")
                if (!frame.finished) continue
                check(inputFinished.get())
                return@coroutineScope VolcengineRecognitionProtocol.transcript(json)
            }
            throw SpeechApiException("火山识别未返回最终结果。")
        } finally {
            producer?.cancel(); events.cancel(); socket?.cancel()
        }
    }
}

/** ASR sequence flags differ from the TTS event protocol. */
internal object VolcengineRecognitionProtocol {
    const val MAX = 131_072
    data class Frame(val finished: Boolean, val payload: ByteArray, val error: Int? = null)

    fun transcript(json: JsonObject): SpeechTranscript {
        val result = json["result"] ?: throw SpeechApiException("火山识别结果缺少文字字段。")
        val parts = if (result.isJsonArray) result.asJsonArray.toList() else listOf(result)
        val text = parts.joinToString("") { item ->
            val value = item.takeIf { it.isJsonObject }?.asJsonObject?.get("text")
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString)
                throw SpeechApiException("火山识别结果缺少文字字段。")
            value.asString
        }.trim()
        if (text.length > 20_000) throw SpeechApiException("火山识别文字过长。")
        if (text.isBlank()) throw NoSpeechRecognized()
        val spans = mutableListOf<Pair<Long, Long>>()
        val emotions = mutableListOf<String>()
        fun number(obj: JsonObject, name: String) = obj[name]?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isNumber
        }?.asString?.toDoubleOrNull()?.takeIf { it.isFinite() }
        for (part in parts) {
            val utterances = part.asJsonObject["utterances"]?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (item in utterances.take(1024)) {
                if (!item.isJsonObject) continue
                val utterance = item.asJsonObject
                val definite = utterance["definite"]
                if (definite != null && (!definite.isJsonPrimitive || !definite.asJsonPrimitive.isBoolean || !definite.asBoolean)) continue
                val begin = number(utterance, "start_time"); val end = number(utterance, "end_time")
                if (begin != null && end != null && begin >= 0 && end > begin && end <= 30_000 && begin % 1 == 0.0 && end % 1 == 0.0)
                    spans += begin.toLong() to end.toLong()
                val additions = utterance["additions"]?.takeIf { it.isJsonObject }?.asJsonObject
                val tag = additions?.get("emotion")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                val known = when (tag) {
                    "surprise" -> "surprised"
                    "angry", "happy", "neutral", "sad" -> tag
                    else -> null
                }
                if (known != null) emotions += known
            }
        }
        var transcript = SpeechTranscript(text, spans.take(1024))
        for (tag in emotions.distinct()) transcript = transcript.withProviderEmotion(tag, "火山识别")
        return transcript
    }

    fun encode(typeFlags: Int, payload: ByteArray): ByteString = ByteBuffer.allocate(8 + payload.size).apply {
        put(0x11); put(typeFlags.toByte()); put(if (typeFlags ushr 4 == 1) 0x10 else 0); put(0)
        putInt(payload.size); put(payload)
    }.array().toByteString()

    fun decode(bytes: ByteString): Frame {
        require(bytes.size in 8..MAX)
        val b = ByteBuffer.wrap(bytes.toByteArray())
        val versionHeader = b.get().toInt() and 255
        require(versionHeader ushr 4 == 1)
        val header = (versionHeader and 15) * 4; require(header in 4..bytes.size)
        val typeFlags = b.get().toInt() and 255
        val type = typeFlags ushr 4; val flags = typeFlags and 15
        val encoding = b.get().toInt() and 255
        require(type in setOf(9, 15) && flags in 0..3 && encoding ushr 4 == 1 && (encoding and 15) in 0..1)
        b.position(header)
        fun integer(): Int { require(b.remaining() >= 4); return b.int }
        val error = if (type == 15) integer() else null
        if (type == 9 && flags and 1 != 0) {
            val sequence = integer()
            require(if (flags and 2 != 0) sequence < 0 else sequence > 0)
        }
        val length = integer(); require(length in 0..MAX && length == b.remaining())
        var payload = ByteArray(length).also { b.get(it) }
        if (encoding and 15 == 1) payload = GZIPInputStream(payload.inputStream()).use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX); output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Frame(type == 9 && flags and 2 != 0, payload, error)
    }
}
