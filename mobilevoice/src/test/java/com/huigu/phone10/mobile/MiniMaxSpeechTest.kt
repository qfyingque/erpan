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

class MiniMaxSpeechTest {
    private val config = SpeechConfig.bailianDefaults().withTtsProvider(SpeechConfig.MINIMAX)
        .copy(sttKey = "ASR-ONLY", ttsKey = "MINIMAX-ONLY")

    @Test fun realWebsocketStreamsBeforeReplyEndsAndKeepsSentenceFinalInsideSameTask() = runBlocking {
        val messages = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            var parts = 0
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"event":"connected_success","base_resp":{"status_code":0}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.add(text)
                when (JsonParser.parseString(text).asJsonObject["event"].asString) {
                    "task_start" -> webSocket.send("""{"event":"task_started"}""")
                    "task_continue" -> {
                        val hex = if (parts++ == 0) "010203" else "04"
                        webSocket.send("""{"event":"sentence_start"}""")
                        webSocket.send("""{"event":"task_continued","data":{"audio":"$hex"},"is_final":true}""")
                        webSocket.send("""{"event":"sentence_end"}""")
                    }
                    "task_finish" -> webSocket.send("""{"event":"task_finished"}""")
                }
            }
        }))
        server.start()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val captured = LinkedBlockingQueue<Request>()
        val sockets = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                captured.add(request)
                return client.newWebSocket(request.newBuilder().url(server.url("/ws/v1/t2a_v2_bidi")).build(), listener)
            }
        }
        val texts = Channel<String>(2)
        val audio = Channel<ByteArray>(2)
        try {
            texts.send("第一句。")
            val speech = async { CloudSpeech(config, client, sockets).speakStream(texts) { audio.trySend(it) } }
            assertArrayEquals(byteArrayOf(1, 2), withTimeout(5000) { audio.receive() })
            assertFalse(speech.isCompleted)
            texts.send("第二句。")
            assertArrayEquals(byteArrayOf(3, 4), withTimeout(5000) { audio.receive() })
            texts.close()
            withTimeout(5000) { speech.await() }
            val all = messages.toList().map { JsonParser.parseString(it).asJsonObject }
            assertEquals(listOf("task_start", "task_continue", "task_continue", "task_finish"), all.map { it["event"].asString })
            assertEquals(24_000, all[0].getAsJsonObject("audio_setting")["sample_rate"].asInt)
            assertEquals("pcm", all[0].getAsJsonObject("audio_setting")["format"].asString)
            assertEquals("Bearer MINIMAX-ONLY", captured.single().header("Authorization"))
            assertFalse(all.joinToString().contains("ASR-ONLY"))
        } finally {
            texts.cancel(); audio.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun hexRejectsInvalidDataAndPreservesUnsignedSamples() {
        assertArrayEquals(byteArrayOf(-1, -128, 0, 127), MiniMaxSpeech.decodeHex("Ff80007f"))
        listOf("abc", "GG").forEach { text -> assertThrows(Exception::class.java) { MiniMaxSpeech.decodeHex(text) } }
    }
}
