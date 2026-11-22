package com.huigu.phone10.mobile

import com.google.gson.Gson
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

class ElevenLabsSpeechTest {
    private fun config(model: String = "eleven_flash_v2_5") = SpeechConfig.bailianDefaults().copy(
        sttKey = "ASR-ONLY", ttsProvider = "elevenlabs", ttsBaseUrl = "https://api.elevenlabs.io/v1",
        ttsKey = "ELEVEN-ONLY", ttsModel = model, voice = "voice123")

    @Test fun profilesKeepRecognitionAndSeparateModelsAfterSerialization() {
        val source = SpeechConfig.bailianDefaults().copy(sttKey = "ASR-ONLY", ttsKey = "CLOUD", voice = "cloud")
        val defaults = source.withTtsProvider("elevenlabs")
        assertEquals(source.sttKey, defaults.sttKey)
        assertEquals("eleven_flash_v2_5", defaults.ttsModel)
        assertEquals("", defaults.ttsKey)
        assertTrue(config().streamingTts)
        assertFalse(config("eleven_v3").streamingTts)
        assertTrue(config("eleven_v3").sentenceHttpTts)
        assertFalse(config().sentenceHttpTts)
        val saved = MobileSettings(source, chatId = "chat").saveVoiceProfile("Cloud")
            .copy(speech = config()).saveVoiceProfile("Flash")
            .copy(speech = config("eleven_v3")).saveVoiceProfile("v3")
        val restored = Gson().fromJson(Gson().toJson(saved), MobileSettings::class.java)
        assertEquals(saved, restored)
        assertEquals(source, restored.selectVoiceProfile(saved.profiles()[0].id).speech)
        assertEquals("ElevenLabs", restored.profiles()[1].description().substringBefore(" ·"))
    }

    @Test fun invalidVoiceModelAndUnofficialEndpointsAreRejected() {
        assertTrue(config().validationErrors(false).isEmpty())
        for (bad in listOf(config().copy(voice = "../secret"), config("unknown"),
            config().copy(ttsBaseUrl = "https://other.example/v1"),
            config().copy(ttsBaseUrl = "https://api.elevenlabs.io/v1?key=SECRET"))) {
            val errors = bad.validationErrors(false)
            assertTrue(errors.isNotEmpty())
            assertFalse(errors.joinToString().contains("SECRET"))
        }
    }

