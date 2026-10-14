package com.huigu.phone10.mobile

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

data class SpeechConfig(val sttBaseUrl: String, val sttKey: String, val sttModel: String,
    val ttsBaseUrl: String, val ttsKey: String, val ttsModel: String, val voice: String,
    val provider: String? = OPENAI, val ttsProvider: String? = null,
    val volcResource: String? = null, val volcAppId: String? = null, val voicePrompt: String? = null,
    val sttVolcResource: String? = null, val sttVolcAppId: String? = null,
    val mossSpeed: Double? = null, val qwenSplitGranularity: String? = null,
    val mossTextMode: String? = null) {
    // Gson leaves fields absent from older encrypted settings null.
    val isBailian: Boolean get() = provider == BAILIAN
    val effectiveTtsProvider: String get() = ttsProvider ?: provider ?: OPENAI
    val elevenDialogueTts: Boolean get() = effectiveTtsProvider == ELEVENLABS && ttsModel == "eleven_v4_turbo"
    val elevenHttpTts: Boolean get() = effectiveTtsProvider == ELEVENLABS && ttsModel in setOf("eleven_v3", "eleven_v4")
    val streamingTts: Boolean get() = effectiveTtsProvider in setOf(BAILIAN, MINIMAX, VOLCENGINE, QWEN_LOCAL) ||
        (effectiveTtsProvider == ELEVENLABS && ttsModel == "eleven_flash_v2_5") || elevenDialogueTts
    val sentenceHttpTts: Boolean get() = effectiveTtsProvider == MOSSLAND ||
        elevenHttpTts
    val effectiveVolcResource: String get() = volcResource ?: "seed-icl-2.0"
    val effectiveSttVolcResource: String get() = sttVolcResource ?: "volc.seedasr.sauc.duration"
    val effectiveMossSpeed: Double get() = mossSpeed ?: 1.0
    val effectiveMossTextMode: String get() = mossTextMode ?: "fast"
    val coherentTts: Boolean get() = effectiveTtsProvider == MOSSLAND && effectiveMossTextMode == "coherent"
    val effectiveQwenSplitGranularity: String get() = qwenSplitGranularity ?: "sentence"
    val wholeReplyTts: Boolean get() = (effectiveTtsProvider == QWEN_LOCAL && effectiveQwenSplitGranularity == "none") ||
        (effectiveTtsProvider == MOSSLAND && effectiveMossTextMode == "whole")
    val clientSegmentedTts: Boolean get() = effectiveTtsProvider == QWEN_LOCAL && effectiveQwenSplitGranularity == "client_segments"
    val supportsVoicePrompt: Boolean get() = effectiveVolcResource == "seed-tts-2.0" ||
        (effectiveVolcResource == "seed-icl-2.0" && ttsModel == "seed-tts-2.0-expressive")

    fun withTtsProvider(next: String): SpeechConfig {
        if (next == effectiveTtsProvider) return this
        val defaults = when (next) {
            BAILIAN -> bailianDefaults()
            OPENAI -> openAiDefaults()
            MINIMAX -> copy(ttsBaseUrl = "wss://api.minimax.cn/ws/v1/t2a_v2_bidi",
                ttsKey = "", ttsModel = "speech-2.8-turbo", voice = "male-qn-qingse")
            MOSSLAND -> copy(ttsBaseUrl = "https://api.mosi.cn/v1",
                ttsKey = "", ttsModel = "moss-tts-1.5-flash", voice = "")
            VOLCENGINE -> copy(ttsBaseUrl = "wss://openspeech.bytedance.com/api/v3/tts/bidirection",
                ttsKey = "", ttsModel = "seed-tts-2.0-expressive", voice = "")
            QWEN_LOCAL -> copy(ttsBaseUrl = "", ttsKey = "", ttsModel = "qwen-a-clone", voice = "xiaoying-a")
            ELEVENLABS -> copy(ttsBaseUrl = "https://api.elevenlabs.io/v1", ttsKey = "",
                ttsModel = "eleven_flash_v2_5", voice = "")
            else -> throw IllegalArgumentException("请选择支持的合成服务。")
        }
        return copy(ttsProvider = next, ttsBaseUrl = defaults.ttsBaseUrl, ttsKey = defaults.ttsKey,
            ttsModel = defaults.ttsModel, voice = defaults.voice, qwenSplitGranularity = null, mossTextMode = null)
    }

    fun withSttProvider(next: String, restored: SpeechConfig? = null): SpeechConfig {
        require(next in STT_PROVIDERS) { "请选择支持的识别服务。" }
        val defaults = restored ?: when (next) {
            BAILIAN -> bailianDefaults()
            OPENAI -> openAiDefaults()
            MINIMAX -> copy(sttBaseUrl = "https://api.minimaxi.com/v1", sttKey = "", sttModel = "asr-1.0")
            MOSSLAND -> copy(sttBaseUrl = "https://api.mosi.cn/v1", sttKey = "", sttModel = "moss-transcribe-1.0")
            else -> copy(sttBaseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                sttKey = "", sttModel = "bigmodel", sttVolcResource = null, sttVolcAppId = null)
        }
        // Freeze legacy implicit TTS provider before changing recognition provider.
        return copy(provider = next, ttsProvider = effectiveTtsProvider, sttBaseUrl = defaults.sttBaseUrl,
            sttKey = defaults.sttKey, sttModel = defaults.sttModel,
            sttVolcResource = defaults.sttVolcResource, sttVolcAppId = defaults.sttVolcAppId)
    }

    fun validate(includeRecognition: Boolean = true) {
        val errors = validationErrors(includeRecognition)
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }

    fun validationErrors(includeRecognition: Boolean = true): List<String> = buildList {
        if (includeRecognition && provider != null && provider !in STT_PROVIDERS) add("识别服务商：请选择支持的服务。")
        fun address(label: String, value: String, validate: () -> Unit) {
            if (value.isBlank()) add("$label：请填写地址。")
            else try { validate() } catch (invalid: IllegalArgumentException) {
                add("$label：${invalid.message}")
            }
        }
        if (includeRecognition) address("识别接口地址", sttBaseUrl) {
            when (provider) {
                BAILIAN -> bailianEndpoint(sttBaseUrl)
                VOLCENGINE -> volcengineRecognitionEndpoint(sttBaseUrl)
                else -> base(sttBaseUrl)
            }
        }
        address("合成接口地址", ttsBaseUrl) {
            when (effectiveTtsProvider) {
                BAILIAN -> bailianEndpoint(ttsBaseUrl)
                MINIMAX -> minimaxEndpoint(ttsBaseUrl)
                VOLCENGINE -> volcengineEndpoint(ttsBaseUrl)
                QWEN_LOCAL -> qwenEndpoint(ttsBaseUrl)
                ELEVENLABS -> elevenEndpoint()
                OPENAI, MOSSLAND -> base(ttsBaseUrl)
                else -> throw IllegalArgumentException("请选择支持的合成服务。")
            }
        }
        (if (includeRecognition) listOf("识别 API Key" to sttKey, "识别模型" to sttModel) else emptyList()).plus(listOf("合成 API Key" to ttsKey,
            "合成模型" to ttsModel, "音色 ID" to voice)).forEach { (label, value) ->
            if (value.isBlank()) add("$label：尚未填写。")
            else if (value.length > 4096 || '\r' in value || '\n' in value ||
                (label.endsWith("Key") && value.any { it.code !in 33..126 }))
                add("$label：格式不正确，请重新复制完整内容。")
        }
        if (effectiveTtsProvider == QWEN_LOCAL) {
            val namedId = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
            if (!namedId.matches(ttsModel)) add("合成模型：请填写电脑语音服务提供的模型编号。")
            if (!namedId.matches(voice)) add("音色 ID：请填写电脑语音服务提供的音色编号，不是文件路径。")
            if (effectiveQwenSplitGranularity !in setOf("sentence", "none", "client_segments")) add("本机语音生成方式：请选择支持的生成方式。")
            // Named voice capabilities belong to the local server, not to a fixed client alias.
        }
        if (effectiveTtsProvider == ELEVENLABS) {
            if (ttsModel !in setOf("eleven_flash_v2_5", "eleven_v3", "eleven_v4", "eleven_v4_turbo"))
                add("ElevenLabs 模型：请选择 Flash v2.5、v3、v4 或 v4 Turbo。")
            if (!Regex("[A-Za-z0-9_-]{1,128}").matches(voice)) add("ElevenLabs 音色：请填写平台提供的 Voice ID，不是名称或网址。")
        }
        if (effectiveTtsProvider == MOSSLAND && effectiveMossTextMode !in setOf("fast", "coherent", "whole"))
            add("Moss 朗读方式：请选择支持的模式。")
        if (effectiveTtsProvider == MOSSLAND && ttsModel.isNotBlank() && ttsModel != "moss-tts-1.5-flash")
            add("合成模型：Mossland 流式合成请选择 moss-tts-1.5-flash。")
        if (effectiveTtsProvider == MOSSLAND && (!effectiveMossSpeed.isFinite() || effectiveMossSpeed !in 0.25..4.0))
            add("Moss 语速：请选择 0.25～4 倍。")
        if (includeRecognition && provider == MINIMAX && sttModel.isNotBlank() && sttModel != "asr-1.0")
            add("识别模型：MiniMax 请选择 asr-1.0。")
        if (includeRecognition && provider == MOSSLAND && sttModel.isNotBlank() && sttModel !in MOSS_STT_MODELS)
            add("识别模型：请选择 Moss 普通转写或多说话人转写模型。")
        if (includeRecognition && provider == VOLCENGINE) {
            if (sttModel.isNotBlank() && sttModel != "bigmodel") add("识别模型：火山流式识别使用 bigmodel。")
            if (effectiveSttVolcResource !in VOLC_STT_RESOURCES) add("火山识别类型：请选择支持的识别版本。")
            if (sttVolcAppId.orEmpty().any { it !in '0'..'9' } || sttVolcAppId.orEmpty().length > 64)
                add("识别旧版 App ID：请填写数字编号；新版 API Key 无需此项。")
        }
        if (effectiveTtsProvider == VOLCENGINE) {
            if (effectiveVolcResource !in VOLC_RESOURCES) add("火山模型类型：请选择支持的合成或复刻版本。")
            if (effectiveVolcResource == "seed-icl-2.0" && ttsModel !in setOf("seed-tts-2.0-standard", "seed-tts-2.0-expressive"))
                add("复刻版本：请选择标准版或表现力增强版。")
            if (volcAppId.orEmpty().any { it !in '0'..'9' } || volcAppId.orEmpty().length > 64)
                add("旧版 App ID：请填写数字编号；新版 API Key 无需此项。")
            if (voicePrompt.orEmpty().length > 2000) add("语气描述：请控制在 2000 字以内。")
        }
    }

    override fun toString(): String = "SpeechConfig(credentials=redacted)"

    internal fun sttEndpoint() = if (provider == MINIMAX) base(sttBaseUrl).newBuilder()
        .encodedPath(base(sttBaseUrl).encodedPath.trimEnd('/') + "/speech_to_text").build()
        else endpoint(sttBaseUrl, "transcriptions")
    internal fun ttsEndpoint() = endpoint(ttsBaseUrl, "speech")

    internal fun elevenEndpoint(): HttpUrl {
        val url = base(ttsBaseUrl)
        require(url.host == "api.elevenlabs.io" && url.port == 443 && url.encodedPath.trimEnd('/') == "/v1") {
            "ElevenLabs 官方地址请填写 https://api.elevenlabs.io/v1。"
        }
        val route = if (elevenDialogueTts) url.newBuilder().encodedPath("/v1/text-to-dialogue/stream-input")
            else url.newBuilder().encodedPath("/v1/text-to-speech").addPathSegment(voice)
                .addPathSegment(if (elevenHttpTts) "stream" else "stream-input")
        return route
            .addQueryParameter("output_format", "pcm_24000").apply {
                if (!elevenHttpTts) {
                    addQueryParameter("model_id", ttsModel)
                }
                if (ttsModel == "eleven_flash_v2_5") {
                    addQueryParameter("inactivity_timeout", "180")
                    addQueryParameter("auto_mode", "true")
                }
            }.build()
    }

    internal fun qwenEndpoint(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { null }
        require(value.length <= 4096 && uri != null && uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/v1/audio/speech/stream" && uri.port in -1..65535 && uri.port != 0) {
            "本机语音 须填写完整 WSS 地址，以 /v1/audio/speech/stream 结尾；令牌填在密钥框。"
        }
        return uri.toString()
    }

    internal fun minimaxEndpoint(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { null }
        require(uri != null && uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/ws/v1/t2a_v2_bidi") { "MiniMax 须填写完整 WSS 双向流式地址，且不含账号或查询参数。" }
        return uri.toString()
    }

    internal fun volcengineEndpoint(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { null }
        require(uri != null && uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == "/api/v3/tts/bidirection") {
            "火山须填写完整的 v3 WebSocket 双向流式地址。"
        }
        return uri.toString()
    }

    internal fun volcengineRecognitionEndpoint(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { null }
        require(uri != null && uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf(
                "/api/v3/sauc/bigmodel_async", "/api/v3/sauc/bigmodel", "/api/v3/sauc/bigmodel_nostream")) {
            "火山识别须填写完整的 v3 流式识别 WSS 地址。"
        }
        return uri.toString()
    }

    internal fun bailianEndpoint(value: String): String {
        val message = "百炼地址须为 WSS 推理地址，或 HTTPS 的根地址、/api/v1 地址，且不含账号、查询参数或片段。"
        val text = value.trim()
        require(text.length <= 4096 && '\r' !in text && '\n' !in text) { message }
        val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException(message) }
        val scheme = uri.scheme.orEmpty().lowercase()
        require(scheme == "wss" || scheme == "https") { message }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { message }
        require(if (scheme == "wss") uri.rawPath == "/api-ws/v1/inference"
            else uri.rawPath in listOf("", "/", "/api/v1", "/api/v1/")) { message }
        val httpText = "https" + text.substring(text.indexOf(':'))
        val url = httpText.toHttpUrlOrNull()
        require(url != null && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { message }
        return url.newBuilder().encodedPath("/api-ws/v1/inference").build().toString().replaceFirst("https://", "wss://")
    }

    private fun endpoint(value: String, action: String): HttpUrl = base(value).newBuilder()
        .encodedPath(base(value).encodedPath.trimEnd('/') + "/audio/" + action).build()

    private fun base(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "语音地址须为 HTTPS 基址，且不含账号、查询参数或片段。" }
        return url
    }

    companion object {
        const val OPENAI = "openai"
        const val BAILIAN = "bailian"
        const val MINIMAX = "minimax"
        const val MOSSLAND = "mossland"
        const val VOLCENGINE = "volcengine"
        const val QWEN_LOCAL = "qwen-local"
        const val ELEVENLABS = "elevenlabs"
        val STT_PROVIDERS = setOf(BAILIAN, OPENAI, VOLCENGINE, MINIMAX, MOSSLAND)
        val MOSS_STT_MODELS = setOf("moss-transcribe-1.0", "moss-transcribe-diarize-pro")
        val VOLC_STT_RESOURCES = setOf("volc.seedasr.sauc.duration", "volc.seedasr.sauc.concurrent",
            "volc.bigasr.sauc.duration", "volc.bigasr.sauc.concurrent")
        val VOLC_RESOURCES = setOf("seed-tts-1.0", "seed-tts-1.0-concurr", "seed-tts-2.0",
            "seed-icl-1.0", "seed-icl-1.0-concurr", "seed-icl-2.0")

        fun bailianDefaults() = SpeechConfig(
            sttBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference", sttKey = "", sttModel = "paraformer-realtime-v2",
            ttsBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference", ttsKey = "", ttsModel = "cosyvoice-v3.5-plus",
            voice = "", provider = BAILIAN)

        fun openAiDefaults() = SpeechConfig(
            sttBaseUrl = "https://api.openai.com/v1", sttKey = "", sttModel = "gpt-4o-mini-transcribe",
            ttsBaseUrl = "https://api.openai.com/v1", ttsKey = "", ttsModel = "gpt-4o-mini-tts", voice = "coral",
            provider = OPENAI)
    }
}
