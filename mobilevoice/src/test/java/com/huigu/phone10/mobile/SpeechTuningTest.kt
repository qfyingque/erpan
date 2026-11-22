package com.huigu.phone10.mobile

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

class SpeechTuningTest {
    private val gson = Gson()
    private fun config(provider: String, model: String) = SpeechConfig.bailianDefaults()
        .withTtsProvider(provider).copy(sttKey = "ASR-ONLY", ttsKey = "TTS-ONLY", ttsModel = model, voice = "voice123")

    private fun tuned(config: SpeechConfig, name: String, json: String): SpeechConfig =
        gson.fromJson(gson.toJsonTree(config).asJsonObject.apply { add(name, JsonParser.parseString(json)) }, SpeechConfig::class.java)

    private suspend fun frames(config: SpeechConfig): List<JsonObject> {
        val messages = LinkedBlockingQueue<JsonObject>()
        val server = MockWebServer()
        val mini = config.effectiveTtsProvider == SpeechConfig.MINIMAX
        val dialogue = config.elevenDialogueTts
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (mini) ws.send("""{"event":"connected_success"}""")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val j = JsonParser.parseString(text).asJsonObject
                messages.add(j)
                if (mini) when (j["event"].asString) {
                    "task_start" -> ws.send("""{"event":"task_started"}""")
                    "task_continue" -> ws.send("""{"event":"task_continued","data":{"audio":"0102"}}""")
                    "task_finish" -> ws.send("""{"event":"task_finished"}""")
                } else if ((dialogue && j.has("close_socket")) || (!dialogue && j["text"]?.asString == "")) {
                    ws.send(if (dialogue) """{"audio":"AQI=","is_final":true}""" else """{"audio":"AQI=","isFinal":true}""")
                }
            }
        }))
        server.start()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
                client.newWebSocket(request.newBuilder().url(server.url("/test")).build(), listener)
        }
        try {
            val input = Channel<String>(1).apply { trySend("这是一句调节测试。"); close() }
            withTimeout(5000) { CloudSpeech(config, client, factory).speakStream(input) {} }
            return messages.toList()
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun miniMaxSendsSpeedAndEmotionOnlyOnTaskStart() = runBlocking {
        val c = tuned(config(SpeechConfig.MINIMAX, "speech-2.8-turbo"), "miniMaxVoiceSettings", """{"speed":0.85,"emotion":"happy"}""")
        val sent = frames(c)
        val settings = sent.first().getAsJsonObject("voice_setting")
        assertEquals(JsonParser.parseString("0.85"), settings["speed"])
        assertEquals(JsonParser.parseString("\"happy\""), settings["emotion"])
        assertEquals("voice123", settings["voice_id"].asString)
        assertTrue(sent.drop(1).none { it.has("voice_setting") })
    }

    @Test fun unchangedMiniMaxStillLetsServerChooseEmotionAndSpeed() = runBlocking {
        val settings = frames(config(SpeechConfig.MINIMAX, "speech-2.8-turbo")).first().getAsJsonObject("voice_setting")
        assertEquals(setOf("voice_id"), settings.keySet())
    }

    @Test fun elevenFlashSendsControlsOnInitializationOnly() = runBlocking {
        val c = tuned(config(SpeechConfig.ELEVENLABS, "eleven_flash_v2_5"), "elevenVoiceSettings", """{"stability":0.7,"similarityBoost":0.8}""")
        val sent = frames(c)
        assertEquals(JsonParser.parseString("""{"stability":0.7,"similarity_boost":0.8}"""), sent.first()["voice_settings"])
        assertTrue(sent.drop(1).none { it.has("voice_settings") })
    }

    @Test fun elevenTurboSendsOnlyDocumentedStabilityBesideRegisteredVoice() = runBlocking {
        val c = tuned(config(SpeechConfig.ELEVENLABS, "eleven_v4_turbo"), "elevenVoiceSettings", """{"stability":0.5,"similarityBoost":0.8}""")
        val sent = frames(c)
        assertTrue(sent.first().has("voices"))
        assertEquals(JsonParser.parseString("""{"stability":0.5}"""), sent.first()["voice_settings"])
        assertTrue(sent.drop(1).none { it.has("voice_settings") })
    }

    @Test fun elevenHttpFiltersUnsupportedControlsByModel() = runBlocking {
        for (model in listOf("eleven_v4", "eleven_v3")) {
            var captured: JsonObject? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured = JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(byteArrayOf(1, 2).toResponseBody("audio/pcm".toMediaType())).build()
            }.build()
            try {
                val c = tuned(config(SpeechConfig.ELEVENLABS, model), "elevenVoiceSettings", """{"stability":0.5,"similarityBoost":0.8}""")
                CloudSpeech(c, client).speak("你好。") {}
                val expected = if (model == "eleven_v3") """{"stability":0.5}""" else """{"stability":0.5,"similarity_boost":0.8}"""
                assertEquals(JsonParser.parseString(expected), captured!!["voice_settings"])
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun oldElevenProfilesKeepInheritingCloudDefaults() = runBlocking {
        for (model in listOf("eleven_flash_v2_5", "eleven_v4_turbo"))
            assertFalse(frames(config(SpeechConfig.ELEVENLABS, model)).first().has("voice_settings"))
    }

    @Test fun profileRoundTripAndRoleSelectionKeepIndependentTuning() {
        val a = tuned(config(SpeechConfig.ELEVENLABS, "eleven_v4"), "elevenVoiceSettings", """{"stability":0.65,"similarityBoost":0.85}""")
        val b = tuned(config(SpeechConfig.MINIMAX, "speech-2.8-turbo"), "miniMaxVoiceSettings", """{"speed":1.2,"emotion":"sad"}""")
        val first = MobileSettings(a, chatId = "original", listenOnly = true).saveVoiceProfile("甲")
        val idA = first.profiles().single().id
        val both = first.bindRoleVoice("Sol", idA).copy(speech = b).saveVoiceProfile("乙")
        val restored = gson.fromJson(gson.toJson(both), MobileSettings::class.java)
        val jsonA = gson.toJsonTree(restored.selectVoiceProfile(idA).speech).asJsonObject
        assertEquals(JsonParser.parseString("""{"stability":0.65,"similarityBoost":0.85}"""), jsonA["elevenVoiceSettings"])
        assertEquals(JsonParser.parseString("""{"speed":1.2,"emotion":"sad"}"""), gson.toJsonTree(restored.speech).asJsonObject["miniMaxVoiceSettings"])
        assertEquals(a, restored.voiceForRole("Sol").speech)
        assertEquals(both.roleVoiceBindings, restored.roleVoiceBindings)
        assertEquals("original", restored.chatId)
    }

    @Test fun invalidSpeedAndUnsupportedEmotionAreRejectedBeforeNetwork() {
        val base = config(SpeechConfig.MINIMAX, "speech-2.8-turbo")
        for (json in listOf("""{"speed":0.1}""", """{"speed":2.1}""", """{"emotion":"whisper"}""", """{"emotion":"made-up"}""")) {
            assertFalse(json, tuned(base, "miniMaxVoiceSettings", json).validationErrors(false).isEmpty())
        }
    }

    @Test fun invalidElevenSettingsAreRejectedBeforeNetwork() {
        val base = config(SpeechConfig.ELEVENLABS, "eleven_v4")
        for (json in listOf("""{"stability":-0.1}""", """{"similarityBoost":1.1}"""))
            assertFalse(json, tuned(base, "elevenVoiceSettings", json).validationErrors(false).isEmpty())
        assertFalse(tuned(base.copy(ttsModel = "eleven_v3"), "elevenVoiceSettings", """{"stability":0.3}""").validationErrors(false).isEmpty())
    }

    @Test fun switchingServiceClearsOnlyTuningAndLeavesRecognitionUntouched() {
        val c = config(SpeechConfig.MINIMAX, "speech-2.6-hd").copy(miniMaxVoiceSettings = MiniMaxVoiceSettings(0.9, "whisper"))
        val other = c.withTtsProvider(SpeechConfig.ELEVENLABS)
        assertNull(other.miniMaxVoiceSettings)
        assertNull(other.elevenVoiceSettings)
        assertEquals(c.sttKey, other.sttKey)
        assertEquals(c.sttBaseUrl, other.sttBaseUrl)
        assertEquals(c.sttModel, other.sttModel)
        assertTrue(c.validationErrors(false).isEmpty())
    }

    @Test fun automaticEmotionAndResetDoNotSendAnAutoStringOrOldOverride() = runBlocking {
        val c = config(SpeechConfig.MINIMAX, "speech-2.8-hd").copy(miniMaxVoiceSettings = MiniMaxVoiceSettings(1.1))
        assertFalse(frames(c).first().getAsJsonObject("voice_setting").has("emotion"))
        assertEquals(setOf("voice_id"), frames(c.copy(miniMaxVoiceSettings = null)).first().getAsJsonObject("voice_setting").keySet())
    }

    @Test fun oldHttpProfilesSendNoVoiceOverridesAndOtherProvidersIgnoreStoredTuning() = runBlocking {
        var captured: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(byteArrayOf(1, 2).toResponseBody("audio/pcm".toMediaType())).build()
        }.build()
        try {
            for (model in listOf("eleven_v3", "eleven_v4")) {
                CloudSpeech(config(SpeechConfig.ELEVENLABS, model), client).speak("你好。") {}
                assertFalse(captured!!.has("voice_settings"))
            }
            val unrelated = SpeechConfig.openAiDefaults().copy(ttsKey = "test", elevenVoiceSettings = ElevenVoiceSettings(0.9, 0.8),
                miniMaxVoiceSettings = MiniMaxVoiceSettings(1.1, "happy"))
            CloudSpeech(unrelated, client).speak("你好。") {}
            assertEquals(setOf("model", "voice", "input", "response_format"), captured!!.keySet())
        } finally { client.dispatcher.executorService.shutdownNow() }
    }
}
