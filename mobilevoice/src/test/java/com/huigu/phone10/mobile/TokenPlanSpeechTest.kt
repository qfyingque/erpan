package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TokenPlanSpeechTest {
    private fun speech(config: SpeechConfig, http: OkHttpClient) = CloudSpeech(config, http, WebSocket.Factory { _, _ -> error("Unexpected WebSocket for Token Plan") })
    private val root = "https://token-plan.cn-beijing.maas.aliyuncs.com"
    private fun config(base: String = root) = SpeechConfig(base, "test-key", "qwen-audio-3.0-asr-flash",
        base, "test-key", "qwen-audio-3.0-tts-plus", "longanhuan_v3.6", provider = SpeechConfig.BAILIAN)
    private fun client(handler: (Request) -> ResponseBody) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request())).build()
    }.build()
    private fun json(text: String) = text.toResponseBody("application/json".toMediaType())
    private fun sse(text: String) = text.toResponseBody("text/event-stream".toMediaType())
    private fun frame(data: String) = "event: result\ndata: $data\n\n"
    private fun audio(bytes: ByteArray) = frame("""{"output":{"type":"sentence-synthesis","audio":{"data":"${Base64.getEncoder().encodeToString(bytes)}"},"finish_reason":null}}""")
    private val stop = frame("""{"output":{"finish_reason":"stop"}}""")

    @Test fun tokenUsesSentenceHttpWhileOrdinaryBailianRemainsDuplex() {
        assertFalse(config().streamingTts)
        assertTrue(config().sentenceHttpTts)
        assertTrue(SpeechConfig.bailianDefaults().streamingTts)
        assertFalse(SpeechConfig.bailianDefaults().sentenceHttpTts)
    }
    @Test fun consoleCompatibleBaseIsAccepted() {
        config("$root/compatible-mode/v1").validate()
    }
    @Test fun invalidAddressesNeverLeakOrSendCredentials() {
        for (base in listOf("http://token-plan.cn-beijing.maas.aliyuncs.com",
            "$root/compatible-mode/v1?key=secret", "https://user:secret@token-plan.cn-beijing.maas.aliyuncs.com",
            "$root/../compatible-mode/v1", "$root:8443", "$root/unknown")) {
            val error = assertThrows(IllegalArgumentException::class.java) { config(base).validate() }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        assertFalse(config("https://token-plan.cn-beijing.maas.aliyuncs.com.attacker.invalid").tokenPlanTts)
    }
    @Test fun eachSideCanUseAnIndependentProvider() {
        val normal = SpeechConfig.bailianDefaults().copy(sttKey = "normal", ttsKey = "normal", voice = "longxiaochun")
        val sttOnly = normal.copy(sttBaseUrl = root, sttModel = "qwen-audio-3.0-asr-flash")
        sttOnly.validate()
        assertTrue(sttOnly.tokenPlanStt)
        assertTrue(sttOnly.streamingTts)
        val ttsOnly = config().copy(sttBaseUrl = normal.sttBaseUrl, sttModel = normal.sttModel)
        ttsOnly.validate()
        assertFalse(ttsOnly.tokenPlanStt)
        assertTrue(ttsOnly.sentenceHttpTts)
    }
    @Test fun planKeyOnOrdinaryEndpointExplainsHowToCorrectIt() {
        val invalid = SpeechConfig.bailianDefaults().copy(sttKey = "sk-sp-private", ttsKey = "sk-sp-private", voice = "voice")
        val errors = invalid.validationErrors()
        assertTrue(errors.any { it.contains("Token Plan") })
        assertFalse(errors.joinToString().contains("private"))
    }
    @Test fun recognitionUploadsWavToDedicatedDomainAndReadsFullText() = runBlocking {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val speech = speech(config(), client { request ->
            assertEquals("$root/api/v1/services/aigc/multimodal-generation/generation", request.url.toString())
            assertEquals("Bearer test-key", request.header("Authorization"))
            val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals("qwen-audio-3.0-asr-flash", body["model"].asString)
            val content = body.getAsJsonObject("input").getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")[0].asJsonObject
            assertEquals("input_audio", content["type"].asString)
            val uri = content.getAsJsonObject("input_audio")["data"].asString
            assertTrue(uri.startsWith("data:audio/wav;base64,"))
            val wav = Base64.getDecoder().decode(uri.substringAfter(','))
            assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
            assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
            assertEquals("16000", body.getAsJsonObject("parameters")["sample_rate"].asString)
            json("""{"output":{"text":" 第一段。第二段。 ","sentence":{"text":"第二段。"}}}""")
        })
        assertEquals("第一段。第二段。", speech.transcribeDetailed(pcm).text)
    }
    @Test fun recognitionEmptyIsNoSpeechAndMalformedIsNotConnectionSuccess() = runBlocking {
        speech(config(), client { json("""{"output":{"text":""}}""") }).checkRecognitionConnection()
        for (body in listOf("{}", """{"output":{"text":123}}""", """{"output":{"sentence":{"text":"partial"}}}""")) {
            assertThrows(SpeechApiException::class.java) { runBlocking {
                speech(config(), client { json(body) }).checkRecognitionConnection()
            } }
        }
    }
    @Test fun synthesisSendsCorrectInputAndAlignsPcmAcrossEvents() = runBlocking {
        val received = mutableListOf<Byte>()
        var rate = 0
        speech(config("$root/compatible-mode/v1"), client { request ->
            assertEquals("$root/api/v1/services/audio/tts/SpeechSynthesizer", request.url.toString())
            assertEquals("enable", request.header("X-DashScope-SSE"))
            val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            val input = body.getAsJsonObject("input")
            assertEquals("你好", input["text"].asString)
            assertEquals("pcm", input["format"].asString)
            assertEquals(24000, input["sample_rate"].asInt)
            assertEquals("longanhuan_v3.6", input["voice"].asString)
            sse(": heartbeat\n\n" + audio(byteArrayOf(1)) + audio(byteArrayOf(2, 3, 4)) + stop)
        }).speak("你好", { rate = it }, { received.addAll(it.toList()) })
        assertEquals(24000, rate)
        assertEquals(listOf<Byte>(1, 2, 3, 4), received)
    }
    @Test fun rejectsPrematureEofOddPcmEmptyAndJsonErrors() = runBlocking {
        for (events in listOf(audio(byteArrayOf(1, 2)), audio(byteArrayOf(1)) + stop, stop,
            frame("""{"code":"InvalidApiKey","message":"test-key private text"}"""))) {
            val error = assertThrows(SpeechApiException::class.java) { runBlocking {
                speech(config(), client { sse(events) }).speak("你好") {}
            } }
            assertFalse(error.message.orEmpty().contains("test-key"))
        }
    }
    @Test fun malformedMediaNeverReachesPlayer() {
        for (media in listOf("text/html", "application/json", "audio/mpeg")) {
            assertThrows(SpeechApiException::class.java) { runBlocking {
                speech(config(), client { "bad".toResponseBody(media.toMediaType()) }).speak("你好") { fail("Invalid media played") }
            } }
        }
    }
    @Test fun rejectsUnsupportedModelsBeforeNetwork() {
        assertTrue(config().copy(sttModel = "paraformer-realtime-v2").validationErrors().isNotEmpty())
        assertTrue(config().copy(ttsModel = "cosyvoice-v1").validationErrors().isNotEmpty())
    }
    @Test fun cancellationStopsTokenRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            if (chain.call().isCanceled()) canceled.countDown()
            throw java.io.IOException("private detail")
        }.build()
        val job = launch(Dispatchers.Default) { speech(config(), http).transcribe(byteArrayOf(1, 2)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(canceled.await(1, TimeUnit.SECONDS))
    }
}
