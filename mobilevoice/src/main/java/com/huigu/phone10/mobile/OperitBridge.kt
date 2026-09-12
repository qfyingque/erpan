package com.huigu.phone10.mobile

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class OperitChat(val id: String, val title: String)

/** A protocol/channel failure is not a user interruption. */
internal suspend fun consumeOperitEvents(pending: OperitPending, timeout: Long, consume: suspend (JsonObject) -> Unit) {
    try {
        withTimeout(timeout) {
            for (event in pending.events) {
                currentCoroutineContext().ensureActive()
                when (event["type"].asString) {
                    "error" -> error(event["code"]?.asString ?: "OPERIT_UNAVAILABLE")
                    "complete" -> return@withTimeout
                    else -> consume(event)
                }
            }
            error(pending.failure ?: "OPERIT_INCOMPLETE")
        }
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException(pending.failure ?: if (cancelled is TimeoutCancellationException) "OPERIT_TIMEOUT" else "OPERIT_UNAVAILABLE")
    }
}

/** Local IPC only: no model credentials, network server, history or retry queue. */
class OperitBridge(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val active = ConcurrentHashMap<OperitPending, Job>()
    @Volatile private var closed = false

    suspend fun listChats(): List<OperitChat> {
        val chats = mutableListOf<OperitChat>()
        execute(OperitPending("list"), 20_000L) { event ->
            if (event["type"].asString == "chats") {
                val batch = event["chats"]?.asJsonArray ?: error("OPERIT_INVALID_EVENT")
                if (batch.size() > 40 || chats.size + batch.size() > 2000) error("OPERIT_INVALID_EVENT")
                for (item in batch) {
                    val value = item.asJsonObject
                    val id = value["id"].asString
                    val title = value["title"].asString
                    if (id.isBlank() || id.length > 160 || title.length > 256) error("OPERIT_INVALID_EVENT")
                    chats += OperitChat(id, title)
                }
            }
        }
        return chats.distinctBy { it.id }
    }

    suspend fun reply(chatId: String, text: String, onChunk: suspend (String) -> Unit) {
        require(chatId.isNotBlank() && chatId.length <= 160 && text.isNotBlank() && text.length <= 60_000) {
            "OPERIT_INVALID_REQUEST"
        }
        execute(OperitPending("reply", chatId, text), 130_000L) { event ->
            if (event["type"].asString == "chunk") onChunk(event["text"].asString)
        }
    }

    private suspend fun execute(pending: OperitPending, timeout: Long, consume: suspend (JsonObject) -> Unit) {
        check(!closed) { "OPERIT_CLOSED" }
        val job = requireNotNull(currentCoroutineContext()[Job])
        active[pending] = job
        var completed = false
        try {
            check(!closed) { "OPERIT_CLOSED" }
            OperitInbox.add(pending)
            withContext(Dispatchers.IO) { dispatch(pending) }
            consumeOperitEvents(pending, timeout, consume)
            completed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException(safeCode(pending.failure ?: error.message))
        } finally {
            pending.cancel()
            // Interruptions wait at most 1.5 seconds for a nonce-specific cancel.
            // No message is retried, including timeout and unknown result cases.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (!completed && pending.kind == "reply") cancelRemote(pending)
                } finally {
                    revoke(pending)
                    active.remove(pending)
                }
            }
        }
    }

    private suspend fun cancelRemote(target: OperitPending) {
        val cancel = OperitPending("cancel", targetId = target.id)
        try {
            OperitInbox.add(cancel)
            dispatch(cancel)
            withTimeoutOrNull(1500) {
                for (event in cancel.events) {
                    if (event["type"].asString in listOf("complete", "error")) break
                }
            }
        } catch (_: Exception) {
            // Best effort upstream cancel; native cancellation already rejects
            // every old chunk. Operit keeps its active token until its send finishes.
        } finally { cancel.cancel(); revoke(cancel) }
    }

    private fun dispatch(pending: OperitPending) {
        val uri = uri(pending)
        context.grantUriPermission(OPERIT_PACKAGE, uri, GRANTS)
        val intent = Intent(if (pending.kind == "cancel") CANCEL_ACTION else ACTION).apply {
            component = ComponentName(OPERIT_PACKAGE, "$OPERIT_PACKAGE.integrations.tasker.WorkflowTaskerReceiver")
            data = uri
            clipData = ClipData.newRawUri("Phone10 Mobile", uri)
            addFlags(GRANTS or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra("uri", uri.toString())
        }
        context.sendBroadcast(intent)
    }

    private fun revoke(pending: OperitPending) {
        OperitInbox.remove(pending.id)
        context.revokeUriPermission(uri(pending), GRANTS)
    }

    override fun close() {
        closed = true
        active.forEach { (pending, job) ->
            pending.cancel()
            job.cancel(CancellationException("OPERIT_CLOSED"))
        }
    }

    companion object {
        const val AUTHORITY = "com.huigu.phone10.mobile.operit"
        const val ACTION = "com.huigu.phone10.mobile.OPERIT_VOICE"
        const val CANCEL_ACTION = "com.huigu.phone10.mobile.OPERIT_VOICE_CANCEL"
        const val OPERIT_PACKAGE = "com.ai.assistance.operit"
        private const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private fun uri(pending: OperitPending) = Uri.parse("content://$AUTHORITY/request/${pending.id}")
        private val codes = setOf("OPERIT_INVALID_REQUEST", "OPERIT_CLOSED", "OPERIT_INVALID_EVENT", "OPERIT_INCOMPLETE",
            "OPERIT_EVENT_OVERFLOW", "OPERIT_UNAVAILABLE", "OPERIT_TIMEOUT", "OPERIT_STREAMING_UNAVAILABLE", "REQUEST_ALREADY_CLAIMED",
            "OPERIT_BUSY", "JOURNAL_FAILED", "JOURNAL_FULL", "INVALID_OPERIT_RESPONSE", "RESPONSE_TOO_LARGE",
            "OPERIT_REPLY_FAILED", "OPERIT_LIST_FAILED", "OPERIT_CANCEL_FAILED", "INVALID_REQUEST",
            "OPERIT_WORKER_FAILED", "OPERIT_WORKER_UNAVAILABLE")
        private fun safeCode(value: String?) = value?.takeIf { it in codes } ?: "OPERIT_UNAVAILABLE"
    }
}
