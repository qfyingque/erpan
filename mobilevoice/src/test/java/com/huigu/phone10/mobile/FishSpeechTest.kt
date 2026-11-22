package com.huigu.phone10.mobile

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FishSpeechTest {
    private fun config(url: String = "https://api.fish.audio/v1") = SpeechConfig.openAiDefaults().copy(
        sttKey = "stt-fixture", ttsProvider = "fish-audio", ttsBaseUrl = url,
        ttsKey = "fish-fixture", ttsModel = "s2.1-pro-free", voice = "933563129e564b19a115bedd57b7406a")

    private fun client(code: Int = 200, handler: (Request) -> ResponseBody) = OkHttpClient.Builder()
        .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(handler(chain.request())).build() }.build()

    @Test fun selectingFishPreservesRecognitionAndUsesExplicitFreeModel() {
        val old = SpeechConfig.bailianDefaults().copy(sttKey = "keep-recognition", ttsKey = "old-tts")
        val fish = old.withTtsProvider("fish-audio")
        assertEquals("fish-audio", fish.effectiveTtsProvider)
        assertEquals("https://api.fish.audio/v1", fish.ttsBaseUrl)
        assertEquals("s2.1-pro-free", fish.ttsModel)
        assertEquals("", fish.ttsKey)
        assertEquals("", fish.voice)
        assertEquals(old.provider, fish.provider)
        assertEquals(old.sttKey, fish.sttKey)
        assertEquals(old.sttBaseUrl, fish.sttBaseUrl)
        assertTrue(fish.sentenceHttpTts)
        assertFalse(fish.streamingTts)
        assertFalse(fish.wholeReplyTts)
    }

    @Test fun rootV1AndFullEndpointAllReachFishRoute() = runBlocking {
        for (path in listOf("", "/", "/v1", "/v1/", "/v1/tts", "/v1/tts/")) {
            CloudSpeech(config("https://api.fish.audio$path"), client { request ->
                assertEquals("https://api.fish.audio/v1/tts", request.url.toString())
                byteArrayOf(0, 1).toResponseBody("audio/pcm".toMediaType())
            }).speak("测试", {}, {})
        }
    }

    @Test fun invalidEndpointOrHeaderNeverDispatchesOrLeaksConfig() {
        for (url in listOf("http://api.fish.audio/v1", "https://wrong.invalid/v1",
            "https://api.fish.audio/v1/audio/speech", "https://secret@api.fish.audio/v1",
            "https://api.fish.audio/v1?key=secret", "https://api.fish.audio:444/v1")) {
            val error = assertThrows(IllegalArgumentException::class.java) { config(url).validate(false) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        assertTrue(config().copy(ttsModel = "s2-pro\u0000secret").validationErrors(false).isNotEmpty())
        assertTrue(config().copy(voice = "https://fish.audio/voice/123").validationErrors(false).isNotEmpty())
        assertTrue(config().copy(voice = "").validationErrors(false).isNotEmpty())
    }

    @Test fun knownModelsValidateAndUnknownCannotSilentlySelectPaidDefault() {
        for (model in listOf("s1", "s2-pro", "s2.1-pro", "s2.1-pro-free", "drama-3"))
            config().copy(ttsModel = model).validate(false)
        assertTrue(config().copy(ttsModel = "typo-model").validationErrors(false).any { it.contains("Fish") })
    }

    @Test fun requestUsesOfficialContractAndAnnouncesRateBeforePcm() = runBlocking {
        val events = mutableListOf<String>()
        val text = "你好，今天一起散步吧。"
        CloudSpeech(config(), client { request ->
            assertEquals("Bearer fish-fixture", request.header("Authorization"))
            assertEquals("s2.1-pro-free", request.header("model"))
            assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
            val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals(text, json["text"].asString)
            assertEquals(config().voice, json["reference_id"].asString)
            assertEquals("pcm", json["format"].asString)
            assertEquals(44100, json["sample_rate"].asInt)
            assertFalse(json.has("input")); assertFalse(json.has("model")); assertFalse(json.has("voice"))
            byteArrayOf(0, 1, 2, 3).toResponseBody("audio/pcm".toMediaType())
        }).speak(text, { events += "rate:$it" }, { events += "bytes:${it.size}" })
        assertEquals(listOf("rate:44100", "bytes:4"), events)
    }

    @Test fun oddTransportChunksAreAlignedWithoutLosingTailSamples() = runBlocking {
        val expected = ByteArray(50) { it.toByte() }
        val received = Buffer()
        CloudSpeech(config(), client {
            object : ResponseBody() {
                private val stream = object : ForwardingSource(Buffer().write(expected)) {
                    override fun read(sink: Buffer, byteCount: Long) = super.read(sink, minOf(byteCount, 3))
                }.buffer()
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = stream
            }
        }).speak("测试", {}, { assertEquals(0, it.size % 2); received.write(it) })
        assertArrayEquals(expected, received.readByteArray())
    }

    @Test fun rejectsWrongFormatsAndEmptyOrTruncatedPcm() {
        for (type in listOf("text/html", "application/json", "audio/mpeg", "audio/wav")) {
            assertThrows(SpeechApiException::class.java) { runBlocking {
                CloudSpeech(config(), client { "not PCM".toResponseBody(type.toMediaType()) })
                    .speak("测试", {}, { fail("invalid response reached player") })
            } }
        }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1))) {
            assertThrows(SpeechApiException::class.java) { runBlocking {
                CloudSpeech(config(), client { bytes.toResponseBody("audio/pcm".toMediaType()) }).speak("测试", {}, {})
            } }
        }
    }

    @Test fun httpFailuresDoNotRetryChangeModelOrExposePrivateBody() {
        for (status in listOf(400, 401, 402, 429, 503)) {
            var requests = 0
            val error = assertThrows(SpeechApiException::class.java) { runBlocking {
                CloudSpeech(config(), client(status) {
                    requests++
                    "{\"message\":\"fish-fixture private speech\"}".toResponseBody("application/json".toMediaType())
                }).speak("私密测试", {}, {})
            } }
            assertEquals(1, requests)
            assertTrue(error.message.orEmpty().contains("HTTP $status"))
            assertFalse(error.message.orEmpty().contains("fish-fixture"))
            assertFalse(error.message.orEmpty().contains("private speech"))
        }
    }

    @Test fun stoppingSynthesisCancelsTransportPromptly() = runBlocking {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            if (chain.call().isCanceled()) canceled.countDown()
            throw java.io.IOException("private upstream detail")
        }.build()
        val job = launch(Dispatchers.Default) { CloudSpeech(config(), http).speak("测试", {}, {}) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(canceled.await(1, TimeUnit.SECONDS))
    }

    @Test fun sentencePlaybackKeepsOrderAndCompleteTail() = runBlocking {
        val requests = AtomicInteger()
        val cloud = CloudSpeech(config(), client {
            val value = requests.incrementAndGet().toByte()
            byteArrayOf(value, 0, value, 0).toResponseBody("audio/pcm".toMediaType())
        })
        val texts = Channel<String>(2)
        texts.send("第一句。"); texts.send("第二句。"); texts.close()
        val events = mutableListOf<String>()
        SentenceSpeech(cloud::speak).speak(texts, { events += "rate:$it" }, { events += it }, { events += "pcm:${it[0]}" })
        assertEquals(listOf("第一句。", "rate:44100", "pcm:1", "第二句。", "pcm:2"), events)
        assertEquals(2, requests.get())
    }

    @Test fun connectionCheckUsesSameAdapter() = runBlocking {
        var requests = 0
        CloudSpeech(config(), client {
            requests++
            byteArrayOf(1, 0).toResponseBody("audio/pcm".toMediaType())
        }).checkSynthesisConnection()
        assertEquals(1, requests)
    }

    @Test fun savedFishProfileAndPreviousVoiceSurviveRoundTrip() {
        val previous = SpeechConfig.bailianDefaults()
        val initial = MobileSettings(config(), chatId = "unchanged", listenOnly = true,
            sendEmotionHints = true, voiceProfiles = listOf(VoiceProfile("old", "原方案", previous, "原声音")))
        val saved = initial.saveVoiceProfile("Fish声音")
        val restored = Gson().fromJson(Gson().toJson(saved), MobileSettings::class.java)
        assertEquals(config(), restored.speech)
        assertEquals("unchanged", restored.chatId)
        assertTrue(restored.sendEmotionHints)
        assertTrue(restored.currentVoiceProfile()!!.description().startsWith("Fish Audio"))
        assertEquals(previous, restored.selectVoiceProfile("old").speech)
        assertEquals(config(), restored.selectVoiceProfile("old").selectVoiceProfile(saved.activeVoiceProfileId!!).speech)
    }
}
