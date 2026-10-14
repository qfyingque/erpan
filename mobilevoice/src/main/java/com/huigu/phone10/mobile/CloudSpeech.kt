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

class CloudSpeech(private val config: SpeechConfig, client: OkHttpClient = OkHttpClient(), webSockets: WebSocket.Factory? = null,
    private val onSynthesisStatus: suspend (String) -> Unit = {}) {
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build()
    private val bailian by lazy { BailianSpeech(config, webSockets ?: http) }
    private val minimax by lazy { MiniMaxSpeech(config, webSockets ?: http) }
    private val eleven by lazy { ElevenLabsSpeech(config, webSockets ?: http) }
    private val volcengine by lazy { VolcengineSpeech(config, webSockets ?: http) }
    private val qwen by lazy { QwenSpeech(config, webSockets ?: http, onStatus = onSynthesisStatus,
        lifecycle = if (webSockets == null) QwenLifecycle(config, http) else null) }
    private val volcengineRecognition by lazy { VolcengineRecognition(config, webSockets ?: http) }

    init { config.validate(includeRecognition = false) }

    suspend fun checkRecognitionConnection() {
        // One second of generated silence; never capture the user's microphone.
        try { transcribe(ByteArray(32_000)) }
        catch (_: NoSpeechRecognized) { /* Valid completion with no words is expected for silence. */ }
    }

    suspend fun checkSynthesisConnection() {
        var received = 0L
        speak("你好，连接测试。", { rate ->
            require(rate > 0) { "合成采样率无效。" }
        }, { pcm -> received += pcm.size })
        if (received == 0L) throw SpeechApiException("合成音频为空。")
    }

    suspend fun speakStream(texts: ReceiveChannel<String>, onPlayed: suspend () -> Unit = {},
        onAbort: () -> Unit = {}, onSegment: (String) -> Unit = {}, onPcm: (ByteArray) -> Unit) {
        when (config.effectiveTtsProvider) {
            SpeechConfig.BAILIAN -> bailian.speakStream(texts, onPcm)
            SpeechConfig.MINIMAX -> minimax.speakStream(texts, onPcm)
            SpeechConfig.ELEVENLABS -> eleven.speakStream(texts, onPcm)
            SpeechConfig.VOLCENGINE -> volcengine.speakStream(texts, onPcm)
            SpeechConfig.QWEN_LOCAL -> qwen.speakStream(texts, onPlayed, onAbort, onSegment, onPcm)
            else -> error("当前语音协议不支持持续追加文字。")
        }
    }

    suspend fun transcribe(pcm: ByteArray): String {
        config.validate()
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 960_000) { "录音数据无效。" }
        if (config.isBailian) return bailian.transcribe(pcm)
        if (config.provider == SpeechConfig.VOLCENGINE) return volcengineRecognition.transcribe(pcm)
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
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString)
                throw SpeechApiException("识别响应缺少有效的文字字段，请检查服务接口。")
            val text = value.asString.trim()
            if (text.isBlank()) throw NoSpeechRecognized()
            text
        }
    }

    suspend fun transcribeDetailed(pcm: ByteArray): SpeechTranscript {
        config.validate()
        return if (config.isBailian) bailian.transcribeDetailed(pcm) else SpeechTranscript(transcribe(pcm))
    }

    suspend fun speak(text: String, onPcm: (ByteArray) -> Unit) = speak(text, { rate ->
        require(rate == 24_000) { "播放器需要支持返回的音频采样率。" }
    }, onPcm)

    suspend fun speak(text: String, onSampleRate: (Int) -> Unit, onPcm: (ByteArray) -> Unit) {
        require(text.isNotBlank()) { "朗读内容为空。" }
        if (config.streamingTts) {
            val texts = kotlinx.coroutines.channels.Channel<String>(1)
            texts.trySend(text); texts.close()
            speakStream(texts, onPcm = onPcm); return
        }
        val moss = config.effectiveTtsProvider == SpeechConfig.MOSSLAND
        val elevenHttp = config.effectiveTtsProvider == SpeechConfig.ELEVENLABS
        if (elevenHttp && text.codePointCount(0, text.length) > if (config.ttsModel == "eleven_v4") 10_000 else 4000)
            throw SpeechApiException("ElevenLabs 单段过长，请分段朗读。")
        if (moss) VoiceDiagnostics.record("moss_request_chars_${text.length}")
        val json = if (moss) MossSpeech.request(config, text) else if (elevenHttp) JsonObject().apply {
            addProperty("text", text); addProperty("model_id", config.ttsModel)
        } else JsonObject().apply {
            addProperty("model", config.ttsModel)
            addProperty("voice", config.voice)
            addProperty("input", text)
            addProperty("response_format", "pcm")
        }
        val request = Request.Builder().url(if (elevenHttp) config.elevenEndpoint() else config.ttsEndpoint())
            .apply { if (elevenHttp) header("xi-api-key", config.ttsKey) else header("Authorization", "Bearer ${config.ttsKey}") }
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        execute("合成", request) { response, active ->
            if (moss) {
                MossSpeech.consume(response, active, onSampleRate, onPcm)
                return@execute
            }
            val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (type !in setOf("application/octet-stream", "audio/pcm", "audio/x-pcm")) {
                throw SpeechApiException("合成服务未返回兼容的 PCM 音频。")
            }
            val input = response.body.byteStream()
            if (elevenHttp) onSampleRate(24_000)
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
                    if (elevenHttp && total > 86_400_000) throw SpeechApiException("ElevenLabs 音频超过单轮播放上限。")
                }
            }
            if (active() && (carry != null || total == 0L)) throw SpeechApiException("合成音频为空或 PCM 数据不完整。")
        }
    }

    private suspend fun <T> execute(stage: String, request: Request, consume: (Response, () -> Boolean) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val startedAt = System.nanoTime()
            fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000
            val call = http.newCall(request)
            val responseRef = AtomicReference<Response?>()
            continuation.invokeOnCancellation {
                VoiceDiagnostics.record("http_${stage}_cancelled_${elapsed()}ms")
                call.cancel(); responseRef.getAndSet(null)?.close()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        val timeout = e is java.io.InterruptedIOException
                        VoiceDiagnostics.record("http_${stage}_${if (timeout) "timeout" else "network_error"}_${elapsed()}ms")
                        continuation.resumeWithException(SpeechApiException(if (timeout) "${stage}请求超时。" else "${stage}网络请求失败。"))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    responseRef.set(response)
                    response.use {
                        try {
                            if (!continuation.isActive) return
                            VoiceDiagnostics.record("http_${stage}_status_${response.code}_${elapsed()}ms")
                            if (!response.isSuccessful) throw SpeechApiException("${stage}服务返回 HTTP ${response.code}。")
                            val value = consume(response) { continuation.isActive && !call.isCanceled() }
                            if (continuation.isActive) {
                                VoiceDiagnostics.record("http_${stage}_complete_${elapsed()}ms")
                                continuation.resume(value)
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) {
                                val timeout = error is java.io.InterruptedIOException
                                val failure = if (error is SpeechApiException) error
                                    else SpeechApiException(if (timeout) "${stage}音频或响应接收超时。" else "${stage}响应处理失败。")
                                VoiceDiagnostics.record("http_${stage}_failed_${elapsed()}ms: ${failure.message.orEmpty()}")
                                continuation.resumeWithException(failure)
                            }
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
open class SpeechApiException internal constructor(message: String) : IOException(message)

internal class NoSpeechRecognized : SpeechApiException("没有识别到文字，请重新说一句。")
