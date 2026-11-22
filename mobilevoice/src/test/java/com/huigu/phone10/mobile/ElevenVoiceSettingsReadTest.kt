package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ElevenVoiceSettingsReadTest {
    private fun config() = SpeechConfig.bailianDefaults().withTtsProvider(SpeechConfig.ELEVENLABS)
        .copy(ttsKey = "ELEVEN-ONLY", voice = "voice123")

    @Test fun lateReadCannotOverwriteSpeechChangesMadeBeforeTheNextUiFrame() {
        val expected = config()
        val fetched = ElevenVoiceSettings(0.82, 0.91)
        for (newSpeech in listOf(expected.copy(voice = "new-voice"), expected.copy(ttsModel = "eleven_v4"),
            expected.copy(sttModel = "new-recognition-model"), expected.copy(elevenVoiceSettings = ElevenVoiceSettings(0.2, 0.4)))) {
            val currentDraft = MobileSettings(newSpeech)
            assertNull(currentDraft.withReadElevenVoiceSettings(expected, fetched))
        }
    }

    @Test fun readOnlyMergesVoiceValuesIntoTheLatestDraftPreservingOtherEdits() {
        val expected = config()
        val currentDraft = MobileSettings(expected, chatId = "new-chat", voiceName = "new-name", captionsEnabled = true,
            activeVoiceProfileId = "current-profile", confirmBeforeSend = true)
        val fetched = ElevenVoiceSettings(0.82, 0.91)
        assertEquals(currentDraft.copy(speech = expected.copy(elevenVoiceSettings = fetched)),
            currentDraft.withReadElevenVoiceSettings(expected, fetched))
    }

    private suspend fun read(body: String, status: Int = 200, model: String = "eleven_flash_v2_5"): ElevenVoiceSettings {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val r = chain.request()
            assertEquals("GET", r.method)
            assertEquals("https://api.elevenlabs.io/v1/voices/voice123/settings", r.url.toString())
            assertEquals("ELEVEN-ONLY", r.header("xi-api-key"))
            assertNull(r.header("Authorization"))
            assertNull(r.body)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .header("Location", "https://example.invalid/never-follow")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try { return CloudSpeech(config().copy(ttsModel = model), client).readElevenVoiceSettings() }
        finally { assertEquals(1, calls); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun readsRemoteValuesAsAnEditingSnapshotWithoutSynthesizingOrWriting() = runBlocking {
        assertEquals(ElevenVoiceSettings(0.82, 0.91), read("""{"stability":0.82,"similarity_boost":0.91,"style":0.9}"""))
        assertEquals(ElevenVoiceSettings(0.5, null), read("""{"stability":0.5,"similarity_boost":0.91}""", model = "eleven_v3"))
    }

    @Test fun missingFieldsStayUnsetRatherThanTurningIntoInventedDefaults() = runBlocking {
        assertEquals(ElevenVoiceSettings(null, 0.67), read("""{"similarity_boost":0.67}"""))
    }

    @Test fun malformedOutOfRangeAndEmptyResultsFailWithoutExposingRemoteContent() = runBlocking {
        for (body in listOf("SECRET", "{}", "[]", """{"stability":"SECRET"}""",
            """{"stability":1.2}""", """{"stability":0.4,"similarity_boost":-0.1}""", " ".repeat(32_769))) {
            val failure = try { read(body); null } catch (e: SpeechApiException) { e }
            assertNotNull(body.take(20), failure)
            assertFalse(failure!!.message.orEmpty().contains("SECRET"))
            assertFalse(failure.message.orEmpty().contains("ELEVEN-ONLY"))
        }
    }

    @Test fun permissionErrorsAndRedirectsDoNotRetryOrUseFallbackValues() = runBlocking {
        for (status in listOf(301, 401, 403, 429, 500)) {
            val failure = try { read("SECRET", status); null } catch (e: SpeechApiException) { e }
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains(status.toString()))
            assertFalse(failure.message.orEmpty().contains("SECRET"))
        }
    }

    @Test fun v3ImportDoesNotSilentlyRoundACloudValue() = runBlocking {
        try { read("""{"stability":0.71,"similarity_boost":0.8}""", model = "eleven_v3"); fail("must select supported value") }
        catch (error: SpeechApiException) { assertTrue(error.message.orEmpty().contains("v3")) }
    }

    @Test fun leavingReadCancelsTheHttpCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val cancelled = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/settings")).build())
        }.eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).build()
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { CloudSpeech(config(), client).readElevenVoiceSettings() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(cancelled.get())
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
