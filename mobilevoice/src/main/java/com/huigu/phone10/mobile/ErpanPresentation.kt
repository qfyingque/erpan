package com.huigu.phone10.mobile

/** Display-only names never change the provider's voice ID or trigger a request. */
internal fun MobileSettings.displayVoice(): String = voiceName?.trim()?.takeIf { it.isNotEmpty() }
    ?: speech.voice.takeIf { it.isNotBlank() } ?: "未设置"

internal fun MobileSettings.displayChat(): String = chatTitle.takeIf { it.isNotBlank() }
    ?: if (chatId.isBlank()) "选择聊天" else "未命名聊天"

internal fun VoiceState.homeStatus(configured: Boolean): String = when {
    changing -> if (message.contains("关麦")) "正在关麦" else "正在开麦"
    !running -> if (configured) "未连接" else "尚未配置"
    !micEnabled -> "麦克风已关"
    message.contains("失败") || message.contains("不可用") -> "本轮异常"
    message.contains("播放") -> "正在播放"
    message.contains("等待 Operit") -> "等待回复"
    message.contains("识别") -> "正在识别"
    message.contains("正在判断") || message.contains("等你") -> "等待说完"
    else -> "正在聆听"
}
