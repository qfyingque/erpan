package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MobileOptionsTest {
    @Test fun oldSettingsKeepProvidersAndDoNotEnableNewFeatures() {
        val old = """{"speech":{"provider":"bailian","sttBaseUrl":"wss://dashscope.aliyuncs.com/api-ws/v1/inference","sttKey":"asr","sttModel":"paraformer-realtime-v2","ttsBaseUrl":"wss://dashscope.aliyuncs.com/api-ws/v1/inference","ttsKey":"tts","ttsModel":"cosyvoice-v3.5-plus","voice":"v"},"chatId":"old","chatTitle":"Sol"}"""
        val settings = Gson().fromJson(old, MobileSettings::class.java)
        settings.speech.validate()
        assertEquals(SpeechConfig.BAILIAN, settings.speech.effectiveTtsProvider)
        assertFalse(settings.overlayEnabled)
        assertFalse(settings.smartEndpoint)
        assertFalse(settings.disableVoiceInterruption)
        assertEquals("old", settings.chatId)
    }

    @Test fun interruptionPreferenceSurvivesSettingsRoundTrip() {
        val gson = Gson()
        val settings = MobileSettings(SpeechConfig.bailianDefaults(), disableVoiceInterruption = true)
        val loaded = gson.fromJson(gson.toJson(settings), MobileSettings::class.java)
        assertTrue(loaded.disableVoiceInterruption)
    }

    @Test fun minimaxSelectionKeepsAsrAndNeverCopiesItsKey() {
        val original = SpeechConfig.bailianDefaults().copy(sttKey = "bailian-private", ttsKey = "bailian-tts")
        val next = original.withTtsProvider(SpeechConfig.MINIMAX)
        assertTrue(next.isBailian)
        assertEquals("bailian-private", next.sttKey)
        assertEquals("", next.ttsKey)
        assertEquals(SpeechConfig.MINIMAX, next.effectiveTtsProvider)
        next.copy(ttsKey = "minimax-own").validate()
        assertFalse(next.toString().contains("bailian-private"))
    }

    @Test fun minimaxAcceptsOnlyDocumentedTlsEndpointShape() {
        val c = SpeechConfig.bailianDefaults()
        assertEquals("wss://api.minimax.cn/ws/v1/t2a_v2_bidi", c.minimaxEndpoint("wss://api.minimax.cn/ws/v1/t2a_v2_bidi"))
        listOf("ws://api.minimax.cn/ws/v1/t2a_v2_bidi", "wss://key@api.minimax.cn/ws/v1/t2a_v2_bidi",
            "wss://api.minimax.cn/ws/v1/t2a_v2_bidi?key=x", "wss://api.minimax.cn/ws/v1/t2a_v2").forEach {
            assertThrows(IllegalArgumentException::class.java) { c.minimaxEndpoint(it) }
        }
    }
}
