package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Fish official HTTP text-in / streamed PCM-out. Playback and cancellation stay in CloudSpeech. */
internal object FishSpeech {
    const val BASE = "https://api.fish.audio/v1"
    const val DEFAULT_MODEL = "s2.1-pro-free"
    const val SAMPLE_RATE = 44_100
    const val MAX_PCM_BYTES = 158_760_000L // Thirty minutes of PCM16 mono; no whole-clip buffering.
    val models = listOf("s2.1-pro-free", "s2.1-pro", "s2-pro", "s1", "drama-3")

    fun endpoint(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
        require(value.length <= 4096 && '\r' !in value && '\n' !in value &&
            url != null && url.isHttps && url.host == "api.fish.audio" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath.trimEnd('/') in setOf("", "/v1", "/v1/tts")) {
            "Fish Audio 官方地址请填写 https://api.fish.audio/v1。"
        }
        return url.newBuilder().encodedPath("/v1/tts").build()
    }

    fun request(config: SpeechConfig, text: String) = JsonObject().apply {
        addProperty("text", text)
        addProperty("reference_id", config.voice)
        addProperty("format", "pcm")
        addProperty("sample_rate", SAMPLE_RATE)
        // The engine belongs in the model header, not in this JSON body.
        // Omitted tuning fields use Fish's defaults. Never select another model on failure.
    }
}
