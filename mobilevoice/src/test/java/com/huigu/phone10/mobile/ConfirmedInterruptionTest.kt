package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConfirmedInterruptionTest {
    @Test fun manualStopDiscardsPendingRecognitionWithoutAnotherSend() = runBlocking {
        val oldStarted=CompletableDeferred<Unit>()
        val asrStarted=CompletableDeferred<Unit>()
        var sends=0
        val flow=VoiceConversation(this,{
            if(it[0]==1.toByte()) "旧话" else {asrStarted.complete(Unit);awaitCancellation()}
        },{_,_->sends++;oldStarted.complete(Unit);awaitCancellation()},{},{},{})
        val old=flow.submit(byteArrayOf(1));oldStarted.await()
        val candidate=flow.submit(byteArrayOf(2));asrStarted.await()
        flow.interrupt()
        withTimeout(2000) { candidate.join();old.join() }
        assertEquals(1,sends)
        assertTrue(flow.acceptsSpeech())
    }
    @Test fun confirmedInterruptionStillUsesSmartEndJudgement() = runBlocking {
        val started=CompletableDeferred<Unit>()
        val judged=mutableListOf<String>()
        val sent=mutableListOf<String>()
        val flow=VoiceConversation(this,{ if(it[0]==1.toByte()) "旧话" else "新话" },
            { text,_ -> sent+=text; if(text=="旧话") { started.complete(Unit);awaitCancellation() } },
            {},{},{},judgeEnd={judged+=it;true})
        try {
            flow.submit(byteArrayOf(1));started.await()
            withTimeout(2000) { flow.submit(byteArrayOf(2)).join() }
            assertEquals(listOf("旧话","新话"),judged)
            assertEquals(judged,sent)
        } finally {flow.interrupt()}
    }
    @Test fun falseOnsetAndEmptyRecognitionLeaveOriginalReplyAlive() = runBlocking {
        val replying = CompletableDeferred<Unit>()
        var stopped = 0
        val flow = VoiceConversation(this, { if (it[0] == 1.toByte()) "你好" else "" },
            { _, _ -> replying.complete(Unit); awaitCancellation() }, {}, { stopped++ }, {})
        try {
            val original = flow.submit(byteArrayOf(1)); replying.await()
            val before = stopped
            flow.speechStarted()
            assertTrue("VAD onset must not kill the reply", original.isActive)
            withTimeout(2000) { flow.submit(byteArrayOf(2)).join() }
            assertTrue(original.isActive)
            assertEquals(before, stopped)
        } finally { flow.interrupt() }
    }
    @Test fun recognitionFailureAndMutedLateCandidateCannotCancelOriginal() = runBlocking {
        val replying = CompletableDeferred<Unit>()
        val startedRecognition = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val flow = VoiceConversation(this, {
            when (++attempts) {
                1 -> "你好"
                2 -> throw IllegalStateException("offline")
                else -> { startedRecognition.complete(Unit); release.await(); "新话" }
            }
        }, { _, _ -> replying.complete(Unit); awaitCancellation() }, {}, {}, {})
        try {
            val original = flow.submit(byteArrayOf(1)); replying.await()
            withTimeout(2000) { flow.submit(byteArrayOf(2)).join() }
            assertTrue(original.isActive)
            val candidate = flow.submit(byteArrayOf(3)); startedRecognition.await()
            flow.setMicrophoneEnabled(false); release.complete(Unit)
            withTimeout(2000) { candidate.join() }
            assertTrue(original.isActive)
        } finally { flow.interrupt() }
    }
    @Test fun confirmedWordsWaitForCancellationCleanupAndSendExactlyOnce() = runBlocking {
        val replying = CompletableDeferred<Unit>()
        val recognized = CompletableDeferred<Unit>()
        val sends = mutableListOf<String>()
        var cleaned = false
        val flow = VoiceConversation(this, {
            if (it[0] == 1.toByte()) "旧话" else { recognized.await(); "新话" }
        }, { text, _ ->
            sends += text
            if (text == "旧话") try { replying.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { delay(20); cleaned = true } }
            else assertTrue(cleaned)
        }, {}, {}, {})
        try {
            val original = flow.submit(byteArrayOf(1)); replying.await()
            flow.speechStarted(); val candidate = flow.submit(byteArrayOf(2))
            assertTrue(original.isActive)
            recognized.complete(Unit)
            withTimeout(2000) { candidate.join(); original.join() }
            assertEquals(listOf("旧话", "新话"), sends)
        } finally { flow.interrupt() }
    }
}
