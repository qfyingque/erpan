package com.huigu.phone10.mobile

import java.util.concurrent.ConcurrentHashMap

/** No transcript, replay queue, or unlocked worker ownership is stored here. */
internal class OperitCancellationGate(private val settle: suspend (String, Boolean) -> Unit) {
    private val unsettled = ConcurrentHashMap<String, String>()

    suspend fun cancelAndAwait(chatId: String, requestId: String) {
        unsettled[chatId] = requestId
        settle(requestId, false)
        unsettled.remove(chatId, requestId)
    }

    suspend fun awaitReady(chatId: String) {
        val id = unsettled[chatId] ?: return
        settle(id, true)
        unsettled.remove(chatId, id)
    }
}

/** Locally fixed explanations; provider bodies and credentials are never included. */
internal class OperitBridgeFailure(val code: String) : IllegalStateException(code) {
    val userMessage: String get() = when(code) {
        "OPERIT_CANCEL_PENDING" -> "上一轮仍在结束，本句尚未发送，请稍后再试。"
        "OPERIT_PLUGIN_UPDATE_REQUIRED" -> "请同步更新 O 内的耳畔配套插件，再继续语音通话。"
        "OPERIT_BUSY" -> "Operit 正忙，请等上一轮结束后再说；本轮不会自动重发。"
        "OPERIT_TIMEOUT" -> "等待 Operit 回复超时；本轮不会自动重发，请先查看 O 中的消息。"
        else -> "Operit 回复失败，请检查连接与聊天状态；本轮不会自动重发。"
    }
}
