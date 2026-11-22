package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationSpeechTest {
    @Test fun translationIsSilentAcrossEveryStreamBoundaryAndTimerFlush() {
        val input = "Hello.<translation>你好。不要朗读。</translation>Goodbye."
        for (cut in 0..input.length) {
            var now = 0L
            val filter = SpeechText(preserveSpacing = true) { now }
            val spoken = filter.push(input.take(cut)).toMutableList()
            now = 2000
            spoken += filter.flushReady()
            spoken += filter.push(input.drop(cut))
            spoken += filter.flush()
            assertEquals("split at $cut", "Hello.Goodbye.", spoken.joinToString(""))
        }
    }

    @Test fun unclosedTranslationNeverLeaksAtEndOfReply() {
        for (tail in listOf("<trans", "<translation>", "<translation>你好。")) {
            val filter = SpeechText()
            assertEquals("Hello.", (filter.push("Hello.$tail") + filter.flush()).joinToString(""))
        }
    }

    @Test fun attributesCaseAndNestedMarkupKeepTranslationHidden() {
        val filter = SpeechText()
        val input = "Yes.<TRANSLATION lang=\"zh>CN\">是<b>中文</b><translation>内层</translation>尾。</TRANSLATION>No."
        assertEquals("Yes.No.", (filter.push(input) + filter.flush()).joinToString(""))
    }

    @Test fun ordinaryChineseAndUnrelatedMarkupRemainSpeakable() {
        val filter = SpeechText()
        assertEquals("你好。Hello.2 < 3。", (filter.push("你好。<b>Hello.</b>2 < 3。") + filter.flush()).joinToString(""))
    }

    @Test fun snapshotsDoNotGainTranslationAsTagsArrive() {
        val input = "Hello.<translation>你好。</translation>Goodbye."
        var previous = ""
        for (end in 0..input.length) {
            val visible = ReplyListener.visibleText(input.take(end))
            assertTrue("snapshot at $end", visible.startsWith(previous))
            assertFalse(visible.contains("你好"))
            previous = visible
        }
        assertEquals("Hello.Goodbye.", previous)
    }

    @Test fun callAndListenKeepReplyTextButSendOnlyForeignTextToSpeech() = runBlocking {
        val raw = "Hello.<translation>你好。</translation>Goodbye."
        for (listen in listOf(false, true)) {
            val captions = StringBuilder()
            val speech = StringBuilder()
            val flow = VoiceConversation(this, { "test" }, { _, chunk ->
                raw.forEach { chunk(it.toString()) }
            }, {}, {}, {}, streamSpeak = { input ->
                for (part in input) speech.append(part)
            }, onReplyDelta = { captions.append(it) })
            val job = if (listen) flow.playReply(raw) else flow.submit(byteArrayOf(1))
            withTimeout(3000) { job.join() }
            assertEquals(raw, captions.toString())
            assertEquals("Hello.Goodbye.", speech.toString())
        }
    }

    @Test fun hiddenOnlyReplyDoesNotPoisonTheNextReply() = runBlocking {
        val speech = StringBuilder()
        val flow = VoiceConversation(this, { "test" }, { _, _ -> }, {}, {}, {},
            streamSpeak = { input -> for (part in input) speech.append(part) })
        withTimeout(3000) {
            flow.playReply("<translation>只有中文，缺少结束标签").join()
            flow.playReply("Next.").join()
        }
        assertEquals("Next.", speech.toString())
    }
}
