package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class PcmRecorderTest {
    @Test fun blockedAudioCannotTimeoutOrLeakItsTailIntoNextUtterance() {
        val segmenter = UtteranceSegmenter()
        val utterances = mutableListOf<ByteArray>()
        var starts = 0
        repeat(1100) { segmenter.accept(frame(9), true, { starts++ }, utterances::add, false) }
        repeat(6) { segmenter.accept(frame(9), true, { starts++ }, utterances::add, true) }
        assertEquals(0, starts)
        segmenter.accept(frame(0), false, { starts++ }, utterances::add)
        repeat(4) { segmenter.accept(frame(2), true, { starts++ }, utterances::add) }
        repeat(18) { segmenter.accept(frame(0), false, { starts++ }, utterances::add) }
        assertEquals(1, starts)
        assertEquals(1, utterances.size)
        assertFalse(utterances.single().contains(9.toByte()))
    }

    private fun frame(value: Byte) = ByteArray(1024) { value }

    @Test fun noiseDoesNotSubmitAndSpeechIncludesPreRoll() {
        val segmenter = UtteranceSegmenter()
        var starts = 0
        val utterances = mutableListOf<ByteArray>()
        fun feed(value: Byte, speech: Boolean) = segmenter.accept(frame(value), speech, { starts++ }, utterances::add)
        repeat(10) { feed(1, false) }
        feed(2, true)
        repeat(20) { feed(0, false) }
        assertEquals(0, starts)
        assertTrue(utterances.isEmpty())
        repeat(6) { feed(3, true) }
        repeat(18) { feed(0, false) }
        assertEquals(1, starts)
        assertEquals(1, utterances.size)
        assertEquals(0.toByte(), utterances.single()[0])
        assertTrue(utterances.single().contains(3.toByte()))
    }

    @Test fun continuousSpeechFailsAt30SecondsWithoutSendingPartialUtterance() {
        val segmenter = UtteranceSegmenter()
        val utterances = mutableListOf<ByteArray>()
        assertThrows(UtteranceTooLongException::class.java) {
            repeat(1000) { segmenter.accept(frame(7), true, {}, utterances::add) }
        }
        assertTrue(utterances.isEmpty())
    }

    @Test fun silenceBoundaryAndNewUtteranceKeepSeparateAudio() {
        val segmenter = UtteranceSegmenter()
        val utterances = mutableListOf<ByteArray>()
        var starts = 0
        fun feed(value: Byte, speech: Boolean) = segmenter.accept(frame(value), speech, { starts++ }, utterances::add)
        repeat(3) { feed(1, true) }
        repeat(17) { feed(0, false) }
        assertTrue(utterances.isEmpty())
        feed(0, false)
        assertEquals(1, utterances.size)
        repeat(3) { feed(2, true) }
        repeat(18) { feed(0, false) }
        assertEquals(2, starts)
        assertEquals(2, utterances.size)
        assertFalse(utterances[0].contains(2.toByte()))
        assertFalse(utterances[1].contains(1.toByte()))
    }
}
