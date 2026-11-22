package com.huigu.phone10.mobile

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Narrow local O-only entrance. It can offer an invitation, never accept it. */
class IncomingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val c = requireNotNull(context)
        val callers = c.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if ("com.ai.assistance.operit" !in callers) throw SecurityException("OPERIT_ONLY")
        val identity = Binder.clearCallingIdentity()
        try {
            val code = when (method) {
                "offer" -> IncomingCalls.offer(c, IncomingInvite(
                    extras?.getString("id").orEmpty(), extras?.getString("chat_id").orEmpty(),
                    extras?.getString("caller").orEmpty(), extras?.getString("text").orEmpty(),
                    extras?.getLong("expires_at", 0) ?: 0))
                "status" -> IncomingCalls.status(c, arg.orEmpty())
                "cancel" -> IncomingCalls.finish(c, arg.orEmpty(), "CANCELLED")
                else -> "UNKNOWN_METHOD"
            }
            return Bundle().apply { putString("status", code) }
        } catch (_: Exception) { return Bundle().apply { putString("status", "UNAVAILABLE") } }
        finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
