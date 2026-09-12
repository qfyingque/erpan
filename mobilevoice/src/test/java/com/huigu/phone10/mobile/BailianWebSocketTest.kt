package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real local WebSocket upgrade/framing with OkHttp; no account, microphone, or paid API. */
class BailianWebSocketTest {
    @Test fun realSocketAsrAndTtsHonorProtocolAndStreamWithoutWaitingForFinish() = runBlocking {
        for (asr in listOf(true, false)) {
            val frames = LinkedBlockingQueue<Any>()
            val server = MockWebServer()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { frames.add(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.add(bytes) }
            }))
            server.start()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
            val factory = object : WebSocket.Factory {
                override fun newWebSocket(request: Request, listener: WebSocketListener) =
                    client.newWebSocket(request.newBuilder().url(server.url("/api-ws/v1/inference")).build(), listener)
            }
            val config = SpeechConfig.bailianDefaults().copy(sttKey = "test-key", ttsKey = "test-key", voice = "test-voice")
            val done = java.util.concurrent.CompletableFuture<String>()
            val audio = LinkedBlockingQueue<ByteArray>()
            val worker = Thread {
                try { done.complete(runBlocking {
                    val speech = CloudSpeech(config, client, factory)
                    if (asr) speech.transcribe(byteArrayOf(1, 2, 3, 4))
                    else { speech.speak("你好") { audio.add(it) }; "spoken" }
                }) } catch (error: Throwable) { done.completeExceptionally(error) }
            }
            fun next() = frames.poll(5, TimeUnit.SECONDS) ?: error("Missing WebSocket frame")
            try {
                worker.start()
                val socket = next() as WebSocket
                val run = JsonParser.parseString(next() as String).asJsonObject
                val id = run.getAsJsonObject("header")["task_id"].asString
                fun event(name: String, payload: String = "{}") = socket.send("""{"header":{"event":"$name","task_id":"$id"},"payload":$payload}""")
                val payload = run.getAsJsonObject("payload")
                assertEquals("audio", payload["task_group"].asString)
                assertEquals(if (asr) "recognition" else "SpeechSynthesizer", payload["function"].asString)
                assertEquals(if (asr) 16000 else 24000, payload.getAsJsonObject("parameters")["sample_rate"].asInt)
                assertEquals("pcm", payload.getAsJsonObject("parameters")["format"].asString)
                event("task-started")
                if (asr) assertArrayEquals(byteArrayOf(1, 2, 3, 4), (next() as ByteString).toByteArray())
                else {
                    val input = JsonParser.parseString(next() as String).asJsonObject
                    assertEquals(id, input.getAsJsonObject("header")["task_id"].asString)
                    assertEquals("你好", input.getAsJsonObject("payload").getAsJsonObject("input")["text"].asString)
                }
                val finish = JsonParser.parseString(next() as String).asJsonObject
                assertEquals("finish-task", finish.getAsJsonObject("header")["action"].asString)
                assertEquals(id, finish.getAsJsonObject("header")["task_id"].asString)
                if (asr) event("result-generated", """{"output":{"sentence":{"text":"你好","begin_time":0,"end_time":null,"sentence_end":true}}}""")
                else {
                    socket.send(byteArrayOf(1, 2, 3, 4).toByteString())
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.poll(5, TimeUnit.SECONDS))
                    assertFalse(done.isDone)
                }
                event("task-finished")
                assertEquals(if (asr) "你好" else "spoken", done.get(5, TimeUnit.SECONDS))
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                assertEquals("/api-ws/v1/inference", request.path)
            } finally {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll()
                worker.interrupt(); worker.join(5000)
                server.shutdown(); client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
