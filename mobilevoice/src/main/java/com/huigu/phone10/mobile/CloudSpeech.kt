package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CloudSpeech(private val config: SpeechConfig, client: OkHttpClient = OkHttpClient(), webSockets: WebSocket.Factory? = null) {
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build()
    private val bailian by lazy { BailianSpeech(config, webSockets ?: http) }
    private val minimax by lazy { MiniMaxSpeech(config, webSockets ?: http) }

    init { config.validate() }

    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        when (config.effectiveTtsProvider) {
            SpeechConfig.BAILIAN -> bailian.speakStream(texts, onPcm)
            SpeechConfig.MINIMAX -> minimax.speakStream(texts, onPcm)
            else -> error("当前语音协议不支持持续追加文字。")
        }
    }

    suspend fun transcribe(pcm: ByteArray): String {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 960_000) { "录音数据无效。" }
        if (config.isBailian) return bailian.transcribe(pcm)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.sttModel)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav(pcm).toRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url(config.sttEndpoint()).header("Authorization", "Bearer ${config.sttKey}")
            .post(body).build()
        return execute("识别", request) { response, active ->
            val input = response.body.byteStream()
            val buffer = java.io.ByteArrayOutputStream()
            val bytes = ByteArray(4096)
            while (active()) {
                val count = input.read(bytes)
                if (count < 0) break
                if (buffer.size() + count > 65_536) throw SpeechApiException("识别响应过大。")
                buffer.write(bytes, 0, count)
            }
            val json = JsonParser.parseString(buffer.toString("UTF-8"))
            val value = if (json.isJsonObject) json.asJsonObject.get("text") else null
            val text = if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString.trim() else ""
            if (text.isBlank()) throw SpeechApiException("没有识别到文字，请重新说一句。")
            text
        }
    }

    suspend fun transcribeDetailed(pcm: ByteArray): SpeechTranscript =
        if (config.isBailian) bailian.transcribeDetailed(pcm) else SpeechTranscript(transcribe(pcm))

    suspend fun speak(text: String, onPcm: (ByteArray) -> Unit) {
        require(text.isNotBlank()) { "朗读内容为空。" }
        if (config.streamingTts) {
            val texts = kotlinx.coroutines.channels.Channel<String>(1)
            texts.trySend(text); texts.close()
            speakStream(texts, onPcm); return
        }
        val json = JsonObject().apply {
            addProperty("model", config.ttsModel)
            addProperty("voice", config.voice)
            addProperty("input", text)
            addProperty("response_format", "pcm")
        }
        val request = Request.Builder().url(config.ttsEndpoint()).header("Authorization", "Bearer ${config.ttsKey}")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        execute("合成", request) { response, active ->
            val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (type !in setOf("application/octet-stream", "audio/pcm", "audio/x-pcm")) {
                throw SpeechApiException("合成服务未返回兼容的 PCM 音频。")
            }
            val input = response.body.byteStream()
            val buffer = ByteArray(8192)
            var carry: Byte? = null
            var total = 0L
            while (active()) {
                val offset = if (carry == null) 0 else 1
                carry?.let { buffer[0] = it }
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                if (read == 0) continue
                val count = offset + read
                val even = count and -2
                carry = if (count != even) buffer[count - 1] else null
                if (even > 0 && active()) {
                    // Providers must honor response_format=pcm; no decoding or fallback is inferred.
                    onPcm(buffer.copyOf(even))
                    total += even
                }
            }
            if (active() && (carry != null || total == 0L)) throw SpeechApiException("合成音频为空或 PCM 数据不完整。")
        }
    }

    private suspend fun <T> execute(stage: String, request: Request, consume: (Response, () -> Boolean) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            val responseRef = AtomicReference<Response?>()
            continuation.invokeOnCancellation { call.cancel(); responseRef.getAndSet(null)?.close() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(SpeechApiException("${stage}请求失败或超时。"))
                }

                override fun onResponse(call: Call, response: Response) {
                    responseRef.set(response)
                    response.use {
                        try {
                            if (!continuation.isActive) return
                            if (!response.isSuccessful) throw SpeechApiException("${stage}服务返回 HTTP ${response.code}。")
                            val value = consume(response) { continuation.isActive && !call.isCanceled() }
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (error is SpeechApiException) error else SpeechApiException("${stage}响应处理失败。"))
                        } finally { responseRef.compareAndSet(response, null) }
                    }
                }
            })
        }

    private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
        putInt(16_000); putInt(32_000); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
    }.array()
}

/** Only fixed, safe messages reach the service; response bodies and transport causes never escape. */
class SpeechApiException internal constructor(message: String) : IOException(message)
