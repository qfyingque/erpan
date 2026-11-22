package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpeechRecoveryTest {
    private val config = SpeechConfig("https://speech.invalid/v1", "private-asr-key", "stt",
        "https://speech.invalid/v1", "private-tts-key", "tts", "voice")
    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody("application/json".toMediaType())).build()
    private val pcm = ByteArray(32000)

    @Test fun badRequestPreservesErrorCodeAndRequestIdButNotEchoedSecretsOrSpeech() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            response(it.request(), 400, """{"code":"InvalidParameter","request_id":"req-400-123","message":"audio duration too short private-asr-key private-tts-key 用户私密原话","input":{"text":"用户私密原话"}}""")
        }.build()
        val error = assertThrows(SpeechApiException::class.java) { runBlocking { CloudSpeech(config, client).transcribe(pcm) } }
        assertEquals(1, calls.get())
        val report = error.message.orEmpty() + VoiceDiagnostics.snapshot()
        assertTrue(report, report.contains("InvalidParameter"))
        assertTrue(report, report.contains("req-400-123"))
        assertFalse(report.contains("private-asr-key"))
        assertFalse(report.contains("private-tts-key"))
        assertFalse(report.contains("用户私密原话"))
    }

    @Test fun temporaryRecognitionFailureRetriesOnceAndReturnsOnlySuccessfulText() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            if (calls.incrementAndGet() == 1) response(it.request(), 503, "{}")
            else response(it.request(), 200, """{"text":"新一句"}""")
        }.build()
        assertEquals("新一句", CloudSpeech(config, client).transcribe(pcm))
        assertEquals(2, calls.get())
    }

    @Test fun persistentNetworkFailureStopsAfterTwoAttempts() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw java.io.IOException("private-asr-key") }.build()
        val error = assertThrows(SpeechApiException::class.java) { runBlocking { CloudSpeech(config, client).transcribe(pcm) } }
        assertEquals(2, calls.get())
        assertFalse(error.message.orEmpty().contains("private-asr-key"))
    }

    @Test fun cancellationDuringRetryWaitDoesNotSendSecondRequest() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); response(it.request(),503,"{}") }.build()
        val job = launch { CloudSpeech(config, client).transcribe(pcm) }
        withTimeout(2000) { while(calls.get() == 0) delay(5) }
        delay(80)
        job.cancelAndJoin()
        delay(650)
        assertEquals(1, calls.get())
    }

    @Test fun synthesisIsNeverAutomaticallyReplayed() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); response(it.request(),503,"{}") }.build()
        assertThrows(SpeechApiException::class.java) { runBlocking { CloudSpeech(config, client).speak("测试") {} } }
        assertEquals(1, calls.get())
    }

    @Test fun authAndLongRetryAfterDoNotLoop() = runBlocking {
        for (status in listOf(401, 403, 429)) {
            val calls = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor {
                calls.incrementAndGet()
                response(it.request(), status, "{}").newBuilder().header("Retry-After", "120").build()
            }.build()
            assertThrows(SpeechApiException::class.java) { runBlocking { CloudSpeech(config,client).transcribe(pcm) } }
            assertEquals(1, calls.get())
        }
    }

    @Test fun giantOrMalformedBodiesStillPreserveHttpStatusWithoutDumpingBody() = runBlocking {
        for (body in listOf("<html>private-asr-key</html>", "{\"message\":\"" + "x".repeat(20000) + "\"}")) {
            val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 400, body) }.build()
            val error = assertThrows(SpeechApiException::class.java) { runBlocking { CloudSpeech(config,client).transcribe(pcm) } }
            assertTrue(error.message.orEmpty().contains("400"))
            assertTrue(error.message.orEmpty().length < 700)
            assertFalse(error.message.orEmpty().contains("private-asr-key"))
        }
    }

    @Test fun nestedErrorAndSensitiveIdentifiersAreHandledWithoutCredentialEcho() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            response(it.request(),400,"""{"error":{"code":"private-asr-key","message":"invalid sample rate"},"request_id":"private-tts-key"}""")
        }.build()
        val error = assertThrows(SpeechApiException::class.java) { runBlocking { CloudSpeech(config,client).transcribe(pcm) } }
        assertTrue(error.message.orEmpty().contains("采样率"))
        val all = error.message.orEmpty() + VoiceDiagnostics.snapshot()
        assertFalse(all.contains("private-asr-key"))
        assertFalse(all.contains("private-tts-key"))
    }

    @Test fun diagnosticsStayBoundedAndDoNotEchoCommonCredentials() {
        repeat(600) { VoiceDiagnostics.record("failed: sk-private-secret Bearer private-token https://private.invalid/key " + "x".repeat(3000)) }
        val report = VoiceDiagnostics.snapshot()
        assertFalse(report.contains("private-secret"))
        assertFalse(report.contains("private-token"))
        assertFalse(report.contains("private.invalid"))
        assertTrue(report.length.toString(), report.length < 150000)
    }

    @Test fun recognitionFailureAllowsNextUtteranceWithoutSendingOldText() = runBlocking {
        var round = 0
        val sent = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val conversation = VoiceConversation(this, {
            if (++round == 1) throw SpeechApiException("套餐识别服务返回 HTTP 400。")
            "新一句"
        }, { text, _ -> sent.add(text) }, {}, {}, { reports.add(it) })
        conversation.submit(pcm).join()
        assertTrue(conversation.acceptsSpeech())
        assertTrue(reports.last(), reports.last().contains("继续聆听"))
        assertTrue(sent.isEmpty())
        conversation.submit(pcm).join()
        assertEquals(listOf("新一句"), sent)
        conversation.interrupt()
    }

    @Test fun recentFailureSurvivesHighVolumePlaybackEvents() {
        VoiceDiagnostics.record("stage_failed: diagnostic-marker-400")
        repeat(300) { VoiceDiagnostics.record("pcm_frames_sent_$it") }
        val report = VoiceDiagnostics.snapshot()
        assertTrue(report.contains("diagnostic-marker-400"))
        assertTrue(report.length < 150000)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(report))
    }
}
