package com.huigu.phone10.mobile

import com.google.gson.JsonObject

/** Optional per-profile overrides. Null preserves the provider's original defaults. */
data class MiniMaxVoiceSettings(val speed: Double? = null, val emotion: String? = null)
data class ElevenVoiceSettings(val stability: Double? = null, val similarityBoost: Double? = null)

/** Called by the draft owner when an asynchronous settings read finishes. */
internal fun MobileSettings.withReadElevenVoiceSettings(expected: SpeechConfig, fetched: ElevenVoiceSettings): MobileSettings? =
    if (speech != expected) null else copy(speech = speech.copy(elevenVoiceSettings = fetched))

// The existing dialogue WebSocket documents stability only. Do not add TTS-only keys there.
internal val SpeechConfig.elevenSimilaritySupported: Boolean
    get() = ttsModel in setOf("eleven_flash_v2_5", "eleven_v4")

internal fun miniMaxEmotions(model: String): List<Pair<String, String>> = buildList {
    addAll(listOf("happy" to "开心", "sad" to "悲伤", "angry" to "愤怒", "fearful" to "害怕",
        "disgusted" to "厌恶", "surprised" to "惊讶", "calm" to "平静"))
    if (model in setOf("speech-2.6-hd", "speech-2.6-turbo", "speech-2.8-hd", "speech-2.8-turbo")) add("fluent" to "生动")
    if (model in setOf("speech-2.6-hd", "speech-2.6-turbo")) add("whisper" to "低语")
}

internal fun SpeechConfig.voiceTuningErrors(): List<String> = buildList {
    if (effectiveTtsProvider == SpeechConfig.MINIMAX) miniMaxVoiceSettings?.let { settings ->
        settings.speed?.let { if (!it.isFinite() || it !in 0.5..2.0) add("MiniMax 语速：请选择 0.5～2 倍。") }
        settings.emotion?.let { if (miniMaxEmotions(ttsModel).none { choice -> choice.first == it })
            add("MiniMax 情绪：当前模型不支持这个选项，请选择自动或其他情绪。") }
    }
    if (effectiveTtsProvider == SpeechConfig.ELEVENLABS) elevenVoiceSettings?.let { settings ->
        settings.stability?.let {
            if (!it.isFinite() || it !in 0.0..1.0) add("ElevenLabs 稳定性：请选择 0～100%。")
            else if (ttsModel == "eleven_v3" && it !in listOf(0.0, 0.5, 1.0))
                add("ElevenLabs v3 稳定性：请选择灵动、自然或稳健。")
        }
        if (elevenSimilaritySupported) settings.similarityBoost?.let {
            if (!it.isFinite() || it !in 0.0..1.0) add("ElevenLabs 相似度：请选择 0～100%。")
        }
    }
}

internal fun SpeechConfig.miniMaxVoiceJson(): JsonObject = JsonObject().apply {
    addProperty("voice_id", voice)
    miniMaxVoiceSettings?.speed?.let { addProperty("speed", it) }
    miniMaxVoiceSettings?.emotion?.let { addProperty("emotion", it) }
}

internal fun SpeechConfig.elevenVoiceJson(): JsonObject? = elevenVoiceSettings?.let { settings ->
    JsonObject().apply {
        settings.stability?.let { addProperty("stability", it) }
        if (elevenSimilaritySupported) settings.similarityBoost?.let { addProperty("similarity_boost", it) }
    }.takeIf { it.size() > 0 }
}

/** Settings fetched from ELE are only an editing snapshot; this never writes back to ELE. */
internal fun parseElevenVoiceSettings(json: JsonObject, model: String): ElevenVoiceSettings {
    fun number(name: String): Double? {
        val value = json[name]?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber)
            throw SpeechApiException("官网声音设置格式无效，当前设置已保留。")
        val result = value.asDouble
        if (!result.isFinite() || result !in 0.0..1.0)
            throw SpeechApiException("官网声音设置超出范围，当前设置已保留。")
        return result
    }
    val stability = number("stability")
    val similarity = if (model in setOf("eleven_flash_v2_5", "eleven_v4")) number("similarity_boost") else null
    if (stability == null && similarity == null) throw SpeechApiException("官网未返回可用的声音设置，当前设置已保留。")
    if (model == "eleven_v3" && stability != null && stability !in listOf(0.0, 0.5, 1.0))
        throw SpeechApiException("官网稳定性不适用于 v3，请在耳畔选择灵动、自然或稳健。")
    return ElevenVoiceSettings(stability, similarity)
}
