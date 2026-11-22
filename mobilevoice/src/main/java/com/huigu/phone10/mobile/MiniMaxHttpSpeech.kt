package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.EOFException
import java.net.URI

/** One text segment in, bounded SSE PCM out. Caller owns splitting; no retry or fallback. */
internal object MiniMaxHttpSpeech {
    private const val MAX_EVENT_BYTES = 4_194_304L
    private const val MAX_PCM_BYTES = 28_800_000L
    private val requests = java.util.concurrent.atomic.AtomicLong()
    fun nextRequestId(): Long = requests.incrementAndGet()

    // Fixed categories only: exception messages may contain the URL, Key or response text.
    fun failureCategory(error: Exception): String = when (error) {
        is kotlinx.coroutines.CancellationException -> "cancelled"
        is SpeechApiException -> "api_or_contract"
        is java.io.InterruptedIOException -> "timeout"
        is java.net.ProtocolException -> "protocol_io"
        is EOFException -> "unexpected_eof"
        is javax.net.ssl.SSLException -> "tls_io"
        is java.io.IOException -> "transport_io"
        is com.google.gson.JsonParseException -> "json_parse"
        is IllegalArgumentException -> "invalid_value"
        is IllegalStateException -> "invalid_structure"
        else -> "processing"
    }

    fun officialHttpUrl(wss: String): String {
        val uri = try { URI(wss.trim()) } catch (_: Exception) { return "" }
        return if (uri.scheme == "wss" && uri.host in setOf("api.minimax.cn", "api.minimaxi.com", "api.minimax.io") &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/ws/v1/t2a_v2_bidi") "https://${uri.host}/v1/t2a_v2" else ""
    }

    fun endpoint(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
        require(value.length <= 4096 && '\r' !in value && '\n' !in value && url != null && url.isHttps &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath.endsWith("/v1/t2a_v2")) {
            "MiniMax HTTP 接法须填写完整 HTTPS 地址，以 /v1/t2a_v2 结尾；自定义中转需单独填写，密钥不要放在网址里。"
        }
        return url
    }

    fun request(config: SpeechConfig, text: String): Request {
        if (text.isBlank() || text.length >= 10_000)
            throw SpeechApiException("MiniMax 每次提交需要1～9999个字符；超长回复可选择分段提交。")
        val json = JsonObject().apply {
            addProperty("model", config.ttsModel); addProperty("text", text); addProperty("stream", true)
            add("stream_options", JsonObject().apply { addProperty("exclude_aggregated_audio", true) })
            add("voice_setting", config.miniMaxVoiceJson())
            add("audio_setting", JsonObject().apply {
                addProperty("sample_rate", 24_000); addProperty("format", "pcm"); addProperty("channel", 1)
            })
        }
        return Request.Builder().url(endpoint(config.effectiveMiniMaxHttpUrl))
            .header("Authorization", "Bearer ${config.ttsKey}").header("Accept", "text/event-stream")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun result(config: SpeechConfig, response: Response, active: () -> Boolean,
        onRate: (Int) -> Unit, onPcm: (ByteArray) -> Unit, requestId: Long = 0) {
        var total = 0L
        var trace = "none"
        var events = 0
        var phase = "headers"
        val started = System.nanoTime()
        fun metrics() = "request_${requestId}_phase_${phase}_trace_${trace}_events_${events}_pcm_bytes_${total}_elapsed_ms_${(System.nanoTime() - started) / 1_000_000}"
        try {
        fun checkError(json: JsonObject) {
            val candidate = json["trace_id"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
            if (candidate.matches(Regex("[A-Za-z0-9_-]{1,128}")) && !candidate.startsWith("sk-", true) &&
                (config.ttsKey.isEmpty() || !candidate.contains(config.ttsKey))) trace = candidate
            val code = json.getAsJsonObject("base_resp")?.get("status_code")?.asInt ?: 0
            if (code != 0) {
                val status = json.getAsJsonObject("base_resp")?.get("status_msg")?.asString.orEmpty()
                val detail = when {
                    Regex("(?i)\\brpm\\b").containsMatchIn(status) -> "rpm_limit"
                    Regex("(?i)\\btpm\\b").containsMatchIn(status) -> "tpm_limit"
                    code == 1002 -> "rate_limit"
                    else -> "provider_error"
                }
                VoiceDiagnostics.record("minimax_http_error_code_${code}_${detail}_${metrics()}")
                throw SpeechApiException("MiniMax 合成失败（状态码 $code，$detail，trace=$trace）。")
            }
        }
        val source = response.body.source()
        val contentType = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
        if (contentType == "application/json") {
            phase = "read_error_body"
            if (source.request(65_537)) throw SpeechApiException("MiniMax 错误响应过大。")
            val body = source.readUtf8()
            phase = "json"
            checkError(JsonParser.parseString(body).asJsonObject)
            throw SpeechApiException("MiniMax 未返回流式音频，请核对 HTTP 合成地址。")
        }
        if (contentType != "text/event-stream") throw SpeechApiException("MiniMax 未返回 SSE 音频，请核对 HTTP 合成地址。")
        val event = StringBuilder()
        var eventBytes = 0L
        var carry: Byte? = null
        var rateSent = false
        fun dispatch(): Boolean {
            if (event.isEmpty()) return false
            phase = "json"
            events++
            if (event.toString().trim() == "[DONE]") throw SpeechApiException("MiniMax 音频缺少完整结束状态。")
            val json = JsonParser.parseString(event.toString()).asJsonObject
            event.setLength(0); eventBytes = 0
            checkError(json)
            val data = json.getAsJsonObject("data") ?: throw SpeechApiException("MiniMax 音频事件缺少数据。")
            val status = data["status"]?.asInt ?: throw SpeechApiException("MiniMax 音频事件缺少状态。")
            if (status !in 1..2) throw SpeechApiException("MiniMax 音频状态无效。")
            json.getAsJsonObject("extra_info")?.let { info ->
                if (info["audio_sample_rate"]?.asInt?.let { it != 24_000 } == true ||
                    info["audio_channel"]?.asInt?.let { it != 1 } == true ||
                    info["audio_format"]?.asString?.let { it != "pcm" } == true)
                    throw SpeechApiException("MiniMax 返回的音频格式与24k单声道PCM不一致。")
            }
            val hex = data["audio"]?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (status == 2) {
                // With exclude_aggregated_audio, the last frame is metadata only. A relay ignoring
                // that contract must not replay the whole clip or silently drop ambiguous audio.
                if (hex.isNotEmpty()) throw SpeechApiException("MiniMax 末包仍包含聚合音频，请检查接口是否支持排除全量尾包。")
                if (total == 0L || carry != null) throw SpeechApiException("MiniMax 音频为空或PCM不完整。")
                phase = "terminal"
                VoiceDiagnostics.record("minimax_http_complete_${metrics()}")
                return true
            }
            if (hex.isNotEmpty()) {
                phase = "hex"
                val bytes = MiniMaxSpeech.decodeHex(hex)
                total += bytes.size
                if (total > MAX_PCM_BYTES) throw SpeechApiException("MiniMax 音频超过单次10分钟上限。")
                val aligned = carry?.let { byteArrayOf(it) + bytes } ?: bytes
                val count = aligned.size and -2
                carry = if (count < aligned.size) aligned.last() else null
                if (count > 0 && active()) {
                    phase = "playback_callback"
                    if (!rateSent) { onRate(24_000); rateSent = true }
                    onPcm(aligned.copyOf(count))
                }
            }
            return false
        }
        while (active()) {
            phase = "read"
            if (source.exhausted()) {
                if (dispatch()) return
                phase = "missing_terminal"
                throw SpeechApiException("MiniMax 音频提前断流，未收到完整结束状态。")
            }
            val line = try { source.readUtf8LineStrict(MAX_EVENT_BYTES) }
            catch (_: EOFException) {
                if (source.buffer.size <= MAX_EVENT_BYTES && !source.request(source.buffer.size + 1)) source.readUtf8()
                else throw SpeechApiException("MiniMax 音频事件过大。")
            }
            if (!active()) return
            if (line.isEmpty()) {
                if (dispatch()) return
            } else if (line.startsWith("data:")) {
                val data = line.substring(5).removePrefix(" ")
                eventBytes += data.toByteArray(Charsets.UTF_8).size + 1L
                if (eventBytes > MAX_EVENT_BYTES) throw SpeechApiException("MiniMax 音频事件过大。")
                if (event.isNotEmpty()) event.append('\n')
                event.append(data)
            }
        }
        } catch (error: Exception) {
            if (!active() || error is kotlinx.coroutines.CancellationException) throw error
            val category = failureCategory(error)
            VoiceDiagnostics.record("minimax_http_failed_category_${category}_${metrics()}")
            if (error is SpeechApiException) throw error
            throw SpeechApiException("MiniMax 合成中断（$category，阶段 $phase，trace=$trace）。")
        }
    }
}
