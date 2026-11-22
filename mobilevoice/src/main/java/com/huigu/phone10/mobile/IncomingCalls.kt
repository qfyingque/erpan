package com.huigu.phone10.mobile

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.io.File

/** Private, bounded receipts. A duplicate broadcast cannot ring or start audio twice. */
object IncomingCalls {
    private const val CHANNEL = "huigu-incoming-v1"
    private const val NOTICE = 1076
    private val gson = Gson()
    private fun file(c: Context) = AtomicFile(File(c.noBackupFilesDir, "incoming-invites.json"))
    private fun read(c: Context): List<IncomingInvite> = file(c).let {
        if (!it.baseFile.exists()) emptyList() else gson.fromJson(it.readFully().toString(Charsets.UTF_8),
            Array<IncomingInvite>::class.java).toList()
    }
    private fun save(c: Context, state: List<IncomingInvite>) {
        val f = file(c); val out = f.startWrite()
        try { out.write(gson.toJson(state).toByteArray(Charsets.UTF_8)); f.finishWrite(out) }
        catch (e: Exception) { f.failWrite(out); throw e }
    }
    @Synchronized fun get(c: Context, id: String) = read(c).find { it.id == id }
    fun config(c: Context, chatId: String): MobileSettings {
        val s = SettingsStore(c).load()
        require(s.chatId == chatId && chatId.isNotBlank() && !s.listenOnly) { "TARGET_UNAVAILABLE" }
        s.speech.validate(includeRecognition = true)
        if (s.smartEndpoint && !s.confirmBeforeSend) requireNotNull(s.endJudge).validate()
        if (s.confirmBeforeSend) require(Settings.canDrawOverlays(c)) { "OVERLAY_REQUIRED" }
        require(ContextCompat.checkSelfPermission(c, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "MICROPHONE_PERMISSION_REQUIRED" }
        return s
    }
    @Synchronized fun finish(c: Context, id: String, action: String): String {
        val r = IncomingBook.finish(read(c), id, action, System.currentTimeMillis())
        save(c, r.state)
        if (r.code !in setOf("UNKNOWN", "ALREADY_FINISHED"))
            c.getSystemService(NotificationManager::class.java).cancel(id, NOTICE)
        return r.code
    }
    @Synchronized fun offer(c: Context, invite: IncomingInvite): String {
        val selected = SettingsStore(c).load()
        val r = IncomingBook.offer(read(c), invite, selected.chatId, VoiceService.state.value.running, System.currentTimeMillis())
        if (r.code != "RINGING") return r.code
        config(c, invite.chatId)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "淮顾来电", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "电脑发来的语音邀请；点接听后才开启麦克风"
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        })
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled() ||
            nm.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return "NOTIFICATION_DISABLED"
        save(c, r.state) // consume before the externally visible side effect
        try {
            fun intent(cls: Class<*>, action: String) = Intent(c, cls).setAction(action)
                .setData(Uri.parse("erpan-incoming://invite/${invite.id}" )).putExtra("invite", invite.id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val accept = PendingIntent.getActivity(c, 0, intent(IncomingAcceptActivity::class.java, "accept"), flags)
            val decline = PendingIntent.getBroadcast(c, 0, intent(IncomingDeclineReceiver::class.java, "decline"), flags)
            val person = Person.Builder().setName(invite.caller).setImportant(true).build()
            val notice = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_mobile_voice)
                .setContentTitle("${invite.caller} 想和你通话").setContentText(invite.text.take(100))
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, accept))
                .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
                .setDeleteIntent(decline).setTimeoutAfter((invite.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                .build()
            nm.notify(invite.id, NOTICE, notice)
            // Android posts asynchronously. Absence on an immediate read is not
            // failure and must never cancel a valid invitation.
            return "POSTED"
        } catch (e: Exception) { finish(c, invite.id, "FAILED"); throw e }
    }
    @Synchronized fun takeAccepted(c: Context, id: String): IncomingInvite? {
        val item = get(c, id) ?: return null
        if (item.status != "ACCEPTED") return null
        try { config(c, item.chatId) }
        catch (e: Exception) { finish(c, id, "FAILED"); throw e }
        return if (finish(c, id, "STARTED") == "STARTED") item else null
    }
    @Synchronized fun status(c: Context, id: String): String {
        val item = get(c, id) ?: return "UNKNOWN"
        if (item.status in setOf("RINGING", "ACCEPTED") && item.expiresAt <= System.currentTimeMillis()) return "EXPIRED"
        if (item.status == "RINGING" && c.getSystemService(NotificationManager::class.java)
                .activeNotifications.none { it.id == NOTICE && it.tag == id }) return "PENDING_NOTIFICATION"
        return item.status
    }
}

/** Reached only from this app's immutable notification action. */
class IncomingAcceptActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra("invite").orEmpty()
        try {
            val item = requireNotNull(IncomingCalls.get(this, id))
            IncomingCalls.config(this, item.chatId)
            require(!VoiceService.state.value.running)
            if (IncomingCalls.finish(this, id, "ACCEPTED") == "ACCEPTED") {
                ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java)
                    .setAction(VoiceService.INCOMING).putExtra("invite", id))
            }
        } catch (_: Exception) {
            IncomingCalls.finish(this, id, "FAILED")
            Toast.makeText(this, "未能接通；原话已留在 O。请检查耳畔当前聊天、语音配置和麦克风权限。", Toast.LENGTH_LONG).show()
        } finally { finish() }
    }
}

class IncomingDeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IncomingCalls.finish(context, intent.getStringExtra("invite").orEmpty(), "REJECTED")
        // The exact opening is already in O, with its existing text notification.
    }
}
