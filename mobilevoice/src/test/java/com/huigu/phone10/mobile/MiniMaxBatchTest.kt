package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Tests the actual adapter at a local WS boundary, never calls a paid provider. */
class MiniMaxBatchTest {
    private val config = SpeechConfig.bailianDefaults().withTtsProvider(SpeechConfig.MINIMAX)
        .copy(ttsKey = "private-test-key-DO-NOT-LOG")

    private class Peer(private val failure: String? = null) : AutoCloseable {
        val pieces = LinkedBlockingQueue<String>()
        val events = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val sockets = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
                client.newWebSocket(request.newBuilder().url(server.url("/ws/v1/t2a_v2_bidi")).build(), listener)
        }
        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"event":"connected_success","base_resp":{"status_code":0}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JsonParser.parseString(text).asJsonObject
                    val event = message["event"].asString
                    events.add(event)
                    when (event) {
                        "task_start" -> webSocket.send("""{"event":"task_started","base_resp":{"status_code":0}}""")
                        "task_continue" -> {
                            pieces.add(message["text"].asString)
                            webSocket.send("""{"event":"task_continued","data":{"audio":"0102"},"base_resp":{"status_code":0}}""")
                        }
                        "task_finish" -> webSocket.send(failure ?: """{"event":"task_finished","base_resp":{"status_code":0}}""")
                    }
                }
            }))
            server.start()
        }
        override fun close() {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }

    // Mutation caught: forwarding each ready upstream fragment instead of coalescing it.
    @Test fun burstFragmentsKeepAllTextWithFarFewerMessages() = runBlocking {
        Peer().use { peer ->
            val text = "风吹过，灯亮着。".repeat(100).take(646)
            val input = Channel<String>(Channel.UNLIMITED)
            text.chunked(5).forEach { input.send(it) }; input.close()
            withTimeout(5000) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} }
            assertEquals(text, peer.pieces.joinToString(""))
            assertTrue("A ready burst should be coalesced, got ${peer.pieces.size} requests", peer.pieces.size <= 3)
            assertEquals(1, peer.events.count { it == "task_start" })
            assertEquals(1, peer.events.count { it == "task_finish" })
            println("BATCH burst chars=${text.length} input_pieces=${text.chunked(5).size} sent_pieces=${peer.pieces.size}")
        }
    }

    // Mutation caught: waiting for channel closure or indefinitely for the target batch length.
    @Test fun firstAudioAndSmallFollowupArriveWhileInputRemainsOpen() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(8)
            input.send("你好。")
            val job = async(Dispatchers.Default) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} }
            try {
                assertEquals("你好。", peer.pieces.poll(2, TimeUnit.SECONDS))
                input.send("接着说")
                assertEquals("接着说", peer.pieces.poll(900, TimeUnit.MILLISECONDS))
                assertFalse(job.isCompleted)
                input.close(); withTimeout(3000) { job.await() }
            } finally { input.cancel(); job.cancelAndJoin() }
        }
    }

    // Mutation caught: discarding a buffered tail when channel closes during collection.
    @Test fun normalClosureFlushesTailBeforeFinish() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(8)
            input.send("开头。")
            val job = async(Dispatchers.Default) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} }
            try {
                assertEquals("开头。", peer.pieces.poll(2, TimeUnit.SECONDS))
                input.send("尾句"); input.send("没有句号"); input.close()
                withTimeout(3000) { job.await() }
                assertEquals("尾句没有句号", peer.pieces.joinToString(""))
                assertEquals("task_finish", peer.events.last())
            } finally { input.cancel(); job.cancelAndJoin() }
        }
    }

    // Mutation caught: timeout-based receive loses input during a slow incremental producer.
    @Test fun slowFragmentsAreNeitherLostNorRepeated() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(8)
            val fragments = listOf("开头。", "甲", "乙", "丙", "丁", "戊", "己", "庚", "辛")
            val producer = launch { for (word in fragments) { input.send(word); delay(47) }; input.close() }
            withTimeout(5000) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} }
            producer.join()
            assertEquals(fragments.joinToString(""), peer.pieces.joinToString(""))
            assertTrue("Slow short fragments should still aggregate", peer.pieces.size < fragments.size)
        }
    }

    // Mutation caught: treating a failed input channel as normal EOF and issuing task_finish.
    @Test fun exceptionalInputClosureDoesNotFinishSuccessfully() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(2)
            input.send("开头。")
            input.close(IllegalStateException("upstream-failed"))
            val error = runCatching { withTimeout(3000) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} } }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(peer.events.contains("task_finish"))
        }
    }

    @Test fun exceptionalClosureBeforeFirstTextIsNotReportedAsSuccess() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(1)
            input.close(IllegalStateException("source failed before first text"))
            val failure = runCatching { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} }.exceptionOrNull()
            assertNotNull("A failed upstream is not an empty successful reply", failure)
            assertEquals(0, peer.server.requestCount)
        }
    }

    @Test fun emptyNormalClosureDoesNotOpenARequest() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(1); input.close()
            MiniMaxSpeech(config, peer.sockets).speakStream(input) { fail("Empty input has no audio") }
            assertEquals(0, peer.server.requestCount)
        }
    }

    @Test fun oversizedInputDoesNotBypassTheCharacterBudget() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(1); input.send("字".repeat(60_001)); input.close()
            val failure = runCatching { withTimeout(3000) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} } }.exceptionOrNull()
            assertTrue(failure is SpeechApiException)
            assertTrue(peer.pieces.isEmpty())
            assertFalse(peer.events.contains("task_finish"))
        }
    }

    // Mutation caught: cancellation flushes buffered text or sends a new task after stopping.
    @Test fun cancellationDuringCollectionStopsWithoutFinishingOrRetrying() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(8)
            input.send("开头。")
            val job = async(Dispatchers.Default) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} }
            try {
                assertEquals("开头。", peer.pieces.poll(2, TimeUnit.SECONDS))
                input.send("待合并")
                withTimeout(1500) { job.cancelAndJoin() }
                assertFalse(peer.events.contains("task_finish"))
                assertEquals(1, peer.events.count { it == "task_start" })
                assertEquals(1, peer.server.requestCount)
            } finally { input.cancel(); job.cancelAndJoin() }
        }
    }

    // Mutation caught: emitting halves of a Unicode surrogate pair across upstream fragments.
    @Test fun splitEmojiAndLargeBatchRemainIntactAndWithinPerFrameLimit() = runBlocking {
        Peer().use { peer ->
            val input = Channel<String>(Channel.UNLIMITED)
            val text = "😀" + "好".repeat(7998) + "😀结束。"
            input.send(text.substring(0, 1)); input.send(text.substring(1, 8001)); input.send(text.substring(8001)); input.close()
            withTimeout(5000) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} }
            assertEquals(text, peer.pieces.joinToString(""))
            assertTrue(peer.pieces.all { it.length <= 8000 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        }
    }

    // Mutation caught: swallowing error identity or recording server-echoed private text/key.
    @Test fun rateLimitRetainsTraceAndStatsWithoutEchoingSensitiveStatusMessage() = runBlocking {
        val trace = "minimax-batch-test-1234567890"
        val failure = """{"event":"task_failed","trace_id":"$trace","base_resp":{"status_code":1002,"status_msg":"rate limit RPM private-test-key-DO-NOT-LOG PRIVATE-UTTERANCE https://example.invalid/secret"}}"""
        Peer(failure).use { peer ->
            val input = Channel<String>(1); input.send("测试。"); input.close()
            val error = runCatching { withTimeout(3000) { MiniMaxSpeech(config, peer.sockets).speakStream(input) {} } }.exceptionOrNull()
            assertTrue(error is SpeechApiException)
            assertTrue(error?.message.orEmpty().contains(trace))
            val diagnostics = VoiceDiagnostics.snapshot()
            assertTrue(diagnostics.contains(trace))
            assertTrue(diagnostics.contains("minimax_input_done"))
            assertTrue(diagnostics.contains("queue_wait_ms_"))
            assertFalse((diagnostics + error?.message).contains("PRIVATE-UTTERANCE"))
            assertFalse((diagnostics + error?.message).contains(config.ttsKey))
            assertFalse((diagnostics + error?.message).contains("example.invalid"))
        }
    }
}
