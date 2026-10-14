package com.huigu.phone10.mobile

import java.util.UUID

/** Named snapshots share the existing encrypted settings file, never a plaintext export. */
data class VoiceProfile(val id: String, val name: String, val speech: SpeechConfig, val voiceName: String?)

internal fun MobileSettings.profiles(): List<VoiceProfile> = voiceProfiles.orEmpty()

internal fun MobileSettings.currentVoiceProfile(): VoiceProfile? =
    if (activeVoiceProfileId != null) profiles().firstOrNull { it.id == activeVoiceProfileId }
    else profiles().firstOrNull { it.speech == speech && it.voiceName == voiceName }

/** Old settings used content matching and an opt-in mixing flag. */
internal fun MobileSettings.withCurrentVoiceDefaults(): MobileSettings =
    // Retired capture experiment must not survive an upgrade into ordinary calls.
    copy(gameAudioCoexist = true, activeVoiceProfileId = currentVoiceProfile()?.id, preserveBreath = false)

internal fun MobileSettings.saveVoiceProfile(name: String, replaceId: String? = null): MobileSettings {
    val title = name.trim()
    require(title.isNotBlank() && title.length <= 40 && '\n' !in title && '\r' !in title) { "方案名称请填写 1 至 40 个字。" }
    speech.validate(includeRecognition = !listenOnly)
    val existing = profiles()
    require(existing.none { it.name == title && it.id != replaceId }) { "已有同名方案，请换个名字；修改已有方案请先在首页选择它。" }
    require(replaceId == null || existing.any { it.id == replaceId }) { "这个方案已移除，请重新保存。" }
    require(replaceId != null || existing.size < 20) { "最多保存 20 套语音方案，请先移除不用的方案。" }
    val profile = VoiceProfile(replaceId ?: UUID.randomUUID().toString(), title, speech, voiceName)
    return copy(activeVoiceProfileId = profile.id,
        voiceProfiles = if (replaceId == null) existing + profile else existing.map { if (it.id == replaceId) profile else it })
}

internal fun MobileSettings.selectVoiceProfile(id: String): MobileSettings {
    val profile = requireNotNull(profiles().firstOrNull { it.id == id }) { "这个方案已移除，请重新选择。" }
    return copy(speech = profile.speech, voiceName = profile.voiceName, activeVoiceProfileId = id)
}

internal fun MobileSettings.removeVoiceProfile(id: String): MobileSettings =
    copy(voiceProfiles = profiles().filterNot { it.id == id },
        activeVoiceProfileId = activeVoiceProfileId.takeUnless { it == id })

internal fun VoiceProfile.description(): String {
    val provider = when (speech.effectiveTtsProvider) {
        SpeechConfig.BAILIAN -> "阿里云百炼"
        SpeechConfig.VOLCENGINE -> "火山引擎 / 豆包语音"
        SpeechConfig.MOSSLAND -> "模思 Mossland · " + when (speech.effectiveMossTextMode) {
            "coherent" -> "语气连贯"
            "whole" -> "整条合成"
            else -> "快速响应"
        }
        SpeechConfig.MINIMAX -> "MiniMax 官方"
        SpeechConfig.ELEVENLABS -> "ElevenLabs"
        SpeechConfig.QWEN_LOCAL -> if (speech.clientSegmentedTts) "本机语音 · 首句先读" else if (speech.wholeReplyTts) "本机语音 · 整段" else "本机语音"
        else -> "Audio API 兼容"
    }
    return "$provider · ${voiceName?.takeIf { it.isNotBlank() } ?: "已保存音色"}"
}
