package com.huigu.phone10.mobile

/** One short invitation, not a second voice session or model request. */
data class IncomingInvite(val id: String, val chatId: String, val caller: String,
    val text: String, val expiresAt: Long, val status: String = "RINGING")
data class IncomingResult(val code: String, val state: List<IncomingInvite>)

object IncomingBook {
    fun offer(state: List<IncomingInvite>, value: IncomingInvite, selectedChat: String,
        voiceRunning: Boolean, now: Long): IncomingResult {
        fun result(code: String) = IncomingResult(code, state)
        if (!value.id.matches(Regex("computer-[a-f0-9-]{36}")) || value.chatId.isBlank() ||
            value.chatId.length > 160 || value.caller.isBlank() || value.caller.length > 80 ||
            value.text.isBlank() || value.text.length > 8000 || value.status != "RINGING" ||
            value.expiresAt <= now || value.expiresAt - now > 60_000) return result("INVALID")
        state.find { it.id == value.id }?.let {
            return result(if (it.copy(status = "RINGING") == value) "DUPLICATE" else "CONFLICT")
        }
        if (value.chatId != selectedChat) return result("TARGET_CHANGED")
        if (voiceRunning || state.any { it.status in setOf("RINGING", "ACCEPTED") && it.expiresAt > now })
            return result("BUSY")
        return IncomingResult("RINGING", (state + value).takeLast(64))
    }
    fun finish(state: List<IncomingInvite>, id: String, action: String, now: Long): IncomingResult {
        val item = state.find { it.id == id } ?: return IncomingResult("UNKNOWN", state)
        val permitted = when (action) {
            "ACCEPTED", "REJECTED", "CANCELLED" -> item.status == "RINGING"
            "STARTED" -> item.status == "ACCEPTED"
            "FAILED" -> item.status in setOf("RINGING", "ACCEPTED", "STARTED")
            else -> false
        }
        if (!permitted) return IncomingResult("ALREADY_FINISHED", state)
        val next = if (item.expiresAt <= now) "EXPIRED" else action
        return IncomingResult(next, state.map { if (it.id == id) it.copy(status = next) else it })
    }
}
