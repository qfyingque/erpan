package com.huigu.phone10.mobile

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MiniMaxHttpTest {
    private val legacy = SpeechConfig.bailianDefaults().withTtsProvider(SpeechConfig.MINIMAX)
        .copy(sttKey = "asr-test", ttsKey = "tts-test", ttsModel = "speech-2.8-hd", voice = "voice-test")
    private fun config(mode: String = "http_whole", url: String? = null): SpeechConfig {
        val json = Gson().toJsonTree(legacy).asJsonObject
        json.addProperty("miniMaxTransport", mode)
        if (url != null) json.addProperty("miniMaxHttpUrl", url)
        return Gson().fromJson(json, SpeechConfig::class.java)
    }
    private val noWs = WebSocket.Factory { _, _ -> error("HTTP mode must not open a WebSocket") }
    private fun chunk(hex: String, status: Int = 1) =
        "data: {\"data\":{\"audio\":\"$hex\",\"status\":$status},\"trace_id\":\"trace-test\",\"base_resp\":{\"status_code\":0}}\n\n"
    private fun client(body: String, inspect: (Request) -> Unit = {}) = OkHttpClient.Builder().addInterceptor { chain ->
        inspect(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType())).build()
    }.build()
    private fun speak(body: String, text: String = "第一句。第二句！", onPcm: (ByteArray) -> Unit = {}) = runBlocking {
        CloudSpeech(config(), client(body), noWs).speak(text, {}, onPcm)
    }

    @Test fun oldSettingsStayBidiAndHttpFlagsPersistWithProfile() {
        assertTrue(legacy.streamingTts); assertFalse(legacy.wholeReplyTts)
        val http = config()
        assertFalse(http.streamingTts); assertTrue(http.sentenceHttpTts); assertTrue(http.wholeReplyTts)
        val settings = MobileSettings(http, listenOnly = true).saveVoiceProfile("HTTP")
        val restored = Gson().fromJson(Gson().toJson(settings), MobileSettings::class.java)
        assertTrue(restored.selectVoiceProfile(restored.profiles().single().id).speech.wholeReplyTts)
        assertEquals(legacy.ttsBaseUrl, http.ttsBaseUrl)
        val switchedBack = http.copy(miniMaxTransport = "bidi", miniMaxHttpUrl = "invalid-inactive-url")
        assertTrue(switchedBack.streamingTts); assertFalse(switchedBack.wholeReplyTts)
        assertTrue(switchedBack.validationErrors(false).isEmpty())
        assertFalse(http.withTtsProvider(SpeechConfig.MOSSLAND).wholeReplyTts)
        assertTrue(config("unknown").validationErrors(false).isNotEmpty())
    }

    @Test fun oneRequestPreservesTextVoiceModelAndAlignsPcm() = runBlocking {
        var calls = 0; var rate = 0; val pcm = mutableListOf<Byte>()
        val text = "今天🙂，慢慢说。\n完整末句！"
        val http = client(chunk("010203") + chunk("04") + chunk("", 2)) { request ->
            calls++
            assertEquals("https://api.minimax.cn/v1/t2a_v2", request.url.toString())
            assertEquals("Bearer tts-test", request.header("Authorization"))
            val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals(text, json["text"].asString)
            assertEquals("speech-2.8-hd", json["model"].asString)
            assertEquals("voice-test", json.getAsJsonObject("voice_setting")["voice_id"].asString)
            assertEquals(1.2, json.getAsJsonObject("voice_setting")["speed"].asDouble, 0.001)
            assertEquals("sad", json.getAsJsonObject("voice_setting")["emotion"].asString)
            assertTrue(json["stream"].asBoolean)
            assertTrue(json.getAsJsonObject("stream_options")["exclude_aggregated_audio"].asBoolean)
            assertEquals("pcm", json.getAsJsonObject("audio_setting")["format"].asString)
            assertEquals(24_000, json.getAsJsonObject("audio_setting")["sample_rate"].asInt)
        }
        CloudSpeech(config().copy(miniMaxVoiceSettings = MiniMaxVoiceSettings(1.2, "sad")), http, noWs)
            .speak(text, { rate = it }) { pcm.addAll(it.toList()) }
        assertEquals(1, calls); assertEquals(24_000, rate)
        assertEquals(listOf<Byte>(1, 2, 3, 4), pcm)
    }

    @Test fun wholeModeWaitsThroughToolPauseAndMakesOneHttpRequest() = runBlocking {
        val ready = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val speech = config()
        val cloud = CloudSpeech(speech, client(chunk("0102") + chunk("", 2)) { calls.incrementAndGet() }, noWs)
        val flow = VoiceConversation(this, { "请求" }, { _, delta ->
            delta("第一句。"); ready.complete(Unit); finish.await(); delta("第二句。")
        }, {}, {}, {}, streamSpeak = { input -> for (p in input) cloud.speak(p) {} },
            sentenceTts = speech.sentenceHttpTts, wholeReplyTts = speech.wholeReplyTts)
        val job = flow.submit(byteArrayOf(1))
        withTimeout(2000) { ready.await() }; delay(80); assertEquals(0, calls.get())
        finish.complete(Unit); withTimeout(3000) { job.join() }; assertEquals(1, calls.get())
    }

    @Test fun missingTerminalAndOddOrEmptyPcmAreFailures() {
        for (body in listOf(chunk("0102"), chunk("01") + chunk("", 2), chunk("", 2), "data: [DONE]\n\n")) {
            assertThrows(SpeechApiException::class.java) { speak(body) }
        }
    }

    @Test fun sseAcceptsCommentsCrLfMultilineAndFinalAtEof() {
        val received = mutableListOf<Byte>()
        val body = ": keepalive\r\nevent: message\r\ndata: {\"data\":\r\ndata: {\"audio\":\"0102\",\"status\":1}}\r\n\r\n" +
            chunk("", 2).trimEnd()
        speak(body, onPcm = { received.addAll(it.toList()) })
        assertEquals(listOf<Byte>(1, 2), received)
    }

    @Test fun jsonBusinessErrorIsParsedWithoutSseOrRetry() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"base_resp":{"status_code":1002,"status_msg":"RPM private"},"trace_id":"trace-json"}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val error = assertThrows(SpeechApiException::class.java) { runBlocking { CloudSpeech(config(), client, noWs).speak("测试") {} } }
        assertTrue(error.message.orEmpty().contains("1002")); assertTrue(error.message.orEmpty().contains("trace-json"))
        assertFalse(error.message.orEmpty().contains("private")); assertEquals(1, calls)
    }

    @Test fun cancellingBeforeWholeReplyEndsNeverUploadsPartialText() = runBlocking {
        val ready = CompletableDeferred<Unit>(); val calls = AtomicInteger(); val speech = config()
        val cloud = CloudSpeech(speech, client(chunk("0102") + chunk("", 2)) { calls.incrementAndGet() }, noWs)
        val flow = VoiceConversation(this, { "请求" }, { _, delta ->
            delta("尚未完成。"); ready.complete(Unit); awaitCancellation()
        }, {}, {}, {}, streamSpeak = { input -> for (p in input) cloud.speak(p) {} },
            sentenceTts = speech.sentenceHttpTts, wholeReplyTts = speech.wholeReplyTts)
        val job = flow.submit(byteArrayOf(1)); withTimeout(2000) { ready.await() }
        flow.interrupt(); withTimeout(1500) { job.join() }; assertEquals(0, calls.get())
    }

    @Test fun largeAudioStreamsWithoutWholeClipBuffering() {
        var received = 0
        val second = chunk("0000".repeat(24_000))
        speak(second.repeat(117) + chunk("", 2), onPcm = { received += it.size })
        assertEquals(5_616_000, received)
    }

    @Test fun finalAggregateNeverReplaysAlreadyEmittedAudio() {
        val pcm = mutableListOf<Byte>()
        assertThrows(SpeechApiException::class.java) {
            speak(chunk("0102") + chunk("0102", 2), onPcm = { pcm.addAll(it.toList()) })
        }
        assertEquals(listOf<Byte>(1, 2), pcm)
    }

    @Test fun rpmErrorPreservesSafeTraceButNotProviderText() {
        val error = assertThrows(SpeechApiException::class.java) {
            speak(chunk("0102") + "data: {\"base_resp\":{\"status_code\":1002,\"status_msg\":\"RPM tts-test private-text\"},\"trace_id\":\"abc123\"}\n\n")
        }
        assertTrue(error.message.orEmpty().contains("1002")); assertTrue(error.message.orEmpty().contains("abc123"))
        assertFalse(error.message.orEmpty().contains("tts-test")); assertFalse(error.message.orEmpty().contains("private-text"))
    }

    @Test fun oversizedLineAndWrongAudioFormatFail() {
        assertThrows(SpeechApiException::class.java) { speak("data: " + "x".repeat(4_194_305) + "\n\n") }
        assertThrows(SpeechApiException::class.java) {
            speak("data: {\"data\":{\"audio\":\"0102\",\"status\":1},\"extra_info\":{\"audio_sample_rate\":8000}}\n\n" + chunk("", 2))
        }
    }

    @Test fun officialMappingNeverGuessesCustomRelayAndExplicitUrlIsValidated() {
        val custom = config().copy(ttsBaseUrl = "wss://relay.example/ws/v1/t2a_v2_bidi")
        assertTrue(custom.validationErrors(false).isNotEmpty())
        assertTrue(config(url = "https://relay.example/v1/t2a_v2").validationErrors(false).isEmpty())
        for (url in listOf("http://relay.example/v1/t2a_v2", "https://relay.example/v1/t2a_v2?key=secret",
            "https://user:secret@relay.example/v1/t2a_v2")) {
            val errors = config(url = url).validationErrors(false)
            assertTrue(errors.isNotEmpty()); assertFalse(errors.joinToString().contains("secret"))
        }
    }

    @Test fun rejectsLongInputBeforeAnyRequestAndNeverRetries() = runBlocking {
        var calls = 0
        val cloud = CloudSpeech(config(), client(chunk("0102") + chunk("", 2)) { calls++ }, noWs)
        assertThrows(SpeechApiException::class.java) { runBlocking { cloud.speak("字".repeat(10_000)) {} } }
        assertEquals(0, calls)
    }

    @Test fun cancellationStopsRealStreamingHttpWithoutAnotherRequest() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            // MockWebServer also throttles request bodies: allow the entire request immediately.
            .setBody(chunk("0102") + chunk("0102".repeat(2048)) + chunk("", 2))
            .throttleBody(1024, 1, TimeUnit.SECONDS)); server.start()
        val firstPcm = CompletableDeferred<Unit>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).addInterceptor { chain ->
            assertEquals(TimeUnit.SECONDS.toNanos(600), chain.call().timeout().timeoutNanos())
            chain.proceed(chain.request().newBuilder().url(server.url("/v1/t2a_v2")).build())
        }.build()
        try {
            val job = launch(Dispatchers.IO) { CloudSpeech(config(), http, noWs).speak("测试") { firstPcm.complete(Unit) } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5000) { firstPcm.await() }
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(cancelled.get())
            assertEquals(1, server.requestCount)
        } finally { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); server.shutdown(); http.dispatcher.executorService.shutdownNow() }
    }
}
