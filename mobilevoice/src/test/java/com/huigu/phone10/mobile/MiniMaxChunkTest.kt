package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.channels.Channel
import com.google.gson.JsonParser
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.Collections

class MiniMaxChunkTest {
    private fun config(mode: String) = SpeechConfig.bailianDefaults()
        .withTtsProvider(SpeechConfig.MINIMAX).copy(ttsKey = "test-key", miniMaxTransport = mode)

    @Test fun segmentedModesUseHttpWithoutWaitingForWholeReplyAndSurviveSave() {
        for (mode in listOf("http_sentence", "http_300")) {
            val speech = config(mode)
            assertTrue("$mode should use HTTP", speech.miniMaxHttpTts)
            assertTrue(speech.sentenceHttpTts)
            assertFalse(speech.wholeReplyTts)
            assertFalse(speech.streamingTts)
            assertTrue(speech.validationErrors(false).toString(), speech.validationErrors(false).isEmpty())
            val stored = MobileSettings(speech, listenOnly = true).saveVoiceProfile(mode)
            val restored = Gson().fromJson(Gson().toJson(stored), MobileSettings::class.java)
            assertEquals(mode, restored.selectVoiceProfile(restored.profiles().single().id).speech.effectiveMiniMaxTransport)
            assertFalse(speech.withTtsProvider(SpeechConfig.QWEN_LOCAL).miniMaxHttpTts)
        }
    }

    @Test fun sentencesWaitForStopsNotCommasAndKeepTrailingQuotes() {
        val buffer = SpeechText(miniMaxChunkMode = "http_sentence")
        assertTrue(buffer.push("你好，小盈").isEmpty())
        val pieces = buffer.push("。“真的吗？！”再见！") + buffer.flushReady() + buffer.flush()
        assertEquals(listOf("你好，小盈。", "“真的吗？！”", "再见！"), pieces)
    }

    @Test fun threeHundredWaitsPastOldTimerAndFlushesFinalRemainder() {
        var now = 0L
        val buffer = SpeechText(miniMaxChunkMode = "http_300") { now }
        val prefix = "甲。".repeat(100)
        assertTrue(buffer.push(prefix).isEmpty())
        now = 20_000
        assertTrue(buffer.flushReady().isEmpty())
        assertEquals(listOf(prefix + "乙".repeat(100)), buffer.push("乙".repeat(100) + "末尾。"))
        assertEquals(listOf("末尾。"), buffer.flush())
    }

    @Test fun unicodeAndHiddenMarkupDoNotLoseOrLeakText() {
        val buffer = SpeechText(miniMaxChunkMode = "http_300")
        val parts = mutableListOf<String>()
        for (delta in listOf("<thi", "nk>秘密。</think>" + "甲".repeat(299) + "\uD83D", "\uDE42尾巴")) parts += buffer.push(delta)
        parts += buffer.flush()
        assertEquals(listOf("甲".repeat(299) + "🙂", "尾巴"), parts)
    }

    @Test fun sentenceTimeoutAndHardCapDoNotSplitSurrogatePair() {
        var now = 0L
        val buffer = SpeechText(miniMaxChunkMode = "http_sentence") { now }
        assertTrue(buffer.push("还没句号\uD83D").isEmpty())
        now = 2501
        assertEquals(listOf("还没句号"), buffer.flushReady())
        assertTrue(buffer.push("\uDE42").isEmpty())
        assertEquals(listOf("🙂"), buffer.flush())
        assertEquals(listOf("甲".repeat(300), "甲".repeat(300)), buffer.push("甲".repeat(601)))
        assertEquals(listOf("甲"), buffer.flush())
    }

    @Test fun streamedDecimalDoesNotBecomeTwoRequests() {
        val buffer = SpeechText(miniMaxChunkMode = "http_sentence")
        assertTrue(buffer.push("值为3.").isEmpty())
        assertTrue(buffer.flushReady().isEmpty())
        assertEquals(listOf("值为3.14。"), buffer.push("14。下一句。"))
        assertEquals(listOf("下一句。"), buffer.flushReady())
    }

