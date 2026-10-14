package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class FirstClauseTextTest {
    @Test fun firstCommaImmediatelyEmitsTinyGreetingThenCombinesLaterClauses() {
        val text = SpeechText(firstClauseMode = true)
        assertTrue(text.push("小").isEmpty())
        assertEquals(listOf("小盈，"), text.push("盈，"))
        assertTrue(text.push("我在。慢慢来，别着急。").isEmpty())
        assertEquals(listOf("我在。慢慢来，别着急。"), text.flush())
        assertTrue(text.flush().isEmpty())
    }

    @Test fun toolPauseKeepsUnpunctuatedTailUntilCompletion() {
        var now = 0L
        val text = SpeechText(firstClauseMode = true) { now }
        text.push("我来看看")
        now = 150
        assertTrue(text.flushReady().isEmpty())
        assertEquals(listOf("我来看看。"), text.push("。"))
        text.push("稍等我查一下")
        now = 1649
        assertTrue(text.flushReady().isEmpty())
        now = 1650
        assertTrue(text.flushReady().isEmpty())
        assertEquals(listOf("稍等我查一下查好了。"), text.run { push("查好了。"); flush() })
    }

    @Test fun longTextIsBoundedWithoutLosingOrDuplicatingCharactersOrSplittingEmoji() {
        val text = SpeechText(preserveSpacing = true, firstClauseMode = true)
        val body = "你好，" + "中".repeat(239) + "😀" + "文".repeat(521) + "。"
        val chunks = body.toList().flatMap { text.push(it.toString()) } + text.flush()
        assertEquals(body, chunks.joinToString(""))
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 600 })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test fun timerNeverSubmitsHalfOfEmojiAndHiddenToolMarkupIsNotRead() {
        var now = 0L
        val text = SpeechText(firstClauseMode = true) { now }
        assertEquals(listOf("好，"), text.push("<think>secret.</think>好，"))
        assertTrue(text.push("<tool>secret</tool>😀".dropLast(1)).isEmpty())
        now = 1500
        assertTrue(text.flushReady().isEmpty())
        text.push("\uDE00然后继续")
        assertEquals(listOf("😀然后继续"), text.flush())
    }

    @Test fun slowFirstWordIsNotSentUntilPunctuation() {
        var now = 0L
        val text = SpeechText(firstClauseMode = true) { now }
        assertTrue(text.push("我喜欢巧克").isEmpty())
        now = 2000
        assertTrue(text.flushReady().isEmpty())
        assertEquals(listOf("我喜欢巧克力。"), text.push("力。"))
        assertTrue(text.flush().isEmpty())
    }

    @Test fun timedFlushSendsCompleteClausesAndKeepsPartialWord() {
        var now = 0L
        val text = SpeechText(firstClauseMode = true) { now }
        assertEquals(listOf("宝宝，"), text.push("宝宝，"))
        assertTrue(text.push("先喝水。然后吃巧克").isEmpty())
        now = 1500
        assertEquals(listOf("先喝水。"), text.flushReady())
        now = 8000
        assertTrue(text.flushReady().isEmpty())
        text.push("力。")
        assertEquals(listOf("然后吃巧克力。"), text.flushReady())
        assertTrue(text.flush().isEmpty())
    }

    @Test fun length240DoesNotSplitWordInsideUnpunctuatedClause() {
        val text = SpeechText(firstClauseMode = true)
        assertEquals(listOf("你好，"), text.push("你好，"))
        val prefix = "中".repeat(238) + "巧克"
        assertTrue(text.push(prefix).isEmpty())
        assertEquals(listOf(prefix + "力。"), text.push("力。"))
        assertTrue(text.flush().isEmpty())
    }
}
