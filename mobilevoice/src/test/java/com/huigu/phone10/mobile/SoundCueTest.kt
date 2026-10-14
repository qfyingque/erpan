package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class SoundCueTest {
    @Test fun retiredCaptureCannotSurviveLoadingButOtherPreferencesRemain() {
        val stored = MobileSettings(SpeechConfig.bailianDefaults(), preserveBreath = true,
            confirmBeforeSend = true, disableVoiceInterruption = true, captionsEnabled = true)
        val loaded = stored.withCurrentVoiceDefaults()
        assertFalse(loaded.preserveBreath)
        assertEquals(stored.copy(preserveBreath = false).withCurrentVoiceDefaults(), loaded)
        assertEquals(stored.speech, loaded.speech)
        assertTrue(loaded.confirmBeforeSend)
        assertTrue(loaded.disableVoiceInterruption)
        assertTrue(loaded.captionsEnabled)
    }

    @Test fun overlappingLabelsStillConfirmTheContinuouslySupportedCategory() {
        val gate = SoundCueGate()
        assertNull(gate.accept(0, 0.87f, 0.93f))
        assertEquals(SoundCue.BREATH, gate.accept(480, 0.72f, 0.61f))
    }

    @Test fun alternatingUnsharedCategoriesDoNotConfirm() {
        val gate = SoundCueGate()
        assertNull(gate.accept(0, 0.1f, 0.9f))
        assertNull(gate.accept(480, 0.8f, 0.1f))
        assertNull(gate.accept(960, 0.1f, 0.9f))
        assertEquals(SoundCue.SIGH, gate.accept(1440, 0.1f, 0.9f))
    }

    @Test fun isolatedSpikeDoesNotSendAndContinuousBreathingDoesNotSpam() {
        val gate = SoundCueGate()
        assertNull(gate.accept(0, 0.8f, 0f))
        assertNull(gate.accept(480, 0f, 0f))
        assertNull(gate.accept(960, 0.8f, 0f))
        assertEquals(SoundCue.BREATH, gate.accept(1440, 0.8f, 0f))
        for (t in 1920L..20000L step 480) assertNull(gate.accept(t, 0.8f, 0f))
        for (t in 20480L..21440L step 480) gate.accept(t, 0f, 0f)
        assertNull(gate.accept(21920, 0.8f, 0f))
        assertEquals(SoundCue.BREATH, gate.accept(22400, 0.8f, 0f))
    }

    @Test fun gapsAndInvalidScoresCannotConfirmAStaleDetection() {
        val gate = SoundCueGate()
        assertNull(gate.accept(0, 0.8f, 0f))
        assertNull(gate.accept(5000, 0.8f, 0f))
        assertNull(gate.accept(5480, Float.NaN, 0f))
        assertNull(gate.accept(5960, 0.8f, 0f))
        gate.reset()
        assertNull(gate.accept(6440, 0.8f, 0f))
    }

    @Test fun pcmWindowsPreserveSampleOrderAcrossOddByteChunks() {
        val source = ShortArray(23280) { (it % 20000 - 10000).toShort() }
        val bytes = ByteArray(source.size * 2)
        source.forEachIndexed { i, s -> bytes[2*i]=s.toByte(); bytes[2*i+1]=(s.toInt() shr 8).toByte() }
        val windows = mutableListOf<FloatArray>()
        val buffer = SoundCueWindows { windows.add(it) }
        bytes.toList().chunked(511).forEach { buffer.accept(it.toByteArray()) }
        assertEquals(2, windows.size)
        for (w in windows.indices) for (i in windows[w].indices)
            assertEquals(source[i+w*7680]/32768f, windows[w][i], 0f)
        buffer.reset(); buffer.accept(ByteArray(1000))
        assertEquals(2, windows.size)
        buffer.accept(ByteArray(30200))
        assertEquals(3, windows.size)
        assertTrue(windows.last().all { it == 0f })
    }

    @Test fun soundWithoutWordsUsesExistingReplyAndHonorsConfirmation() = runBlocking {
        for (confirm in listOf(false, true)) {
            val sent = mutableListOf<String>()
            val flow = VoiceConversation(this, { fail("sound must not require STT"); "" },
                { text, _ -> sent.add(text) }, {}, {}, {}, confirmBeforeSend = confirm,
                soundCuesEnabled = true)
            flow.observeSoundCue(SoundCue.SIGH)
            delay(800)
            if (confirm) {
                assertTrue(sent.isEmpty())
                flow.confirmDraft(requireNotNull(flow.pendingDraft()).id)!!.join()
            }
            assertEquals(1, sent.size)
            assertTrue(sent.single().contains("叹气"))
            assertTrue(sent.single().contains("疑似"))
        }
    }

    @Test fun soundNeverInterruptsAnAcceptedReplyOrSurvivesMute() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        var stops = 0
        val flow = VoiceConversation(this, { "你好" }, { text, _ ->
            sent.add(text); waiting.complete(Unit); finish.await()
        }, {}, { stops++ }, {}, soundCuesEnabled = true)
        val job = flow.submit(byteArrayOf(1))
        waiting.await()
        flow.setVoiceInterruptionEnabled(true)
        assertFalse("Breath capture must not feed noisy ASR into interruption", flow.acceptsSpeech())
        val stopsBefore = stops
        flow.observeSoundCue(SoundCue.BREATH)
        delay(800)
        assertTrue(job.isActive)
        assertEquals(stopsBefore, stops)
        finish.complete(Unit); job.join()
        flow.observeSoundCue(SoundCue.BREATH)
        flow.setMicrophoneEnabled(false)
        delay(800)
        assertEquals(listOf("你好"), sent)
    }

    @Test fun soundBeforeSpeechJoinsThatMessageInsteadOfSendingTwice() = runBlocking {
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { "我回来了" }, { text, _ -> sent.add(text) },
            {}, {}, {}, soundCuesEnabled = true)
        flow.observeSoundCue(SoundCue.BREATH)
        flow.speechStarted()
        flow.submit(byteArrayOf(1)).join()
        delay(800)
        assertEquals(1, sent.size)
        assertTrue(sent.single().startsWith("我回来了"))
        assertTrue(sent.single().contains("呼吸"))
    }

    @Test fun emptyRecognitionCanStillDeliverSoundButOffIsUnchanged() = runBlocking {
        for (enabled in listOf(true, false)) {
            val sent = mutableListOf<String>()
            val flow = VoiceConversation(this, { throw NoSpeechRecognized() }, { text, _ -> sent.add(text) },
                {}, {}, {}, soundCuesEnabled = enabled)
            flow.observeSoundCue(SoundCue.BREATH); flow.speechStarted()
            flow.submit(byteArrayOf(1)).join()
            delay(800)
            assertEquals(if (enabled) 1 else 0, sent.size)
        }
    }

    @Test fun oldSettingsDefaultToNormalCaptureAndKeepTheStoredInterruptionPreference() {
        val gson = com.google.gson.Gson()
        val previous = gson.toJsonTree(MobileSettings(SpeechConfig.bailianDefaults())).asJsonObject
        previous.remove("preserveBreath")
        val settings = gson.fromJson(previous, MobileSettings::class.java)
        assertFalse(settings.preserveBreath)
        val experiment = settings.copy(preserveBreath = true)
        assertFalse(experiment.disableVoiceInterruption)
        assertFalse(experiment.copy(preserveBreath = false).disableVoiceInterruption)
    }

    @Test fun stopCancelsAnUnsentSoundAndDoesNotReplayItOnNextSpeech() = runBlocking {
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { "下一句" }, { text, _ -> sent.add(text) },
            {}, {}, {}, soundCuesEnabled = true)
        flow.observeSoundCue(SoundCue.BREATH)
        flow.interrupt()
        delay(800)
        assertTrue(sent.isEmpty())
        flow.submit(byteArrayOf(1)).join()
        assertEquals(listOf("下一句"), sent)
    }
}