    @Test fun threeHundredCountsSpokenTextAfterMarkdownAndWhitespaceCleaning() {
        val buffer = SpeechText(miniMaxChunkMode = "http_300")
        assertTrue(buffer.push("**" + "甲".repeat(298)).isEmpty())
        assertTrue(buffer.push("**    ").isEmpty())
        assertEquals(listOf("甲".repeat(298) + " 乙"), buffer.push("乙"))
        assertTrue(buffer.flush().isEmpty())
    }

    @Test fun lateClosingQuoteDoesNotBecomeARequestOrPrefixNextSentence() {
        var now = 0L
        val buffer = SpeechText(miniMaxChunkMode = "http_sentence") { now }
        assertTrue(buffer.push("“你好。").isEmpty())
        assertEquals(listOf("“你好。"), buffer.flushReady())
        assertTrue(buffer.push("”").isEmpty())
        now = 3000
        assertTrue(buffer.flushReady().isEmpty())
        assertEquals(listOf("下一句。"), buffer.push("下一句。") + buffer.flush())
    }

    @Test fun decimalAfterHardCapOrTimedPartialFlushKeepsItsPoint() {
        val buffer = SpeechText(miniMaxChunkMode = "http_sentence")
        val input = "甲".repeat(299) + "3.14元。"
        assertEquals(listOf("甲".repeat(299) + "3", ".14元。"), buffer.push(input) + buffer.flush())
        var now = 0L
        val timed = SpeechText(miniMaxChunkMode = "http_sentence") { now }
        timed.push("值为3")
        now = 2501
        val parts = timed.flushReady() + timed.push(".14元。") + timed.flush()
        assertEquals(listOf("值为3", ".14元。"), parts)
    }

    @Test fun malformedAudioProducesSafePhaseAndTraceDiagnostics() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body("data: {\"trace_id\":\"phase-test-trace\",\"data\":{\"status\":1,\"audio\":\"zz\"}}\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try { CloudSpeech(config("http_whole"), client).speak("私密正文") {}; fail("must fail") }
        catch (_: SpeechApiException) { }
        val log = VoiceDiagnostics.snapshot()
        assertTrue(log, log.contains("phase_hex"))
        assertTrue(log, log.contains("trace_phase-test-trace"))
        assertFalse(log.contains("私密正文")); assertFalse(log.contains("test-key"))
    }

    private fun audioClient(requests: MutableList<String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val text = JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            .asJsonObject["text"].asString
        requests.add(text)
        Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
            .body(("data: {\"data\":{\"status\":1,\"audio\":\"0102\"}}\n\n" +
                "data: {\"data\":{\"status\":2,\"audio\":\"\"}}\n\n").toResponseBody("text/event-stream".toMediaType())).build()
    }.build()

