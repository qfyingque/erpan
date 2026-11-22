package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Response

/** Provider messages can echo speech or credentials: keep identifiers and a local reason only. */
internal object SpeechHttpFailure {
    fun fromResponse(stage: String, response: Response, secrets: List<String>): SpeechApiException {
        val body = try {
            JsonParser.parseString(response.peekBody(8192).string()).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) { null }
        val details = describe(body, secrets, response.header("x-request-id"))
        val waitHeader = response.header("Retry-After")
        val waitSeconds = waitHeader?.toLongOrNull()
        // Never retry earlier than a long/date-based Retry-After asks.
        val waitAllowed = waitHeader == null || (waitSeconds != null && waitSeconds in 0..3)
        val retry = response.code in setOf(408, 429, 500, 502, 503, 504) && waitAllowed
        val hint = when (response.code) {
            400, 422 -> "请求参数或录音不符合服务要求"
            401, 403 -> "请检查密钥、权限及接口是否匹配"
            402 -> "请检查服务余额或套餐权益"
            429 -> "服务请求过于频繁，请稍后再试"
            in 500..599 -> "语音服务暂时异常"
            else -> "语音请求未完成"
        }
        return SpeechApiException("${stage}服务返回 HTTP ${response.code}。$hint。$details",
            retryableRecognition = retry,
            retryDelayMillis = if (waitSeconds != null && waitSeconds in 0..3) maxOf(600, waitSeconds * 1000) else 600)
    }

    fun describe(body: JsonObject?, secrets: List<String> = emptyList(), headerId: String? = null): String {
        fun value(obj: JsonObject?, name: String): String? = obj?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        val nested = body?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        fun identifier(raw: String?): String? {
            if (raw == null || raw.length !in 1..128) return null
            if (secrets.any { it.isNotEmpty() && raw.contains(it) }) return null
            if (raw.startsWith("sk-", true)) return null
            return raw.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }
        }
        val code = identifier(value(nested, "code") ?: value(body, "code"))
        val id = identifier(value(body, "request_id") ?: value(nested, "request_id") ?: headerId)
        val message = (value(nested, "message") ?: value(body, "message")).orEmpty().take(8192).lowercase()
        val reason = when {
            ("duration" in message || "length" in message) && ("short" in message || "minimum" in message) -> "录音时长不足"
            ("duration" in message || "length" in message) && ("long" in message || "maximum" in message || "exceed" in message) -> "录音超过服务时长限制"
            "sample" in message && "rate" in message -> "采样率不符合服务要求"
            "format" in message || "decode" in message || "base64" in message -> "音频格式或编码不符合服务要求"
            "model" in message -> "模型或模型权限不符合服务要求"
            "quota" in message || "balance" in message -> "服务额度或余额限制"
            else -> null
        }
        return listOfNotNull(reason?.let { "原因：$it。" }, code?.let { "错误码：$it。" }, id?.let { "请求编号：$it。" }).joinToString("")
    }
}
