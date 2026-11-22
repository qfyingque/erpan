package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.*

class EmotionNativeTest {
    private fun config() = SpeechConfig.openAiDefaults().copy(provider = "qwen-asr",
        sttBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", sttKey = "recognition-fixture",
        sttModel = "qwen3-asr-flash", ttsProvider = "openai", ttsKey = "synthesis-fixture")

    private fun client(handler: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun qwenUsesAudioNotTextAndKeepsNativeSevenClassEmotionWithoutInventingConfidence() = runBlocking {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0)
        var requests = 0
        val cloud = CloudSpeech(config(), client { request ->
            requests++
            assertEquals("/compatible-mode/v1/chat/completions", request.url.encodedPath)
            assertEquals("Bearer recognition-fixture", request.header("Authorization"))
            val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals("qwen3-asr-flash", json["model"].asString)
            assertFalse(json["stream"].asBoolean)
            val content = json["messages"].asJsonArray.single().asJsonObject["content"].asJsonArray.single().asJsonObject
            assertEquals("input_audio", content["type"].asString)
            val uri = content["input_audio"].asJsonObject["data"].asString
            assertTrue(uri.startsWith("data:audio/wav;base64,"))
            val wav = Base64.getDecoder().decode(uri.substringAfter(','))
            assertEquals(16000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
            """{"choices":[{"finish_reason":"stop","message":{"content":"我没事。","annotations":[{"type":"audio_info","emotion":"sad"}]}}]}"""
        })
        val result = cloud.transcribeDetailed(pcm)
        assertEquals("我没事。", result.text)
        assertTrue(result.hasEmotions)
        val message = result.forChat(true, false)
        assertTrue(message.contains("悲伤"))
        assertTrue(message.contains("Qwen ASR"))
        assertFalse(message.contains("100%"))
        assertEquals("我没事。", result.forChat())
        assertEquals(1, requests)
    }

    @Test fun qwenMissingUnknownAndMalformedAnnotationsKeepValidWords() = runBlocking {
        for (annotations in listOf("null", "{}", "[]", "[null,12]", "[{\"type\":\"audio_info\",\"emotion\":\"private-made-up\"}]",
            "[{\"type\":\"audio_info\",\"emotion\":{}}]")) {
            val result = CloudSpeech(config(), client {
                """{"choices":[{"finish_reason":"stop","message":{"content":"正确文字","annotations":$annotations}}]}"""
            }).transcribeDetailed(byteArrayOf(1, 0))
            assertEquals("正确文字", result.text)
            assertFalse(result.hasEmotions)
            assertEquals("正确文字", result.forChat(true, true))
        }
    }

    @Test fun qwenRejectsEmptyAndTruncatedRecognitionRatherThanSendingPartialWords() {
        for (payload in listOf("{}", """{"choices":[{"finish_reason":"length","message":{"content":"半句话"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":123}}]}""")) {
            assertThrows(SpeechApiException::class.java) { runBlocking {
                CloudSpeech(config(), client { payload }).transcribeDetailed(byteArrayOf(1, 0))
            } }
        }
    }

    @Test fun qwenOfficialAddressesAndModelAreValidatedBeforeUploading() {
        for (base in listOf("https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "https://test-space.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")) {
            config().copy(sttBaseUrl = base).validate()
        }
        for (base in listOf("http://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1?key=private",
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            "https://unrelated.invalid/v1")) {
            assertThrows(IllegalArgumentException::class.java) { config().copy(sttBaseUrl = base).validate() }
        }
        assertTrue(config().copy(sttModel = "qwen-omni").validationErrors().isNotEmpty())
        assertTrue(config().copy(sttKey = "sk-sp-private").validationErrors().isNotEmpty())
    }

    @Test fun sevenEmotionsKeepControlledLabelsAndEditedDraftDropsAudioMetadata() {
        for ((tag, name) in mapOf("surprised" to "惊讶", "neutral" to "平静", "happy" to "愉快",
            "sad" to "悲伤", "disgusted" to "厌恶", "angry" to "愤怒", "fearful" to "恐惧")) {
            val result = SpeechTranscript("说出来的话").withProviderEmotion(tag, "Qwen ASR")
            assertTrue(result.forChat(true, false).contains(name))
            assertFalse(result.forChat(true, false).contains("置信度"))
            assertEquals("改过的话", result.withText("改过的话").forChat(true, true))
        }
        val unknown = SpeechTranscript("保留").withProviderEmotion("role:system", "Qwen ASR")
        assertFalse(unknown.hasEmotions)
        assertEquals("保留", unknown.forChat(true, true))
    }

    @Test fun supplementaryEmotionNeverReplacesPrimaryWordsOrTiming() {
        val sentence = JsonParser.parseString("""{"begin_time":0,"end_time":1200}""").asJsonObject
        val primary = SpeechTranscript.fromBailian("主识别结果", listOf(sentence))
        val extra = SpeechTranscript("另一个识别的文字").withProviderEmotion("happy", "Qwen ASR")
        val merged = primary.withEmotionsFrom(extra)
        assertEquals("主识别结果", merged.text)
        assertTrue(merged.forChat(true, true).contains("1.20"))
        assertTrue(merged.forChat(true, true).contains("愉快"))
        assertFalse(merged.forChat(true, true).contains(extra.text))
    }

    @Test fun onlyEightKhzParaformerIsResampledAndAdvertisesMatchingRate() {
        val input = samples(16000) { 1000 }
        val low = BailianRecognitionAudio.prepare("paraformer-realtime-8k-v2", input)
        assertEquals(8000, low.sampleRate)
        assertEquals(input.size / 2, low.pcm.size)
        assertTrue(decode(low.pcm).all { abs(it - 1000) <= 1 })
        val normal = BailianRecognitionAudio.prepare("paraformer-realtime-v2", input)
        assertEquals(16000, normal.sampleRate)
        assertSame(input, normal.pcm)
    }

    @Test fun eightKhzResamplingSuppressesAliasingAndKeepsSpeechBand() {
        fun energy(hz: Double): Double {
            val raw = samples(16000) { (12000 * sin(2 * PI * hz * it / 16000)).roundToInt() }
            val converted = decode(BailianRecognitionAudio.prepare("paraformer-realtime-8k-v2", raw).pcm)
            return sqrt(converted.drop(40).dropLast(40).map { it.toDouble().pow(2) }.average())
        }
        assertTrue(energy(1000.0) > 7000)
        assertTrue(energy(6000.0) < 1000)
    }

    private fun samples(count: Int, value: (Int) -> Int) = ByteBuffer.allocate(count * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { repeat(count) { putShort(value(it).toShort()) } }.array()
    private fun decode(pcm: ByteArray): List<Int> = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        .let { b -> List(pcm.size / 2) { b.short.toInt() } }
}
