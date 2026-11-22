package com.huigu.phone10.mobile

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.EOFException
import java.net.URI
import java.util.Base64

/** Token Plan's HTTP contracts; transport lifetime remains owned by CloudSpeech. */
internal object TokenPlanSpeech {
    const val HOST = "token-plan.cn-beijing.maas.aliyuncs.com"
    const val BASE = "https://$HOST"
    const val ASR_MODEL = "qwen-audio-3.0-asr-flash"
    const val TTS_MODEL = "qwen-audio-3.0-tts-plus"
    const val VOICE = "longanlufeng"
    private const val ASR_PATH = "/api/v1/services/aigc/multimodal-generation/generation"
    private const val TTS_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer"
    private const val MAX_EVENT_BYTES = 1_048_576

    fun matches(value: String): Boolean = try { URI(value.trim()).host.equals(HOST, ignoreCase = true) }
        catch (_: Exception) { false }

    fun endpoint(value: String, asr: Boolean): HttpUrl {
        val message = "Token Plan 请填写北京套餐 HTTPS 根地址或控制台 Base URL，不要附带密钥或查询参数。"
        val raw = value.trim()
        require(raw.length <= 4096 && '\r' !in raw && '\n' !in raw) { message }
        val uri = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException(message) }
        val path = if (asr) ASR_PATH else TTS_PATH
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.trimEnd('/') in setOf("", "/api/v1", "/compatible-mode/v1", path)) { message }
        val url = raw.toHttpUrlOrNull()
        require(url != null && url.host == HOST && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) { message }
        return url.newBuilder().encodedPath(path).build()
    }

    fun recognitionRequest(config: SpeechConfig, wav: ByteArray) = JsonObject().apply {
        addProperty("model", config.sttModel)
        add("input", JsonObject().apply {
            add("messages", JsonArray().apply { add(JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply { add(JsonObject().apply {
                    addProperty("type", "input_audio")
                    add("input_audio", JsonObject().apply {
                        addProperty("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav))
                    })
                }) })
            }) })
        })
        add("parameters", JsonObject().apply { addProperty("format", "wav"); addProperty("sample_rate", "16000") })
    }

    fun recognitionResult(response: Response, active: () -> Boolean): String {
        if (response.body.contentType()?.subtype != "json") throw SpeechApiException("套餐识别未返回 JSON，请核对接口地址。")
        val source = response.body.source()
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (active()) {
            val count = source.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > 65_536) throw SpeechApiException("套餐识别响应过大。")
            bytes.write(buffer, 0, count)
        }
        val body = JsonParser.parseString(bytes.toString("UTF-8")).asJsonObject
        rejectError(body)
        val value = body.getAsJsonObject("output")?.get("text")
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString)
            throw SpeechApiException("套餐识别响应缺少完整文字，请检查识别模型。")
        return value.asString.trim().ifEmpty { throw NoSpeechRecognized() }
    }

    fun synthesisRequest(config: SpeechConfig, text: String) = JsonObject().apply {
        require(text.isNotBlank() && text.length <= 20_000) { "朗读内容为空或过长。" }
        addProperty("model", config.ttsModel)
        add("input", JsonObject().apply {
            addProperty("text", text); addProperty("voice", config.voice)
            addProperty("format", "pcm"); addProperty("sample_rate", 24_000)
        })
    }

    fun synthesisResult(response: Response, active: () -> Boolean, onRate: (Int) -> Unit, onPcm: (ByteArray) -> Unit) {
        if (response.body.contentType()?.let { "${it.type}/${it.subtype}" } != "text/event-stream")
            throw SpeechApiException("套餐合成未返回流式音频，请核对模型、音色和套餐权限。")
        val source = response.body.source()
        val event = StringBuilder()
        var carry: Byte? = null
        var total = 0L
        var formatSent = false
        fun dispatch(): Boolean {
            if (event.isEmpty()) return false
            val body = JsonParser.parseString(event.toString()).asJsonObject
            event.setLength(0)
            rejectError(body)
            val output = body.getAsJsonObject("output") ?: throw SpeechApiException("套餐合成事件缺少输出。")
            val data = output.getAsJsonObject("audio")?.get("data")
            if (data != null && !data.isJsonNull) {
                check(data.isJsonPrimitive && data.asJsonPrimitive.isString)
                if (data.asString.isNotEmpty()) {
                    val chunk = Base64.getDecoder().decode(data.asString)
                    total += chunk.size
                    if (total > 28_800_000) throw SpeechApiException("套餐合成音频超过单段上限。")
                    val aligned = carry?.let { byteArrayOf(it) + chunk } ?: chunk
                    val even = aligned.size and -2
                    carry = if (even < aligned.size) aligned.last() else null
                    if (even > 0 && active()) {
                        if (!formatSent) { onRate(24_000); formatSent = true }
                        onPcm(aligned.copyOf(even))
                    }
                }
            }
            val reason = output.get("finish_reason")
            if (reason != null && !reason.isJsonNull && reason.asString.isNotEmpty()) {
                if (reason.asString != "stop") throw SpeechApiException("套餐合成未正常完成，请查看文字回复。")
                if (total == 0L || carry != null) throw SpeechApiException("套餐合成音频为空或不完整。")
                return true
            }
            return false
        }
        while (active()) {
            val line = try { source.readUtf8LineStrict(MAX_EVENT_BYTES.toLong()) }
            catch (_: EOFException) { throw SpeechApiException("套餐音频提前中断或事件过大，请重试本轮。") }
            if (!active()) return
            if (line.isEmpty()) {
                if (dispatch()) return
            } else if (line.startsWith("data:")) {
                val data = line.substring(5).removePrefix(" ")
                if (event.length + data.length + 1 > MAX_EVENT_BYTES) throw SpeechApiException("套餐语音事件过大。")
                if (event.isNotEmpty()) event.append('\n')
                event.append(data)
            }
        }
    }

    private fun rejectError(body: JsonObject) {
        val code = body.get("code")
        if (code != null && !code.isJsonNull && code.asString !in setOf("", "200"))
            throw SpeechApiException("Token Plan 请求失败，请核对套餐密钥、模型、音色及剩余额度。")
    }
}
