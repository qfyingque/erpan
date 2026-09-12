package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceOptionsTest {
    @Test fun continuingSpeechMergesBeforeOneOSubmission() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val sends = mutableListOf<String>()
        val flow = VoiceConversation(this, { it.joinToString(",") }, { text, _ -> sends.add(text) }, {}, {}, {
            if (it.contains("等你")) waiting.complete(Unit)
        }, judgeEnd = { it.contains(",") }, continuationMillis = 100)
        val first = flow.submit(byteArrayOf(1))
        withTimeout(2000) { waiting.await() }
        flow.speechStarted()
        val second = flow.submit(byteArrayOf(2))
        first.join(); second.join()
        assertEquals(listOf("1,2"), sends)
    }
    @Test fun muteDiscardsIncompleteUtterance() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val sends = mutableListOf<String>()
        val flow = VoiceConversation(this, { it[0].toString() }, { t, _ -> sends.add(t) }, {}, {}, {
            if (it.contains("等你")) waiting.complete(Unit)
        }, judgeEnd = { it == "2" }, continuationMillis = 100)
        val first = flow.submit(byteArrayOf(1))
        waiting.await(); flow.interrupt(); first.join()
        flow.submit(byteArrayOf(2)).join()
        assertEquals(listOf("2"), sends)
    }
    @Test fun unavailableJudgementStillSendsExactlyOnceAndIsVisible() = runBlocking {
        val reports = mutableListOf<String>()
        var sends = 0
        val flow = VoiceConversation(this, { "hello" }, { _, _ -> sends++ }, {}, {}, reports::add, judgeEnd = { null })
        flow.submit(byteArrayOf(1)).join()
        assertEquals(1, sends)
        assertTrue(reports.any { it.contains("智能判断不可用") })
    }
    @Test fun incompleteSpeechEventuallySubmitsWithoutAnotherSound() = runBlocking {
        var sends = 0
        val flow = VoiceConversation(this, { "我想" }, { _, _ -> sends++ }, {}, {}, {}, judgeEnd = { false }, continuationMillis = 10)
        withTimeout(2000) { flow.submit(byteArrayOf(1)).join() }
        assertEquals(1, sends)
    }
}
