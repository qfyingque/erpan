package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

class VolcengineEmotionTest {
    private val noSockets = object : WebSocket.Factory {
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = error("no network")
    }
    private val config = SpeechConfig.openAiDefaults().copy(provider = "volcengine", ttsProvider = "openai",
        sttBaseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async", sttModel = "bigmodel")

    @Test fun optionalMetadataIsRequestedOnlyWhenChosen() {
        val ordinary = VolcengineRecognition(config, noSockets).parameters()["request"].asJsonObject
        assertFalse(ordinary.has("enable_emotion_detection"))
        val emotion = VolcengineRecognition(config, noSockets, emotionHints = true).parameters()["request"].asJsonObject
        assertTrue(emotion["enable_emotion_detection"].asBoolean)
        assertTrue(emotion["show_utterances"].asBoolean)
        val timing = VolcengineRecognition(config, noSockets, timingHints = true).parameters()["request"].asJsonObject
        assertFalse(timing.has("enable_emotion_detection"))
        assertTrue(timing["show_utterances"].asBoolean)
    }

    @Test fun onlyFinalMetadataIsUsedAndTextIsNotRebuiltFromSegments() {
        val json = JsonParser.parseString("""{"result":{"text":"完整修正后的最终文字","utterances":[
          {"text":"中间文字","definite":false,"additions":{"emotion":"angry"}},
          {"text":"短句","definite":true,"start_time":100,"end_time":1000,"additions":{"emotion":"surprise"}},
          {"definite":true,"additions":{"emotion":"private-instruction"}}]}}""").asJsonObject
        val result = VolcengineRecognitionProtocol.transcript(json)
        assertEquals("完整修正后的最终文字", result.text)
        assertTrue(result.forChat(true, true).contains("惊讶"))
        assertTrue(result.forChat(true, true).contains("0.90"))
        assertFalse(result.forChat(true, true).contains("愤怒"))
        assertFalse(result.forChat(true, true).contains("private-instruction"))
        assertFalse(result.forChat(true, true).contains("100%"))
    }

    @Test fun malformedAndAbsentOptionalMetadataKeepFinalWords() {
        for (utterances in listOf("null", "{}", "[1,null]", "[{\"additions\":[]}]") ) {
            val json = JsonParser.parseString("""{"result":{"text":"还要保留","utterances":$utterances}}""").asJsonObject
            val result = VolcengineRecognitionProtocol.transcript(json)
            assertEquals("还要保留", result.forChat(true, true))
        }
    }
}
