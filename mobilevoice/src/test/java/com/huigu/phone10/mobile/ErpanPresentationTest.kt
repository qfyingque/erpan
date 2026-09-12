package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ErpanPresentationTest {
    @Test fun oldSettingsWithoutDisplayNameKeepVoiceIdAndCredentials() {
        val old = MobileSettings(SpeechConfig.bailianDefaults().copy(voice = "private-voice-id", ttsKey = "test-secret"), chatId = "old-chat")
        val restored = Gson().fromJson(Gson().toJson(old), MobileSettings::class.java)
        assertEquals("private-voice-id", restored.displayVoice())
        val named = restored.copy(voiceName = "  我的音色  ")
        assertEquals("我的音色", named.displayVoice())
        assertEquals(restored.speech, named.speech)
        assertEquals("old-chat", named.chatId)
    }
    @Test fun microphoneMutedDoesNotClaimWholeSessionEnded() {
        assertEquals("麦克风已关", VoiceState(true, "已关麦", false).homeStatus(true))
        assertEquals("未连接", VoiceState().homeStatus(true))
        assertEquals("尚未配置", VoiceState().homeStatus(false))
        assertEquals("等待回复", VoiceState(true, "等待 Operit 回复…", true).homeStatus(true))
        assertEquals("本轮异常", VoiceState(true, "语音合成失败", true).homeStatus(true))
        assertEquals("正在关麦", VoiceState(true, "正在关麦…", false, true).homeStatus(true))
        assertEquals("正在聆听", VoiceState(true, "正在聆听 · 智能结束判断", true).homeStatus(true))
    }
}
