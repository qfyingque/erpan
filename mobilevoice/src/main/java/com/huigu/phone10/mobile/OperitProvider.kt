package com.huigu.phone10.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** Pure in-memory request/response protocol, separately tested on the JVM. */
internal class OperitPending(val kind: String, chatId: String? = null, text: String? = null, targetId: String? = null) {
    val id: String = UUID.randomUUID().toString()
    val nonce: String = UUID.randomUUID().toString()
    private var payload: String? = JsonObject().apply {
        addProperty("version", 1); addProperty("id", id); addProperty("nonce", nonce); addProperty("kind", kind)
        chatId?.let { addProperty("chatId", it) }; text?.let { addProperty("text", it) }
        targetId?.let { addProperty("targetId", it) }
    }.toString()
    val events = Channel<JsonObject>(128)
    @Volatile var cancelled = false
        private set
    @Volatile var failure: String? = null
        private set
    private var terminal = false
    private var nextSeq = 0
    private var receivedChars = 0
    private var claimed = false

    @Synchronized fun payload(): ByteArray = (payload ?: throw FileNotFoundException()).toByteArray(Charsets.UTF_8)

    @Synchronized fun accept(event: JsonObject): Boolean {
        if (cancelled || terminal) return false
        try {
            if (event["version"]?.asInt != 1 || event["id"]?.asString != id || event["nonce"]?.asString != nonce)
                return fail("OPERIT_INVALID_EVENT")
            val type = event["type"]?.asString
            // Native worker failure is terminal and authenticated by the same
            // request nonce; its independent thread cannot know the chunk seq.
            if (type == "worker_error") return fail("OPERIT_WORKER_FAILED")
            // A duplicate Operit worker can never acquire another send permission,
            // even after Operit loses its process-local journal. Preserve the owner.
            if (type == "accepted" && claimed) return false
            if (event["seq"]?.asInt != nextSeq) return fail("OPERIT_INVALID_EVENT")
            if (type !in listOf("accepted", "chats", "chunk", "complete", "error")) return fail("OPERIT_INVALID_EVENT")
            if (type == "chunk") {
                val text = event["text"]?.asString ?: return fail("OPERIT_INVALID_EVENT")
                receivedChars += text.length
                if (text.length > 8192 || receivedChars > 1_000_000) return fail("OPERIT_INVALID_EVENT")
            }
            if (!events.trySend(event).isSuccess) return fail("OPERIT_EVENT_OVERFLOW")
            if (type == "accepted") claimed = true
            nextSeq++
            if (type == "complete" || type == "error") { terminal = true; events.close() }
            return true
        } catch (_: Exception) { return fail("OPERIT_INVALID_EVENT") }
    }

    @Synchronized internal fun fail(code: String): Boolean {
        failure = code
        cancel()
        return false
    }

    @Synchronized fun cancel() {
        cancelled = true
        payload = null
        events.cancel(CancellationException(failure ?: "OPERIT_CANCELLED"))
    }
}

internal object OperitInbox {
    private val pending = mutableMapOf<String, OperitPending>()
    @Synchronized fun add(request: OperitPending) {
        check(pending.size < 16) { "OPERIT_BUSY" }
        pending[request.id] = request
    }
    @Synchronized fun get(id: String): OperitPending? = pending[id]
    @Synchronized fun remove(id: String) { pending.remove(id) }
}

/** Manifest: exported=false, grantUriPermissions=true; only Operit receives URI grants. */
class OperitProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/json"

    private fun request(uri: Uri): OperitPending? {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && requireNotNull(context).packageManager.getPackagesForUid(uid)
                ?.contains(OperitBridge.OPERIT_PACKAGE) != true) throw SecurityException("OPERIT_CALLER_REQUIRED")
        if (uri.authority != OperitBridge.AUTHORITY || uri.pathSegments.size != 2 || uri.pathSegments[0] != "request" ||
            !Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(uri.pathSegments[1])) {
            throw FileNotFoundException()
        }
        return OperitInbox.get(uri.pathSegments[1])
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        val payload = (request(uri) ?: throw FileNotFoundException()).payload()
        // A pipe exposes bytes without ever writing transcript/request files.
        val pipe = ParcelFileDescriptor.createPipe()
        writer.execute {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(payload) } }
            catch (_: Exception) { /* Reader cancelled; no persistent buffer or retry. */ }
            finally { payload.fill(0) }
        }
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val pending = request(uri)
        val json = values?.getAsString("event")
        val accepted = if (pending == null || pending.cancelled) false else try {
            if (json == null || json.length > 65_536) {
                pending.fail("OPERIT_INVALID_EVENT")
            } else pending.accept(JsonParser.parseString(json).asJsonObject)
        } catch (_: Exception) { pending.fail("OPERIT_INVALID_EVENT") }
        return uri.buildUpon().appendQueryParameter("status", if (accepted) "accepted" else "cancelled").build()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private val writer = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "OperitIpcPipe").apply { isDaemon = true } }
    }
}
