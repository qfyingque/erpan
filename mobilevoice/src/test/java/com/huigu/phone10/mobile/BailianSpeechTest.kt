package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BailianSpeechTest {
    @Test fun detailedRecognitionKeepsFinalMetadataWithoutAnotherRequest() = runBlocking {
        val wire = Wire()
        val job = async(Dispatchers.IO) { BailianSpeech(config, wire).transcribeDetailed(byteArrayOf(1, 2)) }
        val id = wire.run()
        wire.event(id, "task-started"); wire.next(); wire.next()
        wire.event(id, "result-generated", """{"output":{"sentence":{
          "begin_time":0,"text":"你好","sentence_end":false,"emo_tag":"negative","emo_confidence":0.99}}}""")
        val final = """{"output":{"sentence":{"begin_time":0,"end_time":1000,
          "text":"你好","sentence_end":true,"emo_tag":"positive","emo_confidence":0.9}}}"""
        wire.event(id, "result-generated", final)
        wire.event(id, "result-generated", final)
        wire.event(id, "task-finished")
        val result = job.await()
        assertEquals("你好", result.text)
        assertTrue(result.forChat(includeVoiceHints = true).contains("疑似正面情感"))
        assertFalse(result.forChat(includeVoiceHints = true).contains("负面"))
        assertEquals(1, Regex("疑似正面情感").findAll(result.forChat(includeVoiceHints = true)).count())
        assertTrue(wire.sent.isEmpty())
    }

    @Test fun wholeReplyUsesOneTaskWhileLaterTextArrivesDuringAudio() = runBlocking {
        val wire = Wire()
        val input = kotlinx.coroutines.channels.Channel<String>(4)
        val audio = LinkedBlockingQueue<ByteArray>()
        input.send("第一句。")
        val job = async(Dispatchers.IO) { BailianSpeech(config, wire).speakStream(input) { audio.add(it) } }
        val id = wire.run(); wire.event(id, "task-started")
        assertTrue((wire.next() as String).contains("第一句。"))
        wire.pcm(1, 2)
        assertArrayEquals(byteArrayOf(1, 2), audio.poll(2, TimeUnit.SECONDS))
        assertNull(wire.sent.poll(80, TimeUnit.MILLISECONDS)) // no premature finish or second run
        input.send("后面的内容继续追加。")
        val next = JsonParser.parseString(wire.next() as String).asJsonObject
        assertEquals(id, next.getAsJsonObject("header")["task_id"].asString)
        assertEquals("continue-task", next.getAsJsonObject("header")["action"].asString)
        input.close()
        assertTrue((wire.next() as String).contains("finish-task"))
        wire.event(id, "task-finished"); job.await()
        assertTrue(wire.sent.isEmpty()); assertTrue(wire.canceled.get())
    }

    @Test fun cancellingStreamingReplyClosesSocketWhileWaitingForMoreText() = runBlocking {
        val wire = Wire(); val input = kotlinx.coroutines.channels.Channel<String>(1)
        input.send("开头。")
        val job = launch(Dispatchers.IO) { BailianSpeech(config, wire).speakStream(input) {} }
        val id = wire.run(); wire.event(id, "task-started"); wire.next()
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(wire.canceled.get()); assertTrue(wire.sent.isEmpty())
        input.close()
        Unit
    }

    private val config get() = SpeechConfig.bailianDefaults().copy(sttKey = "test-key", ttsKey = "test-key", voice = "test-voice")
    private class Wire : WebSocket.Factory, WebSocket {
        lateinit var listener: WebSocketListener
        lateinit var req: Request
        val sent = LinkedBlockingQueue<Any>()
        val canceled = AtomicBoolean(false)
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            req = request; this.listener = listener
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
            return this
        }
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun send(bytes: ByteString): Boolean { sent.add(bytes); return true }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { canceled.set(true) }
        fun next(): Any = sent.poll(3, TimeUnit.SECONDS) ?: error("no outbound frame")
        fun run(): String {
            val run = JsonParser.parseString(next() as String).asJsonObject
            assertEquals("run-task", run.getAsJsonObject("header")["action"].asString)
            assertEquals("Bearer test-key", req.header("Authorization"))
            return run.getAsJsonObject("header")["task_id"].asString
        }
        fun event(id: String, event: String, payload: String = "{}", extraHeader: String = "") {
            listener.onMessage(this, """{"header":{"task_id":"$id","event":"$event"$extraHeader},"payload":$payload}""")
        }
        fun pcm(vararg bytes: Byte) = listener.onMessage(this, bytes.toByteString())
        fun sentence(id: String, text: String, end: Boolean, begin: Int = 0, heartbeat: Boolean = false) =
            event(id, "result-generated", """{"output":{"sentence":{"begin_time":$begin,"text":"$text","sentence_end":$end,"heartbeat":$heartbeat}}}""")
    }

    @Test fun asrWaitsForStartSendsPcmThenFinishAndCollectsFinalSentencesOnce() = runBlocking {
        val wire = Wire()
        val job = async(Dispatchers.IO) { BailianSpeech(config, wire).transcribe(byteArrayOf(1, 2, 3, 4)) }
        val id = wire.run()
        assertNull(wire.sent.poll())
        wire.event(id, "task-started")
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), (wire.next() as ByteString).toByteArray())
        assertTrue((wire.next() as String).contains("finish-task"))
        wire.sentence(id, "你", false)
        wire.sentence(id, "你好。", true)
        wire.sentence(id, "你好。", true)
        wire.sentence(id, "heartbeat", true, 2, true)
        wire.sentence(id, "再见。", true, 2000)
        wire.event(id, "task-finished")
        assertEquals("你好。再见。", job.await())
        assertTrue(wire.canceled.get())
    }

    @Test fun ttsStreamsPcmBeforeFinishAndAlignsAcrossFrames() = runBlocking {
        val wire = Wire(); val audio = LinkedBlockingQueue<ByteArray>()
        val job = async(Dispatchers.IO) { BailianSpeech(config, wire).speak("你好") { audio.add(it) } }
        val id = wire.run(); wire.event(id, "task-started")
        assertTrue((wire.next() as String).contains("continue-task"))
        assertTrue((wire.next() as String).contains("finish-task"))
        wire.pcm(1, 2, 3)
        assertArrayEquals(byteArrayOf(1, 2), audio.poll(2, TimeUnit.SECONDS))
        assertFalse(job.isCompleted)
        wire.pcm(4)
        assertArrayEquals(byteArrayOf(3, 4), audio.poll(2, TimeUnit.SECONDS))
        wire.event(id, "task-finished"); job.await()
    }

    @Test fun failuresAreSafeAndCloseTheSocket() = runBlocking {
        val wire = Wire()
        val result = async(Dispatchers.IO) { runCatching { BailianSpeech(config, wire).transcribe(byteArrayOf(1, 2)) } }
        val id = wire.run()
        wire.event(id, "task-failed", extraHeader = ",\"error_code\":\"InvalidApiKey\",\"error_message\":\"test-key private reply\"")
        val error = result.await().exceptionOrNull()!!
        assertTrue(error is SpeechApiException)
        assertFalse(error.message!!.contains("test-key"))
        // Coroutine debug stack recovery may wrap a copy of this same safe exception.
        assertTrue(generateSequence(error) { it.cause }.all {
            it is SpeechApiException && !it.message.orEmpty().contains("test-key") && !it.message.orEmpty().contains("private reply")
        })
        assertTrue(wire.canceled.get())
    }

    @Test fun cancelRejectsLateAudio() = runBlocking {
        val wire = Wire(); val count = java.util.concurrent.atomic.AtomicInteger()
        val job = launch(Dispatchers.IO) { BailianSpeech(config, wire).speak("你好") { count.incrementAndGet() } }
        val id = wire.run(); wire.event(id, "task-started")
        wire.next(); wire.next()
        job.cancelAndJoin()
        wire.pcm(1, 2); wire.event(id, "task-finished")
        assertEquals(0, count.get()); assertTrue(wire.canceled.get())
    }

    @Test fun missingTaskFinishedTimesOutEvenAfterPcm() = runBlocking {
        val wire = Wire()
        val job = async(Dispatchers.IO) { runCatching { BailianSpeech(config, wire, 300).speak("你好") {} } }
        val id = wire.run(); wire.event(id, "task-started"); wire.pcm(1, 2)
        val error = job.await().exceptionOrNull()!!
        assertTrue(error is SpeechApiException); assertTrue(error.message!!.contains("超时"))
        assertTrue(wire.canceled.get())
    }

    @Test fun wrongTaskOrInvalidOrderingNeverSucceeds() = runBlocking {
        for (event in listOf("wrong-task", "task-finished", "binary", "bad-json")) {
            val wire = Wire()
            val job = async(Dispatchers.IO) { runCatching { BailianSpeech(config, wire).speak("你好") {} } }
            val id = wire.run()
            when (event) {
                "wrong-task" -> wire.event("other", "task-started")
                "binary" -> wire.pcm(1, 2)
                "bad-json" -> wire.listener.onMessage(wire, "<html>test-key</html>")
                else -> wire.event(id, event)
            }
            assertTrue(job.await().exceptionOrNull() is SpeechApiException)
        }
    }

    @Test fun emptyAndTruncatedPcmAndEarlyCloseAreErrors() = runBlocking {
        for (mode in listOf("empty", "odd", "close")) {
            val wire = Wire()
            val job = async(Dispatchers.IO) { runCatching { BailianSpeech(config, wire).speak("你好") {} } }
            val id = wire.run(); wire.event(id, "task-started")
            if (mode == "odd") wire.pcm(1, 2, 3)
            if (mode == "close") wire.listener.onClosing(wire, 1000, "private text") else wire.event(id, "task-finished")
            assertTrue(job.await().exceptionOrNull() is SpeechApiException)
        }
    }

    @Test fun cloudSpeechRoutesBailianWithoutOpenAiHttpRequest() = runBlocking {
        val wire = Wire()
        val client = OkHttpClient.Builder().addInterceptor { error("HTTP audio API must not be used") }.build()
        val job = async(Dispatchers.IO) { CloudSpeech(config, client, wire).transcribe(byteArrayOf(1, 2)) }
        val id = wire.run(); wire.event(id, "task-started"); wire.sentence(id, "成功", true); wire.event(id, "task-finished")
        assertEquals("成功", job.await())
    }

    @Test fun cancelUnblocksBothSlowPlayerAndBackpressuredReader() = runBlocking {
        val wire = Wire(); val entered = java.util.concurrent.CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            BailianSpeech(config, wire).speak("你好") {
                entered.countDown()
                java.util.concurrent.CountDownLatch(1).await()
            }
        }
        val id = wire.run(); wire.event(id, "task-started"); wire.pcm(1, 2)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val finished = java.util.concurrent.CountDownLatch(1)
        val reader = Thread { repeat(40) { wire.pcm(3, 4) }; finished.countDown() }.apply { start() }
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(finished.await(2, TimeUnit.SECONDS)); reader.join(2000)
        assertTrue(wire.canceled.get())
    }

    @Test fun unfinishedRecognitionIsNotSentAsCompleteText() = runBlocking {
        val wire = Wire()
        val job = async(Dispatchers.IO) { runCatching { BailianSpeech(config, wire).transcribe(byteArrayOf(1, 2)) } }
        val id = wire.run(); wire.event(id, "task-started")
        wire.sentence(id, "完整句。", true)
        wire.sentence(id, "还没识别完", false, 1000)
        wire.event(id, "task-finished")
        assertTrue(job.await().exceptionOrNull() is SpeechApiException)
    }
}
