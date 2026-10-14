package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SoundLibraryRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun repeatedStarDoesNotDuplicateTheSameRecording() {
        val store = VoiceKeepsakes(File(folder.root,"recent"), File(folder.root,"saved"))
        val capture = store.begin("voice")
        capture.segment("hello")
        capture.accept(byteArrayOf(1,2,3,4),0,4)
        val clip = capture.finish(2,true,"hello")!!
        val first = store.save(clip.id)
        assertEquals(first.id, store.save(clip.id).id)
        assertEquals(1, store.saved().size)
        assertArrayEquals(byteArrayOf(1,2,3,4), store.audio(clip.id,false).readBytes().drop(44).toByteArray())
    }

    @Test fun emptySentenceIsReportedInsteadOfSilentlySkippingToTheNextOne() = runBlocking {
        val input = Channel<String>(2).apply { trySend("missing"); trySend("next"); close() }
        val requested = mutableListOf<String>()
        var failed = false
        try {
            SentenceSpeech { text, rate, pcm ->
                requested += text; rate(24000)
                if (text == "next") pcm(byteArrayOf(1,2))
            }.speak(input,{}) {}
        } catch (_: SpeechApiException) { failed = true }
        assertTrue("empty synthesis must not be accepted", failed)
        assertEquals(listOf("missing"), requested)
    }

    @Test fun oddByteTailCannotJoinTheNextSentenceAndShiftPcmSamples() = runBlocking {
        val input = Channel<String>(2).apply { trySend("broken"); trySend("next"); close() }
        val requested = mutableListOf<String>()
        var failed = false
        try {
            SentenceSpeech { text, rate, pcm ->
                requested += text; rate(24000); pcm(byteArrayOf(1))
            }.speak(input,{}) {}
        } catch (_: SpeechApiException) { failed = true }
        assertTrue("an odd tail must not be treated as complete audio", failed)
        assertEquals(listOf("broken"), requested)
    }
}
