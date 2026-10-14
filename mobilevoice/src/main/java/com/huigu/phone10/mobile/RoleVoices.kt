package com.huigu.phone10.mobile

/** Role names are the identity supplied by O's history API; bindings are chat-local. */
data class RoleVoiceBinding(val chatId: String, val roleName: String, val profileId: String)

internal fun MobileSettings.hasRoleVoices() = roleVoiceBindings.orEmpty().any { it.chatId == chatId }

internal fun MobileSettings.bindRoleVoice(role: String, profileId: String?): MobileSettings {
    val name = role.trim()
    require(chatId.isNotBlank() && name.isNotEmpty() && name.length <= 256 && '\n' !in name && '\r' !in name)
    require(profileId == null || profiles().any { it.id == profileId }) { "声音方案已不存在，请重新选择。" }
    val remaining = roleVoiceBindings.orEmpty().filterNot { it.chatId == chatId && it.roleName == name }
    require(remaining.size < 200 || profileId == null) { "最多保存 200 项成员绑定。" }
    return copy(roleVoiceBindings = remaining + if (profileId == null) emptyList() else listOf(RoleVoiceBinding(chatId,name,profileId)))
}

/** Return a snapshot for synthesis; call settings/recognition are never switched. */
internal fun MobileSettings.voiceForRole(role: String): MobileSettings {
    val binding = roleVoiceBindings.orEmpty().firstOrNull { it.chatId == chatId && it.roleName == role.trim() } ?: return this
    val profile = requireNotNull(profiles().firstOrNull { it.id == binding.profileId }) { "成员绑定的声音方案已删除，请重新绑定。" }
    return copy(speech=profile.speech,voiceName=profile.voiceName,activeVoiceProfileId=profile.id)
}
