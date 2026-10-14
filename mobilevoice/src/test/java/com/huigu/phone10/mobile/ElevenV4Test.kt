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
import java.util.concurrent.atomic.AtomicBoolean

class ElevenV4Test {
    private fun config(model: String = "eleven_v4_turbo", voice: String = "voice123") =
        SpeechConfig.bailianDefaults().copy(sttKey = "ASR-ONLY", ttsProvider = "elevenlabs",
            ttsBaseUrl = "https://api.elevenlabs.io/v1", ttsKey = "ELEVEN-ONLY", ttsModel = model, voice = voice)

    @Test fun v4ProfilesRouteCorrectlyAndKeepGroupBindingsWhenSaved() {
        assertTrue(config().validationErrors(false).isEmpty())
        assertTrue(config("eleven_v4").validationErrors(false).isEmpty())
        assertTrue(config().streamingTts)
        assertFalse(config().sentenceHttpTts)
        assertFalse(config("eleven_v4").streamingTts)
        assertTrue(config("eleven_v4").sentenceHttpTts)
        val saved = MobileSettings(config(), chatId = "group").saveVoiceProfile("Turbo")
        val bound = saved.bindRoleVoice("Astra", saved.profiles().single().id)
            .copy(speech = config("eleven_v4", "voice456")).saveVoiceProfile("V4")
        val restored = Gson().fromJson(Gson().toJson(bound), MobileSettings::class.java)
        assertEquals(bound, restored)
        assertEquals(saved.speech, restored.selectVoiceProfile(saved.profiles().single().id).speech)
        assertEquals(bound.roleVoiceBindings, restored.roleVoiceBindings)
    }

