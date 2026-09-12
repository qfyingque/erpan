package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import java.util.Locale

/** Per-utterance provider observations, kept separate from the user's spoken words. */
class SpeechTranscript(
    val text: String,
    private val spans: List<Pair<Long, Long>> = emptyList(),
    private val emotions: Map<String, Double> = emptyMap(),
) {
    fun forChat(includeVoiceHints: Boolean = ENABLE_VOICE_HINTS): String {
        val spoken = text.trim()
        if (!includeVoiceHints || spoken.isEmpty()) return spoken
        val hints = mutableListOf<String>()
        val ordered = spans.sortedBy { it.first }
        if (ordered.isNotEmpty()) {
            val duration = ordered.maxOf { it.second } - ordered.first().first
            if (duration > 0) {
                hints += "语音时间跨度约 ${seconds(duration)} 秒（含句内停顿）"
                val han = spoken.count { it in '\u3400'..'\u9fff' }
                val letters = spoken.count { it.isLetterOrDigit() }
                if (han >= 4 && han >= letters * 0.8 && duration >= 500) {
                    hints += "转写汉字密度约 ${String.format(Locale.ROOT, "%.1f", han * 1000.0 / duration)} 字/秒（含停顿，非音节语速）"
                }
            }
            var previousEnd = ordered.first().second
            var longestGap = 0L
            for ((start, end) in ordered.drop(1)) {
                longestGap = maxOf(longestGap, start - previousEnd)
                previousEnd = maxOf(previousEnd, end)
            }
            if (longestGap >= 200) hints += "时间戳所示最长间隔约 ${seconds(longestGap)} 秒"
        }
        for ((tag, confidence) in emotions) {
            val name = emotionNames[tag] ?: continue
            hints += "句段疑似${name}情感（服务商置信度 ${String.format(Locale.ROOT, "%.0f", confidence * 100)}%）"
        }
        if (hints.isEmpty()) return spoken
        return "$spoken\n\n[耳畔声音线索：自动估计，仅供参考，不代表用户自述或确定的心理状态。${hints.joinToString("；")}。]"
    }

    companion object {
        // 接好情绪识别后可手动改为 true 并重新构建；普通界面不提供此开关。
        const val ENABLE_VOICE_HINTS = false

        private val emotionNames = mapOf("positive" to "正面", "negative" to "负面", "neutral" to "中性")
        private fun seconds(milliseconds: Long) = String.format(Locale.ROOT, "%.2f", milliseconds / 1000.0)

        fun combine(parts: Collection<SpeechTranscript>): SpeechTranscript {
            val emotions = mutableMapOf<String, Double>()
            for (part in parts) for ((tag, confidence) in part.emotions) {
                emotions[tag] = maxOf(emotions[tag] ?: 0.0, confidence)
            }
            return SpeechTranscript(parts.joinToString("") { it.text }.trim(),
                parts.flatMap { it.spans }.take(1024), emotions)
        }

        /** Optional fields are advisory: malformed metadata must never discard a valid transcript. */
        fun fromBailian(text: String, sentences: List<JsonObject>): SpeechTranscript {
            val spans = mutableListOf<Pair<Long, Long>>()
            val emotions = mutableMapOf<String, Double>()
            for (sentence in sentences.take(1024)) {
                val words = sentence["words"]?.takeIf { it.isJsonArray }?.asJsonArray
                val wordSpans = words?.take(512)?.mapNotNull { word ->
                    if (word.isJsonObject) span(word.asJsonObject) else null
                }.orEmpty()
                // Paraformer's documented final response can have a null sentence end_time.
                spans += if (wordSpans.isNotEmpty()) wordSpans else listOfNotNull(span(sentence))
                val tag = sentence["emo_tag"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                val confidence = number(sentence, "emo_confidence")
                if (tag in emotionNames && confidence != null && confidence in 0.6..1.0) {
                    emotions[tag!!] = maxOf(emotions[tag] ?: 0.0, confidence)
                }
            }
            return SpeechTranscript(text.trim(), spans.take(1024), emotions)
        }

        private fun span(value: JsonObject): Pair<Long, Long>? {
            val start = number(value, "begin_time") ?: return null
            val end = number(value, "end_time") ?: return null
            if (start < 0 || end <= start || end > 30_000 || start % 1 != 0.0 || end % 1 != 0.0) return null
            return start.toLong() to end.toLong()
        }

        private fun number(value: JsonObject, key: String): Double? {
            val item = value[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
            return item.asString.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }
}
