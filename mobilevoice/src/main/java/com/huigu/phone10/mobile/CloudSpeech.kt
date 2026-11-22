package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
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
    private val onSynthesisStatus: suspend (String) -> Unit = {},
    private val requestEmotionHints: Boolean = false, private val requestTimingHints: Boolean = false) {
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build()
    private val bailian by lazy { BailianSpeech(config, webSockets ?: http) }
    private val minimax by lazy { MiniMaxSpeech(config, webSockets ?: http) }
    // Whole-reply HTTP may stream for longer than the general HTTP client's 90 seconds.
    private val minimaxHttp by lazy { http.newBuilder().callTimeout(600, TimeUnit.SECONDS).build() }
    private val eleven by lazy { ElevenLabsSpeech(config, webSockets ?: http) }
    private val volcengine by lazy { VolcengineSpeech(config, webSockets ?: http) }
    private val qwen by lazy { QwenSpeech(config, webSockets ?: http, onStatus = onSynthesisStatus,
        lifecycle = if (webSockets == null) QwenLifecycle(config, http) else null) }
    private val volcengineRecognition by lazy { VolcengineRecognition(config, webSockets ?: http,
        emotionHints = requestEmotionHints, timingHints = requestTimingHints) }

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

    suspend fun readElevenVoiceSettings(): ElevenVoiceSettings {
        require(config.effectiveTtsProvider == SpeechConfig.ELEVENLABS) { "请选择 ElevenLabs。" }
        val request = Request.Builder().url(config.elevenVoiceSettingsEndpoint())
            .header("xi-api-key", config.ttsKey).get().build()
        return kotlinx.coroutines.withTimeoutOrNull(15_000) {
            execute("读取官网设置", request) { response, active ->
                val input = response.body.byteStream()
                val buffer = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(4096)
                while (active()) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    if (buffer.size() + count > 32_768) throw SpeechApiException("官网声音设置响应过大，当前设置已保留。")
                    buffer.write(bytes, 0, count)
                }
                val json = JsonParser.parseString(buffer.toString("UTF-8"))
                if (!json.isJsonObject) throw SpeechApiException("官网声音设置格式无效，当前设置已保留。")
                parseElevenVoiceSettings(json.asJsonObject, config.ttsModel)
            }
        } ?: throw SpeechApiException("读取官网设置超时，当前设置已保留。")
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

    suspend fun transcribe(pcm: ByteArray): String = transcribeDetailed(pcm).text

    suspend fun transcribeDetailed(pcm: ByteArray): SpeechTranscript {
        VoiceDiagnostics.record("asr_input_pcm_bytes_${pcm.size}_duration_ms_${pcm.size / 32}")
        return try { transcribeOnce(pcm) }
        catch (error: SpeechApiException) {
            if (!error.retryableRecognition) throw error
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            VoiceDiagnostics.record("asr_retry_wait_${error.retryDelayMillis}ms_attempt_2_of_2")
            kotlinx.coroutines.delay(error.retryDelayMillis)
            transcribeOnce(pcm)
        }
    }

    private suspend fun transcribeOnce(pcm: ByteArray): SpeechTranscript {
        config.validate()
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 960_000) { "录音数据无效。" }
        if (config.tokenPlanStt) {
            val request = Request.Builder().url(TokenPlanSpeech.endpoint(config.sttBaseUrl, true))
                .header("Authorization", "Bearer ${config.sttKey}").header("X-DashScope-SSE", "disable")
                .post(TokenPlanSpeech.recognitionRequest(config, wav(pcm)).toString().toRequestBody("application/json".toMediaType())).build()
            return execute("套餐识别", request) { response, active -> SpeechTranscript(TokenPlanSpeech.recognitionResult(response, active)) }
        }
        if (config.provider == SpeechConfig.QWEN_ASR) {
            val request = QwenAsrSpeech.request(config.sttBaseUrl, config.sttKey, config.sttModel, wav(pcm))
            return execute("识别", request) { response, active -> QwenAsrSpeech.result(readJson(response, active)) }
        }
        if (config.isBailian) return bailian.transcribeDetailed(pcm)
        if (config.provider == SpeechConfig.VOLCENGINE) return volcengineRecognition.transcribeDetailed(pcm)
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
            SpeechTranscript(text)
        }
    }

    /** A single explicitly configured extra upload. It never enters recognition retry logic. */
    suspend fun analyzeEmotion(source: EmotionSourceConfig, pcm: ByteArray): SpeechTranscript {
        require(source.validationErrors().isEmpty()) { "情绪补充配置不完整。" }
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 960_000) { "录音数据无效。" }
        val request = QwenAsrSpeech.request(source.baseUrl, source.key, source.model, wav(pcm))
        return execute("情绪识别", request) { response, active -> QwenAsrSpeech.result(readJson(response, active)) }
    }

    private fun readJson(response: Response, active: () -> Boolean): JsonObject {
        if (response.body.contentType()?.let { "${it.type}/${it.subtype}" } != "application/json")
            throw SpeechApiException("识别服务未返回 JSON，请核对接口。")
        val input = response.body.byteStream()
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (active()) {
            val count = input.read(chunk)
            if (count < 0) break
            if (buffer.size() + count > 65_536) throw SpeechApiException("识别响应过大。")
            buffer.write(chunk, 0, count)
        }
        val json = JsonParser.parseString(buffer.toString("UTF-8"))
        if (!json.isJsonObject) throw SpeechApiException("识别结果格式无效。")
        return json.asJsonObject
    }

    suspend fun speak(text: String, onPcm: (ByteArray) -> Unit) = speak(text, { rate ->
        require(rate == 24_000) { "播放器需要支持返回的音频采样率。" }
    }, onPcm)

    suspend fun speak(text: String, onSampleRate: (Int) -> Unit, onPcm: (ByteArray) -> Unit) {
        require(text.isNotBlank()) { "朗读内容为空。" }
        if (config.miniMaxHttpTts) {
            val request = MiniMaxHttpSpeech.request(config, text)
            val requestId = MiniMaxHttpSpeech.nextRequestId()
            VoiceDiagnostics.record("minimax_http_request_${requestId}_mode_${config.effectiveMiniMaxTransport}_chars_${text.length}_points_${text.codePointCount(0, text.length)}")
            execute("MiniMaxHTTP合成", request, minimaxHttp) { response, active ->
                MiniMaxHttpSpeech.result(config, response, active, onSampleRate, onPcm, requestId)
            }
            return
        }
        if (config.tokenPlanTts) {
            val request = Request.Builder().url(TokenPlanSpeech.endpoint(config.ttsBaseUrl, false))
                .header("Authorization", "Bearer ${config.ttsKey}").header("X-DashScope-SSE", "enable")
                .post(TokenPlanSpeech.synthesisRequest(config, text).toString().toRequestBody("application/json".toMediaType())).build()
            execute("套餐合成", request) { response, active -> TokenPlanSpeech.synthesisResult(response, active, onSampleRate, onPcm) }
            return
        }
        if (config.streamingTts) {
            val texts = kotlinx.coroutines.channels.Channel<String>(1)
            texts.trySend(text); texts.close()
            speakStream(texts, onPcm = onPcm); return
        }
        val moss = config.effectiveTtsProvider == SpeechConfig.MOSSLAND
        val fish = config.effectiveTtsProvider == SpeechConfig.FISH_AUDIO
        val elevenHttp = config.effectiveTtsProvider == SpeechConfig.ELEVENLABS
        if (elevenHttp && text.codePointCount(0, text.length) > if (config.ttsModel == "eleven_v4") 10_000 else 4000)
            throw SpeechApiException("ElevenLabs 单段过长，请分段朗读。")
        if (moss) VoiceDiagnostics.record("moss_request_chars_${text.length}")
        val json = if (fish) FishSpeech.request(config, text)
        else if (moss) MossSpeech.request(config, text) else if (elevenHttp) JsonObject().apply {
            addProperty("text", text); addProperty("model_id", config.ttsModel)
            config.elevenVoiceJson()?.let { add("voice_settings", it) }
        } else JsonObject().apply {
            addProperty("model", config.ttsModel)
            addProperty("voice", config.voice)
            addProperty("input", text)
            addProperty("response_format", "pcm")
        }
        val request = Request.Builder().url(if (fish) FishSpeech.endpoint(config.ttsBaseUrl)
            else if (elevenHttp) config.elevenEndpoint() else config.ttsEndpoint())
            .apply {
                if (elevenHttp) header("xi-api-key", config.ttsKey) else header("Authorization", "Bearer ${config.ttsKey}")
                if (fish) header("model", config.ttsModel)
            }
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        execute(if (fish) "Fish Audio 合成" else "合成", request) { response, active ->
            if (moss) {
                MossSpeech.consume(response, active, onSampleRate, onPcm)
                return@execute
            }
            val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (type !in setOf("application/octet-stream", "audio/pcm", "audio/x-pcm")) {
                throw SpeechApiException("合成服务未返回兼容的 PCM 音频。")
            }
            val input = response.body.byteStream()
            if (fish) onSampleRate(FishSpeech.SAMPLE_RATE)
            else if (elevenHttp) onSampleRate(24_000)
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
                    if (fish && total + even > FishSpeech.MAX_PCM_BYTES)
                        throw SpeechApiException("Fish Audio 音频超过单段播放上限。")
                    // Providers must honor response_format=pcm; no decoding or fallback is inferred.
                    onPcm(buffer.copyOf(even))
                    total += even
                    if (elevenHttp && total > 86_400_000) throw SpeechApiException("ElevenLabs 音频超过单轮播放上限。")
                }
            }
            if (active() && (carry != null || total == 0L)) throw SpeechApiException("合成音频为空或 PCM 数据不完整。")
            if (fish && active()) VoiceDiagnostics.record("fish_audio_complete_bytes_$total")
        }
    }

    private suspend fun <T> execute(stage: String, request: Request, transport: OkHttpClient = http,
        consume: (Response, () -> Boolean) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val startedAt = System.nanoTime()
            fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000
            val call = transport.newCall(request)
            // Recognition has no playback to drain: each attempt gets a 30-second budget.
            if (stage == "识别" || stage == "套餐识别") call.timeout().timeout(30, TimeUnit.SECONDS)
            val responseRef = AtomicReference<Response?>()
            continuation.invokeOnCancellation {
                VoiceDiagnostics.record("http_${stage}_cancelled_${elapsed()}ms")
                call.cancel(); responseRef.getAndSet(null)?.close()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        if (stage == "MiniMaxHTTP合成") VoiceDiagnostics.record("minimax_http_failed_phase_connect_category_${MiniMaxHttpSpeech.failureCategory(e)}")
                        val timeout = e is java.io.InterruptedIOException
                        VoiceDiagnostics.record("http_${stage}_${if (timeout) "timeout" else "network_error"}_${elapsed()}ms")
                        continuation.resumeWithException(SpeechApiException(if (timeout) "${stage}请求超时。" else "${stage}网络请求失败。",
                            retryableRecognition = e !is javax.net.ssl.SSLException && e !is java.net.ProtocolException))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    responseRef.set(response)
                    response.use {
                        try {
                            if (!continuation.isActive) return
                            VoiceDiagnostics.record("http_${stage}_status_${response.code}_${elapsed()}ms")
                            if (!response.isSuccessful) throw SpeechHttpFailure.fromResponse(stage, response,
                                listOf(config.sttKey, config.ttsKey, request.header("Authorization")?.removePrefix("Bearer ").orEmpty()))
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
open class SpeechApiException internal constructor(message: String,
    internal val retryableRecognition: Boolean = false,
    internal val retryDelayMillis: Long = 600) : IOException(message)

internal class NoSpeechRecognized : SpeechApiException("没有识别到文字，请重新说一句。")
