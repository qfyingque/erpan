package com.huigu.phone10.mobile

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class VoiceState(val running: Boolean = false, val message: String = "麦克风已关闭",
    val micEnabled: Boolean = false, val changing: Boolean = false, val overlayVisible: Boolean = false,
    val captionsVisible: Boolean = false, val listenOnly: Boolean = false,
    val pendingDraft: PendingVoiceDraft? = null)

/** Sole capture/turn/playback owner. The overlay only requests explicit state transitions. */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var callScope: CoroutineScope? = null
    private var transition: Job? = null
    private var recorder: PcmRecorder? = null
    private var captureJob: Job? = null
    private var bridge: OperitBridge? = null
    private var conversation: VoiceConversation? = null
    private var replyListener: ReplyListener? = null
    private val player = AtomicReference<PcmPlayer?>()
    @Volatile private var soundInputAfter = 0L
    private var started = false
    private var replaying = false
    private var probing = false
    private var probeOutcome: String? = null
    private var replay: ClipReplay? = null
    private data class ReplyArchive(@Volatile var text: String = "")
    @Volatile private var replyArchive = ReplyArchive()
    private fun keepsakes() = VoiceKeepsakes(java.io.File(cacheDir,"voice-recent"), java.io.File(filesDir,"voice-saved"))
    private var terminalMessage = "麦克风已关闭"
    private lateinit var config: MobileSettings
    private var replyVoiceSettings: MobileSettings? = null
    private lateinit var audio: AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var playbackFocus: VoiceAudioFocus? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioRoute: VoiceAudioRoute? = null
    private var overlay: Phone10MicOverlay? = null
    private var captionOverlay: FloatingCaptions? = null
    private val captionBuffer = CaptionBuffer()
    private val spokenCaptions = SpokenCaptions()
    @Volatile private var captionGeneration = 0L
    private var captionAligned = false
    private var captionSegment: Int? = null

    private fun renderCaptions() {
        val aligned = captionAligned && spokenCaptions.text.isNotEmpty()
        captionOverlay?.setPlayback(if (aligned) spokenCaptions.range(captionSegment) else null, captionAligned)
        captionOverlay?.render(if (aligned) spokenCaptions.text else captionBuffer.text,
            mutableState.value.message, mutableState.value.pendingDraft)
    }

    private fun stopPlayer() {
        player.getAndSet(null)?.let {
            soundInputAfter = android.os.SystemClock.elapsedRealtime() + 1000
            it.close()
        }
        captionSegment = null
        captionAligned = false
        captionOverlay?.setPlayback(null)
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (probing) {
            if (intent?.action == STOP) stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            PROBE, PROBE_PLAY -> {
                if (!started) {
                    val mode = ProbeMode.entries.firstOrNull { it.name == intent.getStringExtra("mode") }
                    if (mode == null) stopSelf() else startProbe(mode, intent.action == PROBE_PLAY)
                }
                return START_NOT_STICKY
            }
            REPLAY -> {
                val id=intent.getStringExtra("clip").orEmpty()
                val saved=intent.getBooleanExtra("saved",true)
                if (!started) startReplay(id,saved)
                else if (replaying) replay?.play(id,saved)
                return START_NOT_STICKY
            }
            REPLAY_PAUSE -> {
                if (replaying) replay?.togglePause() else if (!started) stopSelf()
                return START_NOT_STICKY
            }
            REPLAY_SEEK -> {
                if (replaying) replay?.seek(intent.getLongExtra("frame",0)) else if (!started) stopSelf()
                return START_NOT_STICKY
            }
            REPLAY_STOP -> {
                if (replaying || !started) { replay?.stop(); stopSelf() }
                return START_NOT_STICKY
            }
            VOICE_INTERRUPTION -> {
                if (started) {
                    val enabled = intent.getBooleanExtra("enabled", true)
                    config = config.copy(disableVoiceInterruption = !enabled)
                    conversation?.setVoiceInterruptionEnabled(enabled)
                    VoiceDiagnostics.record(if (enabled) "voice_interruption_on" else "voice_interruption_off")
                } else stopSelf()
                return START_NOT_STICKY
            }
            STOP -> { conversation?.interrupt(); stopSelf(); return START_NOT_STICKY }
            INTERRUPT -> { replyListener?.interrupt(); conversation?.interrupt(); VoiceDiagnostics.record("manual_interrupt"); if (!started || replaying) { stopPlayer(); stopSelf() }; return START_NOT_STICKY }
            SHOW_DRAFT -> { if (started) revealPendingDraft() else stopSelf(); return START_NOT_STICKY }
            TOGGLE -> { if (started) toggle() else stopSelf(); return START_NOT_STICKY }
            OVERLAY -> {
                if (started) setOverlay(intent.getBooleanExtra("enabled", false)) else stopSelf()
                return START_NOT_STICKY
            }
            CAPTIONS -> {
                if (started) setCaptions(intent.getBooleanExtra("enabled", false)) else stopSelf()
                return START_NOT_STICKY
            }
            AVATAR -> {
                if (started) {
                    overlay?.refreshAppearance()
                    overlay?.refreshAvatar()
                } else stopSelf()
                return START_NOT_STICKY
            }
        }
        if (started) return START_NOT_STICKY
        // A killed service or stale pending intent never restarts recording.
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        started = true
        activeService = this
        try {
            config = SettingsStore(this).load()
            require(config.chatId.isNotBlank())
            if (!config.listenOnly && config.confirmBeforeSend) require(Settings.canDrawOverlays(this))
            config.speech.validate(includeRecognition = !config.listenOnly)
            if (!config.listenOnly && config.smartEndpoint && !config.confirmBeforeSend)
                requireNotNull(config.endJudge) { "请配置智能判断服务。" }.validate()
            audio = getSystemService(AUDIO_SERVICE) as AudioManager
            setState(if (config.listenOnly) "正在连接只听回复…" else "正在开麦…", changing = true)
            foreground()
            setOverlay(config.overlayEnabled)
            setCaptions(config.captionsEnabled)
            if (config.listenOnly) {
                beginSession()
                val flow = requireNotNull(conversation)
                val o = requireNotNull(bridge)
                val listener = ReplyListener({ after -> o.readReplies(config.chatId, after, config.hasRoleVoices()) },
                    { chunks -> flow.playReplyStream(chunks) }, { stopPlayer() }, { setState(it) },
                    diagnose = VoiceDiagnostics::record,
                    playRole = { role, chunks -> flow.playReplyStream(chunks, role) }).also { replyListener = it }
                requireNotNull(callScope).launch {
                    try { listener.run() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        val code = OperitBridge.safeCode(error.message)
                        VoiceDiagnostics.record("reply_listener_failed:$code")
                        terminalMessage = if (code == "OPERIT_PLUGIN_UPDATE_REQUIRED") "请在 Operit 更新本安装包配套的 Phone10 语音插件，再开启只听回复。"
                            else if (error.message == "RESPONSE_TOO_LARGE") "待播放内容过多，已停止只听回复。请在 Operit 查看文字。"
                            else "只听回复连接中断（$code）。请在 Operit 查看文字；诊断记录已保留。"
                        stopSelf()
                    }
                }
            } else toggle()
        } catch (_: Exception) {
            terminalMessage = "启动失败，请检查语音配置、聊天选择及权限。"; stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setState(message: String, mic: Boolean = conversation?.microphoneEnabled == true, changing: Boolean = false) {
        if (!started) return
        val status = if (config.listenOnly && message.startsWith("正在聆听")) "只听回复已开启 · 等待新的回复"
            else if (!mic && !changing && message.startsWith("正在聆听"))
            "麦克风已关闭 · 点悬浮球可再开麦" else message
        mutableState.value = VoiceState(true, status, mic, changing, mutableState.value.overlayVisible,
            mutableState.value.captionsVisible, config.listenOnly, mutableState.value.pendingDraft)
        overlay?.update(Phone10MicrophoneState(mic, changing, pendingReview = mutableState.value.pendingDraft != null))
        renderCaptions()
    }

    private fun updatePendingDraft(draft: PendingVoiceDraft?) {
        val wasPending = mutableState.value.pendingDraft != null
        mutableState.value = mutableState.value.copy(pendingDraft = draft)
        overlay?.update(Phone10MicrophoneState(mutableState.value.micEnabled,
            mutableState.value.changing, pendingReview = draft != null))
        setCaptions(config.captionsEnabled)
        if (wasPending != (draft != null)) {
            foreground()
            if (draft != null) notifyPendingDraft()
            else (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(DRAFT_NOTIFICATION)
        }
    }

    private fun notifyPendingDraft() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(DRAFT_CHANNEL, "语音待确认",
            NotificationManager.IMPORTANCE_HIGH))
        val allowed = Settings.canDrawOverlays(this)
        val open = pendingReviewIntent(allowed)
        notifications.notify(DRAFT_NOTIFICATION, NotificationCompat.Builder(this, DRAFT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(if (allowed) "语音识别完了，等你确认" else "请允许耳畔悬浮显示")
            .setContentText(if (allowed) "点开悬浮待发栏改字或发送；尚未提交给 Operit"
                else "点此授权后，在悬浮待发栏确认；尚未发送")
            .setContentIntent(open).setAutoCancel(false).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    private fun pendingReviewIntent(overlayAllowed: Boolean): PendingIntent = if (overlayAllowed)
        PendingIntent.getService(this, 4, Intent(this, VoiceService::class.java).setAction(SHOW_DRAFT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    else PendingIntent.getActivity(this, 5,
        Intent(this, MainActivity::class.java).putExtra(REVIEW_PERMISSION_REQUEST, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun revealPendingDraft(): Boolean {
        return when (CaptionReviewPolicy.entry(mutableState.value.pendingDraft != null,
            Settings.canDrawOverlays(this))) {
            CaptionReviewPolicy.Entry.NONE -> false
            CaptionReviewPolicy.Entry.PERMISSION -> {
                VoiceDiagnostics.record("draft_overlay_permission_missing")
                notifyPendingDraft()
                false
            }
            CaptionReviewPolicy.Entry.PANEL -> {
                setCaptions(config.captionsEnabled)
                captionOverlay?.revealDraft() == true
            }
        }
    }

    private fun setOverlay(enabled: Boolean) {
        if (!enabled || config.listenOnly) { overlay?.hide(); return }
        val view = overlay ?: Phone10MicOverlay(this, {
            if (mutableState.value.pendingDraft != null) revealPendingDraft() else toggle()
        }, { visible ->
            mutableState.value = mutableState.value.copy(overlayVisible = visible)
            VoiceDiagnostics.record(if (visible) "overlay_visible" else "overlay_hidden")
        }).also { overlay = it }
        if (!view.show()) VoiceDiagnostics.record("overlay_permission_missing")
        view.update(Phone10MicrophoneState(mutableState.value.micEnabled, mutableState.value.changing,
            pendingReview = mutableState.value.pendingDraft != null))
    }

    private fun setCaptions(enabled: Boolean) {
        if (!CaptionReviewPolicy.shouldShow(enabled, mutableState.value.pendingDraft != null)) {
            captionOverlay?.render(captionBuffer.text, mutableState.value.message, null)
            captionOverlay?.hide(); return
        }
        val view = captionOverlay ?: FloatingCaptions(this, { visible ->
            mutableState.value = mutableState.value.copy(captionsVisible = visible)
        }, { id, text -> conversation?.editDraft(id, text) == true },
            { id -> conversation?.confirmDraft(id) != null },
            { id -> conversation?.cancelDraft(id) == true }).also { captionOverlay = it }
        renderCaptions()
        if (!view.show()) VoiceDiagnostics.record("caption_overlay_unavailable")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        captionOverlay?.reposition()
        overlay?.reposition()
    }

    private fun toggle() {
        if (!started || config.listenOnly || transition?.isActive == true) return
        transition = scope.launch {
            if (recorder != null) mute("麦克风已关闭 · AI 回复与声音继续")
            else {
                setState("正在开麦…", changing = true)
                try {
                    if (callScope == null) beginSession()
                    beginCapture()
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mute("开麦失败，请检查麦克风权限或音频占用。")
                    VoiceDiagnostics.record("capture_start_failed")
                    if (conversation == null) {
                        terminalMessage = "通话初始化失败，请重新开始通话。"
                        stopSelf()
                    }
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun beginSession() {
        // Reply, IPC and playback live for the call, independently of capture.
        foreground()
        acquireAudio()
        val child = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        callScope = child
        val synthesisOwner = Any().also { latestSynthesisOwner = it }
        val synthesisStatus: suspend (String) -> Unit = { message ->
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                if (started && callScope === child) setState(message)
                else if (!started && activeService == null && latestSynthesisOwner === synthesisOwner &&
                    message.startsWith("本机语音")) {
                    // A cancelled call may still be confirming remote shutdown.
                    // Never let its late status overwrite a newer call.
                    mutableState.value = VoiceState(false,message)
                }
            }
        }
        val speechClient = okhttp3.OkHttpClient()
        val cloud = CloudSpeech(config.speech, client = speechClient, onSynthesisStatus = synthesisStatus)
        val synthesisClients = mutableMapOf(config.speech to cloud)
        val o = OperitBridge(this).also { bridge = it }
        val judge = if (!config.listenOnly && config.smartEndpoint && !config.confirmBeforeSend)
            CloudEndJudge(requireNotNull(config.endJudge)) else null
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Phone10Mobile:call").also { it.acquire() }
        fun replyVoice(role: String): ReplyVoice {
            val selected = config.voiceForRole(role)
            replyVoiceSettings = selected
            val speech = selected.speech
            val tts = synthesisClients.getOrPut(speech) {
                CloudSpeech(speech, client = speechClient, onSynthesisStatus = synthesisStatus)
            }
            fun write(output: PcmPlayer, pcm: ByteArray) {
                child.launch { if (callScope === child) setState("正在播放 Operit 的回复…") }
                output.write(pcm)
            }
            val stream: (suspend (kotlinx.coroutines.channels.ReceiveChannel<String>) -> Unit)? =
                if (speech.sentenceHttpTts) { texts ->
                    play(voiceSettings = selected) { output ->
                        SentenceSpeech(tts::speak).speak(texts, output::configure, output::markCaptionSegment) { pcm -> write(output, pcm) }
                    }
                } else if (speech.streamingTts) { texts ->
                    play(voiceSettings = selected) { output ->
                        tts.speakStream(texts,
                            onPlayed = { if (speech.effectiveTtsProvider == SpeechConfig.QWEN_LOCAL) {
                                output.drain(); VoiceDiagnostics.record("playback_drained_before_release")
                            } },
                            onAbort = { if (speech.effectiveTtsProvider == SpeechConfig.QWEN_LOCAL) output.close() },
                            onSegment = output::markCaptionSegment, onPcm = { pcm -> write(output, pcm) })
                    }
                } else null
            return ReplyVoice({ text -> play(voiceSettings = selected) { output ->
                output.markCaptionSegment(text); tts.speak(text, output::configure, output::write)
            } }, stream, speech.sentenceHttpTts, speech.wholeReplyTts, speech.clientSegmentedTts, speech.coherentTts)
        }
        VoiceConversation(child, cloud::transcribe,
            { text, chunk -> o.reply(config.chatId, text, chunk) },
            { error("reply voice required") },
            { stopPlayer() }, { message ->
                if (started && callScope === child) setState(message)
            }, judgeEnd = judge?.let { it::isComplete },
            voiceForRole = ::replyVoice,
            roleReply = if (config.hasRoleVoices()) { text, render ->
                playRoleReplyTurn(text,
                    read = { after -> o.readReplies(config.chatId, after, true) },
                    send = { value -> o.reply(config.chatId, value) { } }, render = render,
                    stop = { stopPlayer() }, report = { message ->
                        if (started && callScope === child) setState(if (message.startsWith("只听回复")) "等待群聊回复…" else message)
                    })
            } else null,
            sentenceTts = config.speech.sentenceHttpTts,
            wholeReplyTts = config.speech.wholeReplyTts,
            firstClauseTts = config.speech.clientSegmentedTts,
            coherentTts = config.speech.coherentTts,
            transcribeDetailed = cloud::transcribeDetailed,
            confirmBeforeSend = !config.listenOnly && config.confirmBeforeSend,
            soundCuesEnabled = !config.listenOnly && config.preserveBreath,
            onDraftChanged = { draft ->
                if (started && callScope === child) updatePendingDraft(draft)
            }, onReplyStart = {
                replyArchive = ReplyArchive()
                captionGeneration++
                captionBuffer.clear(); spokenCaptions.clear(); captionSegment = null
                captionAligned = (replyVoiceSettings ?: config).speech.let { it.sentenceHttpTts || it.clientSegmentedTts }
                captionOverlay?.resetPlayback()
                renderCaptions()
            }, onReplyDelta = { delta ->
                captionBuffer.append(delta)
                replyArchive.text = captionBuffer.text
                if (!captionAligned || spokenCaptions.text.isEmpty()) renderCaptions()
            }, onSpeechSegment = { text ->
                if (captionAligned) { spokenCaptions.append(text); renderCaptions() }
            }).also {
                it.setVoiceInterruptionEnabled(!config.disableVoiceInterruption)
                it.setMicrophoneEnabled(false)
                conversation = it
            }
    }

    private suspend fun beginCapture() {
        val child = requireNotNull(callScope)
        val flow = requireNotNull(conversation)
        foreground()
        val input = PcmRecorder(this, config.preserveBreath).also { recorder = it }
        val ready = CompletableDeferred<Unit>()
        flow.setMicrophoneEnabled(true)
        captureJob = child.launch {
            try {
                input.run({ child.launch {
                    if (recorder === input && callScope === child && flow.microphoneEnabled) {
                        VoiceDiagnostics.record(if (player.get() == null) "speech_onset" else "speech_onset_during_playback")
                        flow.speechStarted()
                    }
                } }, { pcm -> child.launch {
                    if (recorder === input && callScope === child && flow.microphoneEnabled) {
                        VoiceDiagnostics.record("silence_segment_ready"); flow.submit(pcm)
                    }
                } }, {
                    ready.complete(Unit)
                }, acceptInput = flow::acceptsSpeech,
                    acceptSound = { flow.acceptsSoundCues() && player.get() == null &&
                        android.os.SystemClock.elapsedRealtime() >= soundInputAfter },
                    onSound = { cue ->
                        if (recorder === input && callScope === child && flow.microphoneEnabled) {
                            VoiceDiagnostics.record("sound_cue_" + cue.name.lowercase())
                            // Dispatch is suspended until sound metadata has a separate frontend path.
                        }
                    }, onSoundStatus = { available -> child.launch {
                        if (recorder === input && callScope === child) {
                            VoiceDiagnostics.record(if (available) "sound_model_ready" else "sound_model_unavailable")
                            if (!available) setState("气声检测暂不可用，普通语音仍可使用。", flow.microphoneEnabled)
                        }
                    } })
            } catch (cancelled: CancellationException) { ready.cancel(); throw cancelled }
            catch (error: Exception) {
                val wasReady = ready.isCompleted && !ready.isCancelled
                ready.completeExceptionally(error)
                if (wasReady) transition = scope.launch {
                    if (callScope === child && recorder === input) {
                        mute(if (error is UtteranceTooLongException) "一句话超过 30 秒，已关麦。请分段说。"
                            else "录音失败，已关麦，请检查权限和音频占用。")
                        VoiceDiagnostics.record("capture_failed")
                    }
                }
            }
        }
        ready.await()
        if (started && callScope === child) {
            setState(if (config.confirmBeforeSend) "正在聆听 · 约 0.55 秒后待确认"
                else if (config.smartEndpoint) "正在聆听 · 智能结束判断"
                else "正在聆听 · 约 0.55 秒静音提交", true)
            VoiceDiagnostics.record("capture_started")
            foreground()
        }
    }

    private suspend fun play(record: Boolean = true, voiceSettings: MobileSettings = config, synthesize: suspend (PcmPlayer) -> Unit) = withContext(Dispatchers.IO) {
        val owner = requireNotNull(playbackFocus)
        val words = replyArchive
        var firstPcm = true
        val output = PcmPlayer(mediaPlayback = config.listenOnly) {
            if (firstPcm) {
                firstPcm = false
                VoiceDiagnostics.record("playback_first_pcm")
            }
            owner.beforePlayback()
        }
        val capture = if (record) runCatching { keepsakes().begin(voiceSettings.currentVoiceProfile()?.name ?: voiceSettings.displayVoice()) }
            .onFailure { VoiceDiagnostics.record("voice_cache_unavailable") }.getOrNull() else null
        output.captureObserver = capture
        var complete = false

        val generation = captionGeneration
        player.set(output)
        val levelJob = scope.launch {
            var active = false
            while (isActive && player.get() === output && captionGeneration == generation) {
                val segment = withContext(Dispatchers.IO) { output.playbackCaption() }
                if (player.get() !== output || captionGeneration != generation) break
                if (segment != captionSegment) { captionSegment = segment; renderCaptions() }
                if (overlay?.needsAudioLevel == true) {
                    val level = withContext(Dispatchers.IO) { output.playbackLevel() }
                    overlay?.setAudioLevel(level)
                    active = true
                    delay(33)
                } else {
                    if (active) overlay?.setAudioLevel(0f)
                    active = false
                    delay(80)
                }
            }
        }
        VoiceDiagnostics.record("playback_started")
        try {
            try { synthesize(output) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                VoiceDiagnostics.record("playback_failed_${error.javaClass.simpleName}")
                if (voiceSettings.speech.sentenceHttpTts) {
                    // A provider error does not invalidate PCM already accepted.
                    // Explicit user interruption still cancels immediately.
                    try { output.drain(); VoiceDiagnostics.record("received_audio_drained_after_error") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { VoiceDiagnostics.record("received_audio_drain_failed") }
                }
                throw error
            }
            output.drain(); complete = true; VoiceDiagnostics.record("playback_drained")
        }
        finally {
            levelJob.cancel()
            finishPlayback(Dispatchers.Main.immediate,
                finishFocus = { owner.finishedPlayback() },
                clearPlayer = {
                    soundInputAfter = android.os.SystemClock.elapsedRealtime() + 1000
                    player.compareAndSet(output, null)
                },
                closePlayer = { output.close() },
                clearVisual = {
                    if (captionGeneration == generation && player.get() == null) {
                        overlay?.setAudioLevel(0f)
                        captionSegment = null
                        captionOverlay?.setPlayback(null)
                    }
                }, finishArchive = {
                    withContext(Dispatchers.IO) {
                        runCatching { capture?.finish(output.archiveFrames(), complete, words.text) }
                            .onFailure { VoiceDiagnostics.record("voice_cache_save_failed") }
                    }
                })
        }
    }

    /** Local replay shares this service's sole player; it never opens a chat, microphone or TTS request. */
    private fun startProbe(mode: ProbeMode, playback: Boolean) {
        started = true; probing = true; activeService = this
        try {
            config = MobileSettings(SpeechConfig.bailianDefaults(), listenOnly = playback)
            audio = getSystemService(AUDIO_SERVICE) as AudioManager
            CaptureProbe.busy(if (playback) "正在试听 ${mode.title}" else "正在开启 ${mode.title}")
            setState(CaptureProbe.state.value.message, mic = !playback)
            foreground()
            acquireAudio(mediaRoute = playback || mode == ProbeMode.MINIMAL)
            transition = scope.launch {
                try {
                    if (playback) {
                        val sample = requireNotNull(CaptureProbe.state.value.samples[mode])
                        play(record = false) { output ->
                            output.configure(16000)
                            for (offset in sample.pcm.indices step 8192) {
                                ensureActive(); output.write(sample.pcm.copyOfRange(offset,minOf(offset+8192,sample.pcm.size)))
                            }
                        }
                        probeOutcome = "试听结束"
                    } else {
                        val input = PcmRecorder(this@VoiceService, true).also { recorder = it }
                        val (pcm, details) = input.probe(mode == ProbeMode.MINIMAL) { second ->
                            scope.launch { if (started && recorder === input) {
                                CaptureProbe.busy("${mode.title} · 正在录音 $second / 8 秒")
                            } }
                        }
                        recorder = null
                        ensureActive()
                        val raw = ProbeSample(pcm, details, CaptureProbe.metrics(pcm), "尚未分析")
                        CaptureProbe.result(mode, raw)
                        CaptureProbe.busy("已停止录音，正在本机检测…")
                        setState("已停止录音，正在本机检测…", mic = false)
                        val report = analyzeProbe(this@VoiceService, pcm)
                        ensureActive()
                        CaptureProbe.result(mode, raw.copy(modelReport = report))
                        probeOutcome = "${mode.title}完成，可以试听或导出"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    VoiceDiagnostics.record("capture_probe_failed:${error.javaClass.simpleName}")
                    probeOutcome = "收音测试失败，请检查麦克风权限和占用；已有录音保留。"
                }
                finally { stopSelf() }
            }
        } catch (_: Exception) { probeOutcome = "收音测试无法启动。"; stopSelf() }
    }

    private fun startReplay(id: String, saved: Boolean) {
        started = true
        replaying = true
        activeService = this
        try {
            config = SettingsStore(this).load().copy(listenOnly = true)
            audio = getSystemService(AUDIO_SERVICE) as AudioManager
            setState("正在打开声音…", changing = true)
            foreground()
            acquireAudio()
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Earban:keepsake").also { it.acquire(30*60*1000L) }
            val store=keepsakes()
            replay=ClipReplay(scope,
                load={ key, permanent -> withContext(Dispatchers.IO) { store.clip(key,permanent) } },
                render={ clip, permanent, start, progress ->
                    captionGeneration++
                    captionBuffer.clear(); captionBuffer.append(clip.text)
                    spokenCaptions.clear(); captionAligned=false
                    setState("正在试听 · ${clip.voice}")
                    play(record = false) { output ->
                        output.configure(clip.rate)
                        output.setUserPaused(replay?.state?.paused==true)
                        coroutineScope {
                            val monitor=launch(Dispatchers.Main.immediate) {
                                while (isActive) {
                                    progress(start+output.archiveFrames()); delay(80)
                                }
                            }
                            try {
                                store.openAudio(clip.id,permanent).use { input ->
                                    input.seek(44+start*2)
                                    var remaining=(clip.frames-start)*2
                                    val buffer=ByteArray(8192)
                                    while (remaining>0) {
                                        ensureActive()
                                        val n=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt())
                                        check(n>0) { "音频文件不完整" }
                                        output.write(buffer.copyOf(n)); remaining-=n
                                    }
                                }
                                output.drain()
                            } finally { monitor.cancel() }
                        }
                    }
                }, stopOutput=::stopPlayer,
                pauseOutput={ player.get()?.setUserPaused(it) },
                publish={ mutableReplay.value=it },
                ended={ message -> terminalMessage=message; stopSelf() })
            replay?.play(id,saved)
        } catch (_: Exception) { terminalMessage="收藏播放启动失败。"; stopSelf() }
    }

    private suspend fun mute(message: String) {
        conversation?.setMicrophoneEnabled(false)
        setState("正在关麦…", changing = true)
        val input = recorder
        val capture = captureJob
        // Invalidate callbacks before closing. A queued frame from this recorder
        // cannot become a new utterance after mute or after a later unmute.
        recorder = null
        captureJob = null
        runCatching { input?.close() }.onFailure { VoiceDiagnostics.record("capture_close_failed") }
        capture?.cancelAndJoin()
        setState(message)
        VoiceDiagnostics.record("capture_muted_output_continues")
        if (started) foreground()
    }

    private fun acquireAudio(mediaRoute: Boolean = config.listenOnly) {
        previousMode = audio.mode
        val owner = VoiceAudioFocus({
            val granted = audio.requestAudioFocus(requireNotNull(focus)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            VoiceDiagnostics.record(if (granted) "playback_focus_granted" else "playback_focus_denied")
            granted
        }, { paused ->
            player.get()?.setPaused(paused)
            VoiceDiagnostics.record(if (paused) "playback_focus_paused" else "playback_focus_resumed")
        }, coexist = config.gameAudioCoexist)
        playbackFocus = owner
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(if (config.listenOnly)
                AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                VoiceDiagnostics.record("audio_focus_$change")
                if (change < 0) owner.lost()
                else if (change == AudioManager.AUDIOFOCUS_GAIN) owner.gained()
            }, Handler(Looper.getMainLooper())).build()
        VoiceDiagnostics.record(if (config.gameAudioCoexist) "game_audio_coexist_on" else "game_audio_coexist_off")
        // Listening follows the system media output (including A2DP). Only microphone
        // calls require communication routing/AEC. Game coexist keeps its focus policy.
        if (mediaRoute) {
            audio.mode = AudioManager.MODE_NORMAL
            VoiceDiagnostics.record("audio_route_system_media")
        } else {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audioRoute = VoiceAudioRoute(AndroidVoiceAudioRoute(this, audio)).also { it.start() }
        }
    }

    private fun releaseAudio() {
        audioRoute?.close(); audioRoute = null
        playbackFocus?.close(); playbackFocus = null
        if (!::audio.isInitialized || focus == null) return
        runCatching {
            audio.abandonAudioFocusRequest(requireNotNull(focus))
            audio.mode = previousMode
        }
        focus = null
    }

    private fun foreground() {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "手机语音", NotificationManager.IMPORTANCE_LOW))
        val openActivity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = if (mutableState.value.pendingDraft != null)
            pendingReviewIntent(Settings.canDrawOverlays(this)) else openActivity
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 2, Intent(this, VoiceService::class.java).setAction(TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        val mic = mutableState.value.micEnabled
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (probing) "耳畔 · 收音对比" else if (replaying) "耳畔 · 收藏试听" else if (mutableState.value.pendingDraft != null) "耳畔 · 语音待确认"
                else if (config.listenOnly) "耳畔 · 只听回复" else if (mic) "耳畔 · 麦克风已开启" else "耳畔 · 麦克风已关闭")
            .setContentText(if (probing) CaptureProbe.state.value.message else if (replaying) "正在播放手机里保存的声音" else if (mutableState.value.pendingDraft != null) "点开悬浮待发栏，改字后再发送；挂断会取消"
                else if (config.listenOnly) {
                if (config.speech.wholeReplyTts) "在 Operit 打字，整条回复写完后朗读" else "在 Operit 打字，回复生成时陆续朗读"
            } else "轻触返回；挂断结束语音会话").setOngoing(true).setContentIntent(open)
            .apply {
                if (mutableState.value.pendingDraft != null)
                    addAction(android.R.drawable.ic_menu_edit, "编辑草稿", open)
                if (probing) { /* Only the Stop action below owns a diagnostic session. */ }
                else if (config.listenOnly) addAction(android.R.drawable.ic_media_pause, "停止播放",
                    PendingIntent.getService(this@VoiceService, 3, Intent(this@VoiceService, VoiceService::class.java)
                        .setAction(INTERRUPT), PendingIntent.FLAG_IMMUTABLE))
                else addAction(android.R.drawable.ic_btn_speak_now, if (mic) "关麦" else "开麦", toggle)
            }
            .addAction(android.R.drawable.ic_media_pause, if (config.listenOnly) "结束" else "挂断", stop).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(31, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (config.listenOnly) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(31, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(31, notification)
    }

    override fun onDestroy() {
        if (probing) CaptureProbe.finish(probeOutcome ?: "测试已停止，已录完的样本保留。")
        started = false
        if (activeService === this) activeService = null
        overlay?.hide(); overlay = null
        captionOverlay?.hide(); captionOverlay = null; captionBuffer.clear()
        replyListener?.interrupt(); replyListener = null
        conversation?.interrupt()
        runCatching { recorder?.close() }
        scope.cancel()
        replay?.stop(); replay=null
        bridge?.close()
        runCatching { stopPlayer() }
        releaseAudio()
        wakeLock?.let { if (it.isHeld) it.release() }
        mutableState.value = VoiceState(false, terminalMessage)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(DRAFT_NOTIFICATION)
        VoiceDiagnostics.record("session_stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val PROBE = "com.huigu.phone10.mobile.CAPTURE_PROBE"
        const val PROBE_PLAY = "com.huigu.phone10.mobile.CAPTURE_PROBE_PLAY"
        private var latestSynthesisOwner: Any? = null
        private var activeService: VoiceService? = null
        fun editPendingDraft(id: String, text: String): Boolean =
            activeService?.takeIf { it.started }?.conversation?.editDraft(id, text) == true
        fun sendPendingDraft(id: String): Boolean =
            activeService?.takeIf { it.started }?.conversation?.confirmDraft(id) != null
        fun cancelPendingDraft(id: String): Boolean =
            activeService?.takeIf { it.started }?.conversation?.cancelDraft(id) == true
        fun showPendingDraft(): Boolean = activeService?.takeIf { it.started }?.revealPendingDraft() == true
        const val VOICE_INTERRUPTION = "com.huigu.phone10.mobile.VOICE_INTERRUPTION"
        const val REPLAY = "com.huigu.phone10.mobile.REPLAY"
        const val REPLAY_PAUSE = "com.huigu.phone10.mobile.REPLAY_PAUSE"
        const val REPLAY_SEEK = "com.huigu.phone10.mobile.REPLAY_SEEK"
        const val REPLAY_STOP = "com.huigu.phone10.mobile.REPLAY_STOP"
        const val INTERRUPT = "com.huigu.phone10.mobile.INTERRUPT"
        const val SHOW_DRAFT = "com.huigu.phone10.mobile.SHOW_DRAFT"
        const val REVIEW_PERMISSION_REQUEST = "com.huigu.phone10.mobile.REVIEW_PERMISSION_REQUEST"
        const val TOGGLE = "com.huigu.phone10.mobile.TOGGLE"
        const val OVERLAY = "com.huigu.phone10.mobile.OVERLAY"
        const val CAPTIONS = "com.huigu.phone10.mobile.CAPTIONS"
        const val AVATAR = "com.huigu.phone10.mobile.AVATAR"
        const val STOP = "com.huigu.phone10.mobile.STOP"
        private const val CHANNEL = "mobile-voice"
        private const val DRAFT_CHANNEL = "mobile-voice-draft"
        private const val DRAFT_NOTIFICATION = 32
        private val mutableState = MutableStateFlow(VoiceState())
        val state = mutableState.asStateFlow()
        private val mutableReplay = MutableStateFlow(ClipReplayState())
        internal val replayState = mutableReplay.asStateFlow()
    }
}
