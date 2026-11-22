package com.huigu.phone10.mobile

import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EmotionSupplementTest {
    private val pcm = byteArrayOf(1, 0, 2, 0)
    private fun source() = EmotionSourceConfig(enabled = true, key = "supplement-fixture")

    @Test fun disabledUnconfiguredOrNativeEmotionNeverMakesExtraPaidCall() = runBlocking {
        for ((enabled, source, native) in listOf(
            Triple(false, source(), SpeechTranscript("原文")),
            Triple(true, null, SpeechTranscript("原文")),
            Triple(true, source().copy(enabled = false), SpeechTranscript("原文")),
            Triple(true, source().copy(key = ""), SpeechTranscript("原文")),
            Triple(true, source(), SpeechTranscript("原文").withProviderEmotion("sad", "火山识别")))) {
            var extraCalls = 0
            val primary = native
            val result = EmotionRecognition(enabled = enabled, source = source,
                primary = { primary }, analyze = { _, _ -> extraCalls++; error("unexpected upload") }).transcribe(pcm)
            assertSame(primary, result)
            assertEquals(0, extraCalls)
        }
    }

    @Test fun eachNonNativeProviderKeepsOwnTranscriptionAndUsesOnlyOneAudioSupplement() = runBlocking {
        for (provider in listOf("mossland", "minimax", "openai", "bailian-token-plan", "bailian-paraformer")) {
            var primaryCalls = 0; var extraCalls = 0
            val result = EmotionRecognition(enabled = true, source = source(), primary = {
                primaryCalls++; assertSame(pcm, it); SpeechTranscript("$provider 的正确文字")
            }, analyze = { config, bytes ->
                extraCalls++; assertSame(pcm, bytes); assertEquals(source(), config)
                SpeechTranscript("不同的转写，不得替换").withProviderEmotion("happy", "Qwen ASR")
            }).transcribe(pcm)
            assertEquals("$provider 的正确文字", result.text)
            assertTrue(result.forChat(true, false).contains("愉快"))
            assertEquals(1, primaryCalls); assertEquals(1, extraCalls)
        }
    }

    @Test fun primaryFailureDoesNotUploadAgainOrHideFailure() = runBlocking {
        var extraCalls = 0
        val error = assertThrows(SpeechApiException::class.java) { runBlocking {
            EmotionRecognition(enabled = true, source = source(), primary = { throw SpeechApiException("主识别失败") },
                analyze = { _, _ -> extraCalls++; SpeechTranscript("不应发生") }).transcribe(pcm)
        } }
        assertEquals("主识别失败", error.message)
        assertEquals(0, extraCalls)
    }

    @Test fun extraFailureAndMissingEmotionLeavePlainWordsAndSafeDiagnostic() = runBlocking {
        for (fails in listOf(true, false)) {
            val logs = mutableListOf<String>()
            val primary = SpeechTranscript("只发原文")
            val result = EmotionRecognition(enabled = true, source = source(), primary = { primary },
                diagnose = { logs += it }, analyze = { _, _ ->
                    if (fails) throw SpeechApiException("supplement-fixture private words")
                    SpeechTranscript("没有情绪的另一个文字")
                }).transcribe(pcm)
            assertSame(primary, result)
            assertTrue(logs.any { "emotion_supplement_" in it })
            assertFalse(logs.joinToString().contains("supplement-fixture"))
            assertFalse(logs.joinToString().contains("private words"))
        }
    }

    @Test fun deadlineFallsBackWithoutRetryButParentCancellationStillCancels() = runBlocking {
        var attempts = 0
        val primary = SpeechTranscript("保留")
        val result = EmotionRecognition(enabled = true, source = source(), primary = { primary },
            timeoutMillis = 30, analyze = { _, _ -> attempts++; delay(500); error("too late") }).transcribe(pcm)
        assertSame(primary, result); assertEquals(1, attempts)
        val entered = CompletableDeferred<Unit>(); var canceled = false; var returned = false
        val job = launch {
            EmotionRecognition(enabled = true, source = source(), primary = { primary }, analyze = { _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } finally { canceled = true }
            }).transcribe(pcm)
            returned = true
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(canceled); assertFalse(returned)
    }

    @Test fun oldSettingsDoNotAutomaticallyEnableSupplementAndRoundTripPreservesVoice() {
        val gson = Gson()
        val original = MobileSettings(SpeechConfig.openAiDefaults(), sendEmotionHints = true, chatId = "same-chat")
        val old = gson.fromJson(gson.toJson(original), MobileSettings::class.java)
        assertNull(old.emotionSource)
        assertFalse(EmotionSourceConfig().enabled)
        val updated = original.copy(emotionSource = source())
        val restored = gson.fromJson(gson.toJson(updated), MobileSettings::class.java)
        assertEquals(source(), restored.emotionSource)
        assertEquals(original.speech, restored.speech)
        assertEquals(original.chatId, restored.chatId)
        assertFalse(source().toString().contains("supplement-fixture"))
        val switched = restored.copy(voiceProfiles = listOf(VoiceProfile("second", "另一声音",
            SpeechConfig.bailianDefaults(), null))).selectVoiceProfile("second")
        assertEquals(source(), switched.emotionSource)
    }

    @Test fun slowPrimaryCannotSpendTheInterruptionDeadlineOnExtraAnalysis() = runBlocking {
        var now = 0L; var extraCalls = 0
        val original = SpeechTranscript("已经识别成功的文字")
        val result = EmotionRecognition(enabled = true, source = source(), clockMillis = { now },
            primary = { now = 14_500; original }, analyze = { _, _ -> extraCalls++; error("must skip") }).transcribe(pcm)
        assertSame(original, result)
        assertEquals(0, extraCalls)
    }

    @Test fun confirmationEditAlwaysDiscardsSupplementalEmotion() = runBlocking {
        val result = EmotionRecognition(enabled = true, source = source(), primary = { SpeechTranscript("原文") },
            analyze = { _, _ -> SpeechTranscript("另一转写").withProviderEmotion("angry", "Qwen ASR") }).transcribe(pcm)
        assertTrue(result.forChat(true, false).contains("愤怒"))
        assertEquals("手动改字", result.withText("手动改字").forChat(true, true))
    }

    @Test fun mutingDuringPrimaryPreventsStartingALateSupplement() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<SpeechTranscript>()
        val original = SpeechTranscript("关麦前说完的话"); var extraCalls = 0
        val recognition = EmotionRecognition(enabled = true, source = source(), primary = {
            entered.complete(Unit); finish.await()
        }, analyze = { _, _ -> extraCalls++; SpeechTranscript("不该上传").withProviderEmotion("sad", "Qwen ASR") })
        val job = async { recognition.transcribe(pcm) }
        entered.await(); recognition.cancelSupplement(); finish.complete(original)
        assertSame(original, job.await()); assertEquals(0, extraCalls)
    }

    @Test fun mutingDuringSupplementCancelsOnlyAnalysisAndKeepsPrimaryWords() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var canceled = false
        val original = SpeechTranscript("已收到的原转写")
        val recognition = EmotionRecognition(enabled = true, source = source(), primary = { original }, analyze = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { canceled = true }
        })
        val job = async { recognition.transcribe(pcm) }
        entered.await(); recognition.cancelSupplement()
        val result = withTimeout(500) { job.await() }
        assertSame(original, result); assertTrue(canceled); assertFalse(result.hasEmotions)
    }

    @Test fun recognitionStartingAfterMuteStillCannotStartAnExtraUpload() = runBlocking {
        var extraCalls = 0
        val original = SpeechTranscript("队列中已有的原话")
        val recognition = EmotionRecognition(enabled = true, source = source(), primary = { original },
            analyze = { _, _ -> extraCalls++; SpeechTranscript("额外结果").withProviderEmotion("sad", "Qwen ASR") })
        recognition.cancelSupplement()
        val result = recognition.transcribe(pcm)
        assertSame(original, result)
        assertEquals(0, extraCalls)
    }

    @Test fun queuedAcceptedUtteranceKeepsMuteTicketEvenAfterUnmuting() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        var extraCalls = 0; val sent = mutableListOf<String>()
        val recognition = EmotionRecognition(enabled = true, source = source(), primary = { bytes ->
            if (bytes[0].toInt() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            } else SpeechTranscript("第二句原文")
        }, analyze = { _, _ -> extraCalls++; SpeechTranscript("别的文字").withProviderEmotion("happy", "Qwen ASR") })
        val flow = VoiceConversation(this, { error("duplicate") }, { text, _ -> sent += text }, {}, {}, {},
            prepareTranscription = recognition::prepare, onMicrophoneChanged = recognition::setMicrophoneEnabled,
            sendEmotionHints = true)
        val first = flow.submit(byteArrayOf(1, 0)); entered.await()
        val second = flow.submit(byteArrayOf(2, 0)); yield()
        flow.setMicrophoneEnabled(false); flow.setMicrophoneEnabled(true)
        cleanup.complete(Unit)
        withTimeout(1000) { second.join() }
        assertTrue(first.isCancelled)
        assertEquals(listOf("第二句原文"), sent)
        assertEquals(0, extraCalls)
    }
}
