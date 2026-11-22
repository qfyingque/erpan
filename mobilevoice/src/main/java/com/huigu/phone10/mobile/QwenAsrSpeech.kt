package com.huigu.phone10.mobile

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** Qwen-ASR's documented audio input and emotion annotations, without a general audio-chat prompt. */
internal object QwenAsrSpeech {
    const val BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val MODEL = "qwen3-asr-flash"
    private val models = Regex("qwen3-asr-flash(?:-\\d{4}-\\d{2}-\\d{2})?")

    fun validModel(model: String) = models.matches(model)
    fun endpoint(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
        val host = url?.host.orEmpty()
        val allowedHost = host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com") ||
            (host != TokenPlanSpeech.HOST && (host.endsWith(".cn-beijing.maas.aliyuncs.com") || host.endsWith(".ap-southeast-1.maas.aliyuncs.com")))
        require(value.length <= 4096 && '\r' !in value && '\n' !in value && url != null && url.isHttps &&
            url.port == 443 && allowedHost && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.encodedPath.trimEnd('/') in setOf(
                "", "/compatible-mode/v1", "/compatible-mode/v1/chat/completions")) {
            "Qwen ASR 请填写北京或新加坡百炼的 HTTPS 兼容模式地址，以 /compatible-mode/v1 结尾；Token Plan 套餐入口不适用。"
        }
        return url.newBuilder().encodedPath("/compatible-mode/v1/chat/completions").build()
    }

    fun request(base: String, key: String, model: String, wav: ByteArray): Request {
        require(validModel(model)) { "Qwen ASR 请使用 qwen3-asr-flash 或对应日期版本。" }
        val body = JsonObject().apply {
            addProperty("model", model); addProperty("stream", false)
            add("messages", JsonArray().apply { add(JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply { add(JsonObject().apply {
                    addProperty("type", "input_audio")
                    add("input_audio", JsonObject().apply {
                        addProperty("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav))
                    })
                }) })
            }) })
        }
        return Request.Builder().url(endpoint(base)).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun result(json: JsonObject): SpeechTranscript {
        val choices = json["choices"]?.takeIf { it.isJsonArray }?.asJsonArray
        val choice = choices?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw SpeechApiException("Qwen ASR 缺少最终识别结果。")
        if (choice["finish_reason"]?.takeIf { it.isJsonPrimitive }?.asString != "stop")
            throw SpeechApiException("Qwen ASR 未完整结束，当前文字尚未发送。")
        val message = choice["message"]?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw SpeechApiException("Qwen ASR 识别结果无效。")
        val text = message["content"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
            ?: throw SpeechApiException("Qwen ASR 缺少转写文字。")
        if (text.length > 20_000) throw SpeechApiException("Qwen ASR 转写过长。")
        if (text.isBlank()) throw NoSpeechRecognized()
        var result = SpeechTranscript(text)
        val annotations = message["annotations"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return result
        for (entry in annotations.take(16)) {
            if (!entry.isJsonObject) continue
            val info = entry.asJsonObject
            val type = info["type"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val emotion = info["emotion"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (type == "audio_info" && emotion in setOf("surprised", "neutral", "happy", "sad", "disgusted", "angry", "fearful"))
                result = result.withProviderEmotion(emotion!!, "Qwen ASR")
        }
        return result
    }
}
