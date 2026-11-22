package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VoiceHintSettingsTest {
    private val gson = Gson()

    @Test fun savedPayloadRetainsEachIndependentChoiceAcrossVoiceSwitch() {
        for ((emotion, timing) in listOf(true to false, false to true, true to true)) {
            val input = gson.toJsonTree(MobileSettings(SpeechConfig.bailianDefaults(),
                chatId = "chosen-chat")).asJsonObject.apply {
                addProperty("sendEmotionHints", emotion)
                addProperty("sendTimingHints", timing)
            }
            val restored = gson.fromJson(input, MobileSettings::class.java)
                .copy(voiceProfiles = listOf(VoiceProfile("other", "另一声音", SpeechConfig.openAiDefaults(), "第二声音")))
                .selectVoiceProfile("other").withCurrentVoiceDefaults()
            val output = gson.toJsonTree(restored).asJsonObject
            assertTrue("emotion preference was discarded", output.has("sendEmotionHints"))
            assertTrue("timing preference was discarded", output.has("sendTimingHints"))
            assertEquals(emotion, output["sendEmotionHints"].asBoolean)
            assertEquals(timing, output["sendTimingHints"].asBoolean)
            assertEquals("chosen-chat", restored.chatId)
        }
    }

    @Test fun upgradingLegacyPayloadDoesNotOptInOrChangeCredentials() {
        val original = MobileSettings(SpeechConfig.openAiDefaults().copy(sttKey = "local-fixture", ttsKey = "test-only"),
            chatId = "same-chat", confirmBeforeSend = true)
        val legacy = gson.toJsonTree(original).asJsonObject.apply {
            remove("sendEmotionHints"); remove("sendTimingHints")
        }
        val restored = gson.fromJson(legacy, MobileSettings::class.java).withCurrentVoiceDefaults()
        val output = gson.toJsonTree(restored).asJsonObject
        assertTrue("missing explicit default", output.has("sendEmotionHints"))
        assertFalse(output["sendEmotionHints"].asBoolean)
        assertFalse(output["sendTimingHints"].asBoolean)
        assertEquals(original.speech, restored.speech)
        assertEquals(original.chatId, restored.chatId)
        assertTrue(restored.confirmBeforeSend)
    }
}