    @Test fun listenOnlyUsesSelectedRoleSentenceModeAndStartsBeforeSourceEnds() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val received = CompletableDeferred<Unit>()
        val cfg = config("http_sentence")
        val cloud = CloudSpeech(cfg, audioClient(requests))
        val input = Channel<String>(2)
        val voice = ReplyVoice({}, { texts -> SentenceSpeech(cloud::speak).speak(texts, {}) { received.complete(Unit) } },
            cfg.sentenceHttpTts, cfg.wholeReplyTts, false, false, cfg.miniMaxChunkMode)
        val flow = VoiceConversation(this, { "" }, { _, _ -> }, {}, {}, {},
            voiceForRole = { voice }, miniMaxChunkMode = "http_300")
        val job = flow.playReplyStream(input)
        input.send("第一句，先播放。"); withTimeout(3000) { received.await() }
        assertEquals(listOf("第一句，先播放。"), requests.toList())
        assertTrue(job.isActive)
        input.send("第二句！最后一点"); input.close()
        withTimeout(3000) { job.join() }
        assertEquals(listOf("第一句，先播放。", "第二句！", "最后一点"), requests.toList())
    }

    @Test fun callPathSubmits300WhileSourceContinuesAndKeepsFinalTail() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val received = CompletableDeferred<Unit>()
        val cfg = config("http_300"); val cloud = CloudSpeech(cfg, audioClient(requests))
        val flow = VoiceConversation(this, { "你好" }, { _, delta ->
            delta("甲".repeat(310)); withTimeout(3000) { received.await() }
            assertEquals(listOf("甲".repeat(300)), requests.toList())
            delta("乙".repeat(300))
        }, {}, {}, {}, streamSpeak = { texts -> SentenceSpeech(cloud::speak).speak(texts, {}) { received.complete(Unit) } },
            sentenceTts = cfg.sentenceHttpTts, wholeReplyTts = cfg.wholeReplyTts, miniMaxChunkMode = cfg.miniMaxChunkMode)
        withTimeout(4000) { flow.submit(byteArrayOf(1)).join() }
        assertEquals(listOf("甲".repeat(300), "甲".repeat(10) + "乙".repeat(290), "乙".repeat(10)), requests.toList())
    }

    @Test fun sourceFailureNeverSubmitsAnIncomplete300Tail() = runBlocking {
        val requests = mutableListOf<String>(); val statuses = mutableListOf<String>()
        val cfg = config("http_300"); val cloud = CloudSpeech(cfg, audioClient(requests))
        val flow = VoiceConversation(this, { "你好" }, { _, delta -> delta("未完成的文字。"); throw java.io.IOException("source failed") },
            {}, {}, statuses::add, streamSpeak = { texts -> SentenceSpeech(cloud::speak).speak(texts, {}) {} },
            sentenceTts = cfg.sentenceHttpTts, miniMaxChunkMode = cfg.miniMaxChunkMode)
        withTimeout(3000) { flow.submit(byteArrayOf(1)).join() }
        assertTrue(requests.isEmpty())
        assertTrue(statuses.any { "Operit 回复失败" in it })
    }

    @Test fun interruptionCancelsQueuedSegmentsWithOnlyOneSegmentLookahead() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val received = CompletableDeferred<Unit>()
        val cfg = config("http_sentence"); val cloud = CloudSpeech(cfg, audioClient(requests))
        val flow = VoiceConversation(this, { "" }, { _, _ -> }, {}, {}, {},
            streamSpeak = { texts -> SentenceSpeech(cloud::speak).speak(texts, {}) { received.complete(Unit); awaitCancellation() } },
            sentenceTts = true, miniMaxChunkMode = cfg.miniMaxChunkMode)
        val job = flow.playReply("第一句。第二句。第三句。第四句。")
        withTimeout(3000) { received.await() }
        delay(80)
        flow.interrupt(); withTimeout(1500) { job.join() }
        assertTrue(requests.isNotEmpty()); assertTrue(requests.size <= 2)
        assertFalse(requests.any { "第三" in it || "第四" in it })
        val count = requests.size; delay(50); assertEquals(count, requests.size)
    }

    @Test fun transportFailureAfterPcmIsDiagnosedWithoutRawExceptionMessage() {
        val source = object : ForwardingSource(Buffer().writeUtf8("data: {\"trace_id\":\"io-test-trace\",\"data\":{\"status\":1,\"audio\":\"0102\"}}\n\n")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count == -1L) throw java.net.ProtocolException("raw-secret-transport")
                return count
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        val response = Response.Builder().request(Request.Builder().url("https://example.test/").build())
            .code(200).message("OK").protocol(Protocol.HTTP_1_1).body(body).build()
        var bytes = 0
        val error = assertThrows(SpeechApiException::class.java) {
            response.use { MiniMaxHttpSpeech.result(config("http_300"), it, { true }, {}, { bytes += it.size }) }
        }
        assertEquals(2, bytes)
        assertTrue(error.message.orEmpty().contains("protocol_io"))
        val log = VoiceDiagnostics.snapshot()
        assertTrue(log.contains("phase_read_trace_io-test-trace_events_1_pcm_bytes_2"))
        assertFalse(log.contains("raw-secret-transport"))
    }
}
