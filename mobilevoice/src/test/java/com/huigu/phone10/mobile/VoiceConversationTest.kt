package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class VoiceConversationTest {
    @Test fun disabledVoiceInterruptionIgnoresSpeechDuringThinkingAndPlaybackThenResumes() = runBlocking {
        val thinking = CompletableDeferred<Unit>()
        val finishThinking = CompletableDeferred<Unit>()
        val playing = CompletableDeferred<Unit>()
        val finishPlaying = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        var recognitions = 0
        val flow = VoiceConversation(this, { recognitions++; "第${it[0]}句" }, { text, chunk ->
            sent.add(text)
            if (sent.size == 1) { thinking.complete(Unit); finishThinking.await(); chunk("回复。") }
        }, { playing.complete(Unit); finishPlaying.await() }, {}, {})
        flow.setVoiceInterruptionEnabled(false)
        val first = flow.submit(byteArrayOf(1))
        thinking.await()
        assertFalse(flow.acceptsSpeech())
        flow.speechStarted(); flow.submit(byteArrayOf(2)).join()
        assertTrue(first.isActive)
        finishThinking.complete(Unit); playing.await()
        flow.speechStarted(); flow.submit(byteArrayOf(3)).join()
        assertTrue(first.isActive)
        finishPlaying.complete(Unit); first.join()
        assertTrue(flow.acceptsSpeech())
        flow.speechStarted(); flow.submit(byteArrayOf(4)).join()
        assertEquals(listOf("第1句", "第4句"), sent)
        assertEquals(2, recognitions)
    }

    @Test fun disablingAutomaticInterruptionStillAllowsManualStopAndReenabling() = runBlocking {
        val begun = Channel<Unit>(Channel.UNLIMITED)
        val flow = VoiceConversation(this, { "你好" }, { _, _ -> begun.send(Unit); awaitCancellation() }, {}, {}, {})
        flow.setVoiceInterruptionEnabled(false)
        val first = flow.submit(byteArrayOf(1)); begun.receive()
        flow.interrupt(); first.join()
        assertTrue(first.isCancelled)
        val second = flow.submit(byteArrayOf(2)); begun.receive()
        flow.setVoiceInterruptionEnabled(true)
        flow.speechStarted(); second.join()
        assertTrue(second.isCancelled)
    }

    @Test fun speechContextIsOffByDefaultAndOnlySpokenTextReachesChatAndJudge() = runBlocking {
        var recognitions = 0
        val judged = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val speech = SpeechTranscript.fromBailian("你好", listOf(
            com.google.gson.JsonParser.parseString("""{"emo_tag":"positive","emo_confidence":0.9}""").asJsonObject))
        val conversation = VoiceConversation(this, { error("must not recognize twice") },
            { text, _ -> sent.add(text) }, {}, {}, {},
            judgeEnd = { judged.add(it); true },
            transcribeDetailed = { recognitions++; speech })
        conversation.submit(byteArrayOf(1, 2)).join()
        assertEquals(1, recognitions)
        assertEquals(listOf("你好"), judged)
        assertEquals(listOf("你好"), sent)
        assertFalse(sent.single().contains("声音线索"))
    }

    @Test fun continuousSynthesisUsesOneConsumerWhileModelIsStillWriting() = runBlocking {
        val firstSpoken = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        var calls = 0
        val conversation = VoiceConversation(this, { "继续说" }, { _, chunk ->
            chunk("第一句。")
            withTimeout(2000) { firstSpoken.await() }
            repeat(50) { chunk("第${it}句，继续。") }
        }, { fail("must not open per-segment synthesis") }, {}, {}, streamSpeak = { input ->
            calls++
            for (part in input) { spoken.add(part); firstSpoken.complete(Unit) }
        })
        withTimeout(3000) { conversation.submit(byteArrayOf(1)).join() }
        assertEquals(1, calls)
        assertEquals("第一句。" + (0 until 50).joinToString("") { "第${it}句，继续。" }, spoken.joinToString(""))
    }

    @Test fun interruptCancelsContinuousSpeechWithoutStartingAnotherTask() = runBlocking {
        val speaking = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val conversation = VoiceConversation(this, { "你好" }, { _, chunk ->
            chunk("第一句。"); awaitCancellation()
        }, { fail("must not use segmented playback") }, {}, {}, streamSpeak = { input ->
            input.receive(); speaking.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        })
        val job = conversation.submit(byteArrayOf(1))
        withTimeout(2000) { speaking.await() }
        conversation.interrupt()
        withTimeout(2000) { job.join(); stopped.await() }
    }

    @Test fun slowSpeechDoesNotBlockReceivingAFastLongReply() = runBlocking {
        val modelDone = CompletableDeferred<Unit>()
        var spoken = 0
        val conversation = VoiceConversation(this, { "讲一个故事" }, { _, chunk ->
            repeat(200) { chunk("这是第${it}句。") }
            modelDone.complete(Unit)
        }, { withTimeout(1500) { modelDone.await() }; spoken++ }, {}, {})
        withTimeout(3000) { conversation.submit(byteArrayOf(1)).join() }
        assertEquals(200, spoken)
    }

    @Test fun cancellationDoesNotReportAStaleNetworkError() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reports = mutableListOf<String>()
        val conversation = VoiceConversation(this, {
            started.complete(Unit)
            try { awaitCancellation() }
            catch (_: CancellationException) { throw java.io.IOException("closed transport") }
        }, { _, _ -> }, {}, {}, reports::add)
        val job = conversation.submit(byteArrayOf(1))
        withTimeout(2000) { started.await() }
        conversation.interrupt()
        job.join()
        assertFalse(reports.last().contains("失败"))
    }

    @Test fun emptyRecognitionDoesNotCallO() = runBlocking {
        var sends = 0
        val conversation = VoiceConversation(this, { "  " }, { _, _ -> sends++ }, {}, {}, {})
        conversation.submit(byteArrayOf(1)).join()
        assertEquals(0, sends)
    }

    @Test fun responseBeginsSpeakingBeforeOFinishesAndKeepsOrder() = runBlocking {
        val firstSpoken = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val conversation = VoiceConversation(this, { "你好" }, { _, chunk ->
            chunk("你好呀。")
            withTimeout(2000) { firstSpoken.await() }
            chunk("接着说。")
        }, { text -> spoken.add(text); firstSpoken.complete(Unit) }, {}, {})
        conversation.submit(byteArrayOf(1)).join()
        assertEquals(listOf("你好呀。", "接着说。"), spoken)
    }

    @Test fun interruptionWaitsForOldCancellationBeforeNewSend() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val conversation = VoiceConversation(this, { it[0].toString() }, { text, _ ->
            order.add("start$text")
            if (text == "1") try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { delay(20); order.add("cancel1") } }
        }, {}, {}, {})
        conversation.submit(byteArrayOf(1))
        withTimeout(2000) { started.await() }
        conversation.interrupt()
        conversation.submit(byteArrayOf(2)).join()
        assertEquals(listOf("start1", "cancel1", "start2"), order)
    }

    @Test fun modelFailureIsReportedWithoutRetry() = runBlocking {
        var calls = 0
        val reports = mutableListOf<String>()
        val conversation = VoiceConversation(this, { "hello" }, { _, _ ->
            calls++; throw IllegalStateException("provider body must not leak")
        }, {}, {}, reports::add)
        conversation.submit(byteArrayOf(1)).join()
        assertEquals(1, calls)
        assertTrue(reports.last().contains("失败"))
        assertFalse(reports.last().contains("provider body"))
    }

    @Test fun firstTextWithoutPunctuationIsFlushedWithoutWaitingForModelCompletion() = runBlocking {
        val spoken = CompletableDeferred<String>()
        val conversation = VoiceConversation(this, { "你好" }, { _, chunk ->
            chunk("这是一句没有标点的话")
            withTimeout(2000) { spoken.await() }
        }, { spoken.complete(it) }, {}, {})
        conversation.submit(byteArrayOf(1)).join()
        assertEquals("这是一句没有标点的话", spoken.await())
    }
}
