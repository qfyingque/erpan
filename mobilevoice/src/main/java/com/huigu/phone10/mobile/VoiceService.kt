package com.huigu.phone10.mobile

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class VoiceState(val running: Boolean = false, val message: String = "麦克风已关闭",
    val micEnabled: Boolean = false, val changing: Boolean = false, val overlayVisible: Boolean = false)

/** Sole capture/turn/playback owner. The overlay only requests explicit state transitions. */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var callScope: CoroutineScope? = null
    private var transition: Job? = null
    private var recorder: PcmRecorder? = null
    private var bridge: OperitBridge? = null
    private var conversation: VoiceConversation? = null
    private val player = AtomicReference<PcmPlayer?>()
    private var started = false
    private var terminalMessage = "麦克风已关闭"
    private lateinit var config: MobileSettings
    private lateinit var audio: AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var playbackFocus: VoiceAudioFocus? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var selectedSpeaker = false
    private var previousSpeaker = false
    private var overlay: Phone10MicOverlay? = null


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            VOICE_INTERRUPTION -> {
                if (started) {
                    val enabled = intent.getBooleanExtra("enabled", true)
                    config = config.copy(disableVoiceInterruption = !enabled)
                    conversation?.setVoiceInterruptionEnabled(enabled)
                    VoiceDiagnostics.record(if (enabled) "voice_interruption_on" else "voice_interruption_off")
                } else stopSelf()
                return START_NOT_STICKY
            }
            STOP -> { stopSelf(); return START_NOT_STICKY }
            INTERRUPT -> { conversation?.interrupt(); VoiceDiagnostics.record("manual_interrupt"); if (!started) stopSelf(); return START_NOT_STICKY }
            TOGGLE -> { if (started) toggle() else stopSelf(); return START_NOT_STICKY }
            OVERLAY -> {
                if (started) setOverlay(intent.getBooleanExtra("enabled", false)) else stopSelf()
                return START_NOT_STICKY
            }
            AVATAR -> {
                if (started) overlay?.refreshAvatar() else stopSelf()
                return START_NOT_STICKY
            }
        }
        if (started) return START_NOT_STICKY
        // A killed service or stale pending intent never restarts recording.
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        started = true
        try {
            config = SettingsStore(this).load()
            require(config.chatId.isNotBlank())
            config.speech.validate()
            if (config.smartEndpoint) requireNotNull(config.endJudge) { "请配置智能判断服务。" }.validate()
            audio = getSystemService(AUDIO_SERVICE) as AudioManager
            setState("正在开麦…", changing = true)
            foreground()
            setOverlay(config.overlayEnabled)
            toggle()
        } catch (_: Exception) {
            terminalMessage = "开麦失败，请检查语音配置、聊天选择及权限。"; stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setState(message: String, mic: Boolean = false, changing: Boolean = false) {
        if (!started) return
        mutableState.value = VoiceState(true, message, mic, changing, mutableState.value.overlayVisible)
        overlay?.update(Phone10MicrophoneState(mic, changing))
    }

    private fun setOverlay(enabled: Boolean) {
        if (!enabled) { overlay?.hide(); return }
        val view = overlay ?: Phone10MicOverlay(this, { toggle() }, { visible ->
            mutableState.value = mutableState.value.copy(overlayVisible = visible)
            VoiceDiagnostics.record(if (visible) "overlay_visible" else "overlay_hidden")
        }).also { overlay = it }
        if (!view.show()) VoiceDiagnostics.record("overlay_permission_missing")
        view.update(Phone10MicrophoneState(mutableState.value.micEnabled, mutableState.value.changing))
    }

    private fun toggle() {
        if (!started || transition?.isActive == true) return
        transition = scope.launch {
            if (callScope != null) mute("麦克风已关闭 · 点悬浮球可再开麦")
            else {
                setState("正在开麦…", changing = true)
                try { beginCapture() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mute("开麦失败，请检查麦克风权限或音频占用。")
                    VoiceDiagnostics.record("capture_start_failed")
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private suspend fun beginCapture() {
        // The previous session is joined by mute before another owner is constructed.
        foreground()
        acquireAudio()
        val child = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        callScope = child
        val cloud = CloudSpeech(config.speech)
        val o = OperitBridge(this).also { bridge = it }
        val judge = if (config.smartEndpoint) CloudEndJudge(requireNotNull(config.endJudge)) else null
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Phone10Mobile:call").also { it.acquire() }
        val flow = VoiceConversation(child, cloud::transcribe,
            { text, chunk -> o.reply(config.chatId, text, chunk) },
            { text -> play { output -> cloud.speak(text, output::write) } },
            { player.getAndSet(null)?.close() }, { message ->
                if (started && callScope === child) setState(message, mutableState.value.micEnabled)
            }, streamSpeak = if (config.speech.streamingTts) { texts ->
                play { output -> cloud.speakStream(texts) { pcm ->
                    child.launch { if (callScope === child) setState("正在播放 Operit 的回复…", true) }
                    output.write(pcm)
                } }
            } else null, judgeEnd = judge?.let { it::isComplete },
            transcribeDetailed = cloud::transcribeDetailed).also {
                it.setVoiceInterruptionEnabled(!config.disableVoiceInterruption)
                conversation = it
            }
        val input = PcmRecorder(this).also { recorder = it }
        val ready = CompletableDeferred<Unit>()
        child.launch {
            try {
                input.run({ child.launch {
                    VoiceDiagnostics.record(if (player.get() == null) "speech_onset" else "speech_onset_during_playback")
                    flow.speechStarted()
                } }, { pcm -> child.launch { VoiceDiagnostics.record("silence_segment_ready"); flow.submit(pcm) } }, {
                    ready.complete(Unit)
                }, acceptInput = flow::acceptsSpeech)
            } catch (cancelled: CancellationException) { ready.cancel(); throw cancelled }
            catch (error: Exception) {
                val wasReady = ready.isCompleted && !ready.isCancelled
                ready.completeExceptionally(error)
                if (wasReady) transition = scope.launch {
                    if (callScope === child) {
                        mute(if (error is UtteranceTooLongException) "一句话超过 30 秒，已关麦。请分段说。"
                            else "录音失败，已关麦，请检查权限和音频占用。")
                        VoiceDiagnostics.record("capture_failed")
                    }
                }
            }
        }
        ready.await()
        if (started && callScope === child) {
            setState(if (judge == null) "正在聆听 · 约 0.55 秒静音提交" else "正在聆听 · 智能结束判断", true)
            VoiceDiagnostics.record("capture_started")
            foreground()
        }
    }

    private suspend fun play(synthesize: suspend (PcmPlayer) -> Unit) = withContext(Dispatchers.IO) {
        val owner = requireNotNull(playbackFocus)
        val output = PcmPlayer { owner.beforePlayback() }

        player.set(output)
        VoiceDiagnostics.record("playback_started")
        try { synthesize(output); output.drain(); VoiceDiagnostics.record("playback_drained") }
        finally { owner.finishedPlayback(); player.compareAndSet(output, null); output.close() }
    }

    private suspend fun mute(message: String) {
        setState("正在关麦…", changing = true)
        conversation?.interrupt()
        recorder?.close(); recorder = null
        val child = callScope
        callScope = null
        child?.coroutineContext?.get(Job)?.cancelAndJoin()
        conversation = null
        bridge?.close(); bridge = null
        player.getAndSet(null)?.close()
        releaseAudio()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        setState(message)
        VoiceDiagnostics.record("capture_muted")
        if (started) foreground()
    }

    private fun acquireAudio() {
        previousMode = audio.mode
        previousSpeaker = audio.isSpeakerphoneOn
        val owner = VoiceAudioFocus({
            val granted = audio.requestAudioFocus(requireNotNull(focus)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            VoiceDiagnostics.record(if (granted) "playback_focus_granted" else "playback_focus_denied")
            granted
        }, { paused ->
            player.get()?.setPaused(paused)
            VoiceDiagnostics.record(if (paused) "playback_focus_paused" else "playback_focus_resumed")
        })
        playbackFocus = owner
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                VoiceDiagnostics.record("audio_focus_$change")
                if (change < 0) owner.lost()
                else if (change == AudioManager.AUDIOFOCUS_GAIN) owner.gained()
            }, Handler(Looper.getMainLooper())).build()
        // Capture continues while a muted game owns focus. Request it only for real PCM.
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val personalOutput = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE) ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (!personalOutput) {
            if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { selectedSpeaker = audio.setCommunicationDevice(it) }
            else { audio.isSpeakerphoneOn = true; selectedSpeaker = true }
        }
    }

    private fun releaseAudio() {
        playbackFocus?.close(); playbackFocus = null
        if (!::audio.isInitialized || focus == null) return
        runCatching {
            if (selectedSpeaker) {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = previousSpeaker
            }
            selectedSpeaker = false
            audio.abandonAudioFocusRequest(requireNotNull(focus))
            audio.mode = previousMode
        }
        focus = null
    }

    private fun foreground() {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "手机语音", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 2, Intent(this, VoiceService::class.java).setAction(TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        val mic = mutableState.value.micEnabled
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (mic) "耳畔 · 麦克风已开启" else "耳畔 · 麦克风已关闭")
            .setContentText("轻触返回；挂断结束语音会话").setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_btn_speak_now, if (mic) "关麦" else "开麦", toggle)
            .addAction(android.R.drawable.ic_media_pause, "挂断", stop).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(31, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(31, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(31, notification)
    }

    override fun onDestroy() {
        started = false
        overlay?.hide(); overlay = null
        conversation?.interrupt()
        runCatching { recorder?.close() }
        scope.cancel()
        bridge?.close()
        runCatching { player.getAndSet(null)?.close() }
        releaseAudio()
        wakeLock?.let { if (it.isHeld) it.release() }
        mutableState.value = VoiceState(false, terminalMessage)
        VoiceDiagnostics.record("session_stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val VOICE_INTERRUPTION = "com.huigu.phone10.mobile.VOICE_INTERRUPTION"
        const val INTERRUPT = "com.huigu.phone10.mobile.INTERRUPT"
        const val TOGGLE = "com.huigu.phone10.mobile.TOGGLE"
        const val OVERLAY = "com.huigu.phone10.mobile.OVERLAY"
        const val AVATAR = "com.huigu.phone10.mobile.AVATAR"
        const val STOP = "com.huigu.phone10.mobile.STOP"
        private const val CHANNEL = "mobile-voice"
        private val mutableState = MutableStateFlow(VoiceState())
        val state = mutableState.asStateFlow()
    }
}
