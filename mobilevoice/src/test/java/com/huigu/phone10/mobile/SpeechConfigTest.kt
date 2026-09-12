package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SpeechConfigTest {
    private fun configuredBailian() = SpeechConfig.bailianDefaults().copy(
        sttKey = "test-asr-key", ttsKey = "test-tts-key", voice = "test-voice")

    @Test fun previousSavedSettingsKeepOpenAiProviderAndEndpoints() {
        val previous = """{"speech":{"sttBaseUrl":"https://speech.invalid/v1","sttKey":"old-stt","sttModel":"old-asr","ttsBaseUrl":"https://speech.invalid/v1","ttsKey":"old-tts","ttsModel":"old-tts-model","voice":"old-voice"},"chatId":"chat-1","chatTitle":"Existing chat"}"""
        val settings = Gson().fromJson(previous, MobileSettings::class.java)
        assertFalse(settings.speech.isBailian)
        settings.speech.validate()
        assertEquals("https://speech.invalid/v1/audio/transcriptions", settings.speech.sttEndpoint().toString())
        assertEquals("old-tts", settings.speech.ttsKey)
        assertEquals("chat-1", settings.chatId)
    }

    @Test fun freshBailianDefaultsContainNoCredentialsOrGuessedVoice() {
        val config = SpeechConfig.bailianDefaults()
        assertTrue(config.isBailian)
        assertEquals("paraformer-realtime-v2", config.sttModel)
        assertEquals("cosyvoice-v3.5-plus", config.ttsModel)
        assertEquals("", config.sttKey)
        assertEquals("", config.ttsKey)
        assertEquals("", config.voice)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test fun acceptsAndDerivesPublicRegionAndWorkspaceWebSocketEndpoints() {
        val config = configuredBailian()
        config.validate()
        listOf("https://dashscope.aliyuncs.com", "https://dashscope.aliyuncs.com/", "https://dashscope.aliyuncs.com/api/v1", "https://dashscope.aliyuncs.com/api/v1/").forEach {
            assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference", config.bailianEndpoint(it))
        }
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", config.bailianEndpoint("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference"))
        assertEquals("wss://ws-example.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", config.bailianEndpoint("https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1"))
    }

    @Test fun rejectsUnsafeSchemesAuthorityMetadataAndUnexpectedPaths() {
        val config = configuredBailian()
        listOf("http://dashscope.aliyuncs.com/api/v1", "ws://dashscope.aliyuncs.com/api-ws/v1/inference",
            "https://name:pass@dashscope.aliyuncs.com/api/v1", "https://dashscope.aliyuncs.com/api/v1?key=secret",
            "https://dashscope.aliyuncs.com/api/v1#fragment", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "wss://dashscope.aliyuncs.com/", "wss://dashscope.aliyuncs.com/api-ws/v1/inference/extra",
            "https://dashscope.aliyuncs.com/api/../api/v1", "https://dashscope.aliyuncs.com/api/%76%31").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { config.bailianEndpoint(it) }
        }
    }

    @Test fun rejectsUnknownProviderAndHeaderInjectionWithoutLeakingKeys() {
        assertThrows(IllegalArgumentException::class.java) { configuredBailian().copy(provider = "unknown").validate() }
        val invalid = configuredBailian().copy(sttKey = "secret\r\nX: bad")
        val error = assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertFalse(invalid.toString().contains("secret"))
    }

    @Test fun providerRoundTripPreservesBailian() {
        val gson = Gson()
        val original = configuredBailian()
        assertEquals(original, gson.fromJson(gson.toJson(original), SpeechConfig::class.java))
    }
}
