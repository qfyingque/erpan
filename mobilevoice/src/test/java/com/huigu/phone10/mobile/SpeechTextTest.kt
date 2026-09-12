package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun continuousTextKeepsWordBoundariesAcrossTimedFlushes() {
        var now = 0L
        val text = SpeechText(preserveSpacing = true) { now }
        text.push("Hello "); now = 150
        val first = text.flushReady()
        val rest = text.push("world. More words.") + text.flush()
        assertEquals("Hello world. More words.", (first + rest).joinToString(""))
    }

    @Test fun splitTagsNeverExposeReasoningOrToolPayload() {
        val text = SpeechText()
        val actual = listOf("<thi", "nk>secret. <tool name=\"x\">hidden</tool></th", "ink>你好，", "<tool_call>hide</tool_call>世界。")
            .flatMap(text::push) + text.flush()
        assertEquals(listOf("你好，", "世界。"), actual)
    }

    @Test fun incompleteHiddenContentIsDiscardedAtEnd() {
        val text = SpeechText()
        assertEquals(listOf("你好。"), text.push("你好。<think>secret."))
        assertTrue(text.flush().isEmpty())
        assertTrue(SpeechText().run { push("<thi"); flush() }.isEmpty())
    }

    @Test fun firstTextFlushesAfter150MillisecondsWithoutFurtherDelta() {
        var now = 0L
        val text = SpeechText { now }
        assertTrue(text.push("今天很开心").isEmpty())
        now = 149
        assertTrue(text.flushReady().isEmpty())
        now = 150
        assertEquals(listOf("今天很开心"), text.flushReady())
        assertTrue(text.flush().isEmpty())
    }

    @Test fun ordinaryMarkupAndComparisonStayReadable() {
        val text = SpeechText()
        assertEquals(listOf("你好，", "2 < 3。"), text.push("<b>**你好**，</b>2 < 3。"))
    }
}