    @Test fun v3UsesOfficialHttpPcmAndOnlySynthesisKey() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("https://api.elevenlabs.io/v1/text-to-speech/voice123/stream?output_format=pcm_24000", request.url.toString())
            assertEquals("ELEVEN-ONLY", request.header("xi-api-key"))
            assertNull(request.header("Authorization"))
            val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals("eleven_v3", json["model_id"].asString)
            assertEquals("你好。", json["text"].asString)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(byteArrayOf(1, 2, 3, 4).toResponseBody("audio/pcm".toMediaType())).build()
        }.build()
        try {
            var rate = 0
            val pcm = mutableListOf<Byte>()
            CloudSpeech(config("eleven_v3"), client).speak("你好。", { rate = it }, { pcm.addAll(it.toList()) })
            assertEquals(24000, rate)
            assertEquals(listOf<Byte>(1, 2, 3, 4), pcm)
            assertEquals(1, calls)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    private class Fixture(val respond: (WebSocket, String) -> Unit) : AutoCloseable {
        val messages = LinkedBlockingQueue<String>()
        val requests = LinkedBlockingQueue<Request>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                requests.add(request)
                val real = client.newWebSocket(request.newBuilder().url(server.url("/ws")).build(), listener)
                return object : WebSocket by real {
                    override fun cancel() { cancelled.set(true); real.cancel() }
                }
            }
        }
        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages.add(text); respond(webSocket, JsonParser.parseString(text).asJsonObject["text"].asString)
                }
            }))
            server.start()
        }
        override fun close() {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun flashPlaysBeforeInputEndsAndHandlesOddNetworkChunks() = runBlocking {
        var part = 0
        Fixture { ws, text ->
            when (text) {
                " " -> Unit
                "" -> ws.send("""{"isFinal":true}""")
                else -> ws.send(if (part++ == 0) """{"audio":"AQID"}""" else """{"audio":"BA=="}""")
            }
        }.use { f ->
            val texts = Channel<String>(2)
            val pcm = Channel<ByteArray>(2)
            texts.send("第一句。")
            val job = async { CloudSpeech(config(), f.client, f.factory).speakStream(texts) { pcm.trySend(it) } }
            assertArrayEquals(byteArrayOf(1, 2), withTimeout(5000) { pcm.receive() })
            assertFalse(job.isCompleted)
            texts.send("第二句。")
            assertArrayEquals(byteArrayOf(3, 4), withTimeout(5000) { pcm.receive() })
            texts.close(); withTimeout(5000) { job.await() }
            val request = f.requests.single()
            assertEquals("ELEVEN-ONLY", request.header("xi-api-key"))
            assertEquals("eleven_flash_v2_5", request.url.queryParameter("model_id"))
            assertEquals("pcm_24000", request.url.queryParameter("output_format"))
            val sent = f.messages.map { JsonParser.parseString(it).asJsonObject["text"].asString }
            assertEquals(listOf(" ", "第一句。 ", "第二句。 ", ""), sent)
            assertFalse(f.messages.joinToString().contains("ELEVEN-ONLY"))
        }
    }

    @Test fun flashContinuesAfterNullFinalFlagAndPlaysThroughLastFrame() = runBlocking {
        Fixture { ws, text ->
            if (text.isEmpty()) {
                ws.send("""{"audio":"AQI=","isFinal":null}""")
                ws.send("""{"audio":"AwQ=","isFinal":false}""")
                ws.send("""{"audio":"BQY=","isFinal":true}""")
            }
        }.use { f ->
            val input = Channel<String>(1).apply { trySend("读到最后。"); close() }
            val pcm = mutableListOf<Byte>()
            withTimeout(5000) {
                CloudSpeech(config(), f.client, f.factory).speakStream(input) { pcm.addAll(it.toList()) }
            }
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), pcm)
        }
    }

    @Test fun flashRejectsTruncatedPcmAndProviderErrorsWithoutExposingBody() = runBlocking {
        for (payload in listOf("""{"audio":"AQ==","isFinal":true}""", """{"error":"SECRET server failure"}""")) {
            Fixture { ws, text -> if (text.isEmpty()) ws.send(payload) }.use { f ->
                val texts = Channel<String>(1).apply { trySend("Hi."); close() }
                val failure = try {
                    withTimeout(5000) { CloudSpeech(config(), f.client, f.factory).speakStream(texts) {} }
                    null
                } catch (e: SpeechApiException) { e }
                assertNotNull(failure); assertFalse(failure!!.message.orEmpty().contains("SECRET"))
            }
        }
    }

    @Test fun toolPauseKeepsSocketAliveAndCancellationNeverSendsEndOrReplays() = runBlocking {
        Fixture { _, _ -> }.use { f ->
            val input = Channel<String>(1).apply { trySend("请稍等。") }
            val job = launch { ElevenLabsSpeech(config(), f.factory, heartbeatMillis = 30).speakStream(input) {} }
            withTimeout(5000) { while (f.messages.size < 3) delay(10) }
            assertFalse(job.isCompleted)
            val texts = f.messages.map { JsonParser.parseString(it).asJsonObject["text"].asString }
            assertEquals(" ", texts[0]); assertEquals("请稍等。 ", texts[1]); assertEquals(" ", texts[2])
            job.cancelAndJoin()
            assertTrue(f.cancelled.get())
            assertEquals(1, f.requests.size)
            assertFalse(f.messages.any { JsonParser.parseString(it).asJsonObject["text"].asString.isEmpty() })
            input.cancel()
        }
    }

    @Test fun v3ErrorsNeverRetryOrReachThePlayer() = runBlocking {
        for (status in listOf(401, 403, 429, 500)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("SECRET provider body".toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                val failure = try { CloudSpeech(config("eleven_v3"), client).speak("Hi.") { fail("error reached player") }; null }
                    catch (e: SpeechApiException) { e }
                assertNotNull(failure); assertFalse(failure!!.message.orEmpty().contains("SECRET"))
                assertEquals(1, calls)
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun v3RejectsWrongFormatEmptyAudioAndOddTail() = runBlocking {
        for ((bytes, type) in listOf(byteArrayOf(1, 2) to "application/json", byteArrayOf() to "audio/pcm", byteArrayOf(1, 2, 3) to "audio/pcm")) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bytes.toResponseBody(type.toMediaType())).build()
            }.build()
            try {
                var failed = false
                try { CloudSpeech(config("eleven_v3"), client).speak("Hi.") {} } catch (_: SpeechApiException) { failed = true }
                assertTrue(failed)
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }
}
