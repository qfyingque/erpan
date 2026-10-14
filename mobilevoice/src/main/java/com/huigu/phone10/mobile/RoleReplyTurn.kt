package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

/** Send exactly once through the existing owner; speech reads that chat's attributed history. */
internal suspend fun playRoleReplyTurn(
    text: String,
    read: suspend (Long?) -> ReplySnapshot,
    send: suspend (String) -> Unit,
    render: suspend (String, ReceiveChannel<String>) -> Unit,
    stop: () -> Unit,
    report: (String) -> Unit,
    intervalMillis: Long = 500,
) = coroutineScope {
    val baseline = read(null)
    check(baseline.roleMetadata) { "OPERIT_PLUGIN_UPDATE_REQUIRED" }
    check(!baseline.processing && !baseline.failed) { "OPERIT_BUSY" }
    val sender = async { send(text) }
    val listener = ReplyListener(read, { error("role player required") }, stop, report, intervalMillis,
        playRole = { role, chunks -> launch { render(role, chunks) } },
        initialSnapshot = baseline, finishWhenIdle = { sender.isCompleted })
    try {
        try {
            listener.run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            // The optional history reader must not cancel the accepted model task.
            report("群聊消息读取中断，已停止朗读；Operit 原任务继续。")
            sender.await()
            throw error
        }
        sender.await()
    } finally { sender.cancelAndJoin() }
}
