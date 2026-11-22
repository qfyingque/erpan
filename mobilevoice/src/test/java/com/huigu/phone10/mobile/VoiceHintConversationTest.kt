package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceHintConversationTest {
    private fun transcript() = SpeechTranscript.fromBailian("我想慢慢说", listOf(JsonParser.parseString("""{
        "words":[{"begin_time":0,"end_time":500},{"begin_time":1500,"end_time":2000}],
        "emo_tag":"negative","emo_confidence":0.92
    }""").asJsonObject))

    @Test fun eachSelectionOnlyAddsItsOwnMetadataToChatAndNeverToEndJudge() = runBlocking {
        for (emotion in listOf(false, true)) for (timing in listOf(false, true)) {
            val sent = mutableListOf<String>()
            val judged = mutableListOf<String>()
            var recognitions = 0
            val flow = VoiceConversation(this, { error("duplicate recognition") },
                { text, _ -> sent.add(text) }, {}, {}, {},
                transcribeDetailed = { recognitions++; transcript() },
                judgeEnd = { judged.add(it); true },
                sendEmotionHints = emotion, sendTimingHints = timing)
            withTimeout(3000) { flow.submit(byteArrayOf(1)).join() }
            assertEquals(1, recognitions)
            assertEquals(listOf("我想慢慢说"), judged)
            val message = sent.single()
            assertTrue(message.startsWith("我想慢慢说"))
            assertEquals(emotion, message.contains("疑似负面情感"))
            assertEquals(timing, message.contains("最长间隔约 1.00 秒"))
            assertEquals(timing, message.contains("语音时间跨度约 2.00 秒"))
            if (!emotion && !timing) assertEquals("我想慢慢说", message)
        }
    }

    @Test fun missingOrRejectedMetadataStillSendsExactlyTheSpokenWords() = runBlocking {
        val samples = listOf(SpeechTranscript("你好"), SpeechTranscript.fromBailian("你好", listOf(
            JsonParser.parseString("""{"emo_tag":"negative","emo_confidence":0.2,
                "begin_time":-1,"end_time":90000}""").asJsonObject)))
        for (sample in samples) {
            val sent = mutableListOf<String>()
            var recognitions = 0
            val flow = VoiceConversation(this, { error("duplicate recognition") },
                { text, _ -> sent.add(text) }, {}, {}, {}, transcribeDetailed = { recognitions++; sample },
                sendEmotionHints = true, sendTimingHints = true)
            withTimeout(3000) { flow.submit(byteArrayOf(1)).join() }
            assertEquals(listOf("你好"), sent)
            assertEquals(1, recognitions)
        }
    }

    @Test fun plainTextRecognitionDoesNotAcquireInventedHints() = runBlocking {
        val sent = mutableListOf<String>()
        var recognitions = 0
        val flow = VoiceConversation(this, { recognitions++; "我很开心" }, { text, _ -> sent.add(text) }, {}, {}, {},
            sendEmotionHints = true, sendTimingHints = true)
        flow.submit(byteArrayOf(1)).join()
        assertEquals(listOf("我很开心"), sent)
        assertEquals(1, recognitions)
    }

    @Test fun unchangedConfirmedDraftAddsSelectedMetadataOnlyOnce() = runBlocking {
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { error("duplicate recognition") },
            { text, _ -> sent.add(text) }, {}, {}, {}, transcribeDetailed = { transcript() },
            confirmBeforeSend = true, sendEmotionHints = true, sendTimingHints = false)
        flow.submit(byteArrayOf(1)).join()
        assertTrue(sent.isEmpty())
        val draft = requireNotNull(flow.pendingDraft())
        assertEquals("我想慢慢说", draft.text)
        requireNotNull(flow.confirmDraft(draft.id)).join()
        assertTrue(sent.single().contains("疑似负面情感"))
        assertFalse(sent.single().contains("时间跨度"))
        assertNull(flow.confirmDraft(draft.id))
    }

    @Test fun editedConfirmedDraftDropsTheOldAudioObservations() = runBlocking {
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { error("duplicate recognition") },
            { text, _ -> sent.add(text) }, {}, {}, {}, transcribeDetailed = { transcript() },
            confirmBeforeSend = true, sendEmotionHints = true, sendTimingHints = true)
        flow.submit(byteArrayOf(1)).join()
        val draft = requireNotNull(flow.pendingDraft())
        assertTrue(flow.editDraft(draft.id, "这是改过的话"))
        requireNotNull(flow.confirmDraft(draft.id)).join()
        assertEquals(listOf("这是改过的话"), sent)
    }

    @Test fun confirmedInterruptionUsesTheSameSelectedMetadata() = runBlocking {
        val sent = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val flow = VoiceConversation(this, { error("duplicate recognition") }, { text, _ ->
            sent.add(text)
            if (sent.size == 1) { started.complete(Unit); awaitCancellation() }
        }, {}, {}, {}, transcribeDetailed = { transcript() }, sendEmotionHints = false, sendTimingHints = true)
        val first = flow.submit(byteArrayOf(1))
        withTimeout(3000) { started.await() }
        flow.speechStarted()
        withTimeout(3000) { flow.submit(byteArrayOf(2)).join(); first.join() }
        assertEquals(2, sent.size)
        assertTrue(sent.last().contains("最长间隔约 1.00 秒"))
        assertFalse(sent.last().contains("疑似负面情感"))
        assertTrue(first.isCancelled)
    }
}