    @Test fun v4HttpUsesSpeechEndpointAndAcceptsMoreThanV3Limit() = runBlocking {
        val text = "你".repeat(5000)
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val r = chain.request()
            assertEquals("https://api.elevenlabs.io/v1/text-to-speech/voice123/stream?output_format=pcm_24000", r.url.toString())
            assertEquals("ELEVEN-ONLY", r.header("xi-api-key"))
            val body = JsonParser.parseString(Buffer().also { r.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals("eleven_v4", body["model_id"].asString)
            assertEquals(text, body["text"].asString)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(byteArrayOf(1,2,3,4).toResponseBody("audio/pcm".toMediaType())).build()
        }.build()
        try {
            var rate = 0
            val pcm = mutableListOf<Byte>()
            CloudSpeech(config("eleven_v4"), client).speak(text, { rate = it }) { pcm.addAll(it.toList()) }
            assertEquals(24000, rate)
            assertEquals(listOf<Byte>(1,2,3,4), pcm)
            try { CloudSpeech(config("eleven_v4"), client).speak("你".repeat(10001)) {}; fail("over limit") }
            catch (_: SpeechApiException) { }
            assertEquals(1, calls)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    private class SocketFixture(val respond: (WebSocket, JsonObject) -> Unit) : AutoCloseable {
        val messages = LinkedBlockingQueue<JsonObject>()
        val requests = LinkedBlockingQueue<Request>()
        val cancelled = AtomicBoolean(false)
        val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                requests.add(request)
                val ws = client.newWebSocket(request.newBuilder().url(server.url("/ws")).build(), listener)
                return object : WebSocket by ws {
                    override fun cancel() { cancelled.set(true); ws.cancel() }
                }
            }
        }
        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JsonParser.parseString(text).asJsonObject
                    messages.add(json); respond(webSocket, json)
                }
            }))
            server.start()
        }
        override fun close() {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun turboFlushesFirstClauseBeforeInputEndsAndDoesNotTreatTurnEndAsSessionEnd() = runBlocking {
        SocketFixture { ws, msg ->
            if (msg["flush"]?.asBoolean == true) {
                ws.send("""{"audio":"AQID"}""")
                ws.send("""{"audio":"BA=="}""")
                ws.send("""{"is_final_audio_for_turn":true}""")
            }
            if (msg["close_socket"]?.asBoolean == true) ws.send("""{"audio":"BQY=","is_final":true}""")
        }.use { f ->
            val input = Channel<String>(8)
            val pcm = Channel<ByteArray>(8)
            input.send("你好，")
            val job = async { CloudSpeech(config(), f.client, f.factory).speakStream(input) { pcm.trySend(it) } }
            assertArrayEquals(byteArrayOf(1,2), withTimeout(5000) { pcm.receive() })
            assertArrayEquals(byteArrayOf(3,4), withTimeout(5000) { pcm.receive() })
            assertFalse(job.isCompleted)
            input.send("small"); input.send("talk"); input.send(" 继续😀")
            input.close(); withTimeout(5000) { job.await() }
            assertArrayEquals(byteArrayOf(5,6), pcm.receive())
            val r = f.requests.single()
            assertEquals("/v1/text-to-dialogue/stream-input", r.url.encodedPath)
            assertEquals("eleven_v4_turbo", r.url.queryParameter("model_id"))
            assertEquals("pcm_24000", r.url.queryParameter("output_format"))
            assertNull(r.url.queryParameter("auto_mode"))
            assertNull(r.url.queryParameter("inactivity_timeout"))
            assertEquals("ELEVEN-ONLY", r.header("xi-api-key"))
            assertEquals(listOf("voice123"), f.messages.first()["voices"].asJsonArray.map { it.asString })
            val entries = f.messages.filter { it.has("inputs") }.flatMap { it["inputs"].asJsonArray.map { e -> e.asJsonObject } }
            assertEquals("你好，smalltalk 继续😀", entries.joinToString("") { it["text"].asString })
            assertTrue(entries.all { it["voice_id"].asString == "voice123" && it["new_turn"]?.asBoolean != true })
            assertEquals(1, f.messages.count { it["flush"]?.asBoolean == true })
            assertEquals(1, f.messages.count { it["close_socket"]?.asBoolean == true })
            assertFalse(f.messages.toString().contains("ELEVEN-ONLY"))
        }
    }

    @Test fun turboToolPauseUsesKeepAliveAndCancelNeverFlushesOrReplaysOldReply() = runBlocking {
        SocketFixture { _, _ -> }.use { f ->
            val input = Channel<String>(2).apply { trySend("请稍等。") }
            val job = launch { ElevenLabsSpeech(config(), f.factory, heartbeatMillis = 30).speakStream(input) {} }
            withTimeout(5000) { while (f.messages.none { it["keep_alive"]?.asBoolean == true }) delay(10) }
            assertFalse(job.isCompleted)
            job.cancelAndJoin()
            assertTrue(f.cancelled.get())
            assertEquals(1, f.requests.size)
            assertFalse(f.messages.any { it.has("close_socket") || it.has("text") })
            input.cancel()
        }
    }

    @Test fun turboRejectsErrorsOddTailEmptyAndPrematureCloseWithoutRetry() = runBlocking {
        for (payload in listOf("""{"audio":"AQ==","is_final":true}""", """{"is_final":true}""",
            """{"error":"bad_request","message":"SECRET","code":1008}""", """{"audio":"AQI=","isFinal":true}""")) {
            SocketFixture { ws, msg ->
                if (msg.has("close_socket")) { ws.send(payload); ws.close(1000, "done") }
            }.use { f ->
                val input = Channel<String>(1).apply { trySend("hello"); close() }
                val failure = try { withTimeout(5000) { CloudSpeech(config(), f.client, f.factory).speakStream(input) {} }; null }
                    catch (e: SpeechApiException) { e }
                assertNotNull(failure)
                assertFalse(failure!!.message.orEmpty().contains("SECRET"))
                assertEquals(1, f.requests.size)
            }
        }
    }

    @Test fun turboFlushesUnpunctuatedTextDuringAPauseWithoutEndingInput() = runBlocking {
        SocketFixture { ws, msg ->
            if (msg.has("flush")) ws.send("""{"audio":"AQI="}""")
        }.use { f ->
            val input = Channel<String>(1).apply { trySend("等等我正在查看") }
            val pcm = Channel<ByteArray>(1)
            val job = launch { CloudSpeech(config(), f.client, f.factory).speakStream(input) { pcm.trySend(it) } }
            assertArrayEquals(byteArrayOf(1,2), withTimeout(5000) { pcm.receive() })
            assertFalse(job.isCompleted)
            job.cancelAndJoin()
            assertFalse(f.messages.any { it.has("close_socket") })
            input.cancel()
        }
    }

    @Test fun turboRejectsFinalBeforeProducerEndsAndCancelsSocket() = runBlocking {
        SocketFixture { ws, msg ->
            if (msg.has("inputs")) ws.send("""{"audio":"AQI=","is_final":true}""")
        }.use { f ->
            val input = Channel<String>(1).apply { trySend("还没有讲完") }
            try {
                withTimeout(5000) { CloudSpeech(config(), f.client, f.factory).speakStream(input) {} }
                fail("early final accepted")
            } catch (_: SpeechApiException) { }
            assertTrue(f.cancelled.get())
            input.cancel()
        }
    }
}
