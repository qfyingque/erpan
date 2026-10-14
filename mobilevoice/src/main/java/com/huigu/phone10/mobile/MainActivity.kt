package com.huigu.phone10.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.*
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var permissionReviewEntry by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        permissionReviewEntry++
    }
    override fun onStart() {
        super.onStart()
        VoiceDiagnostics.record("activity_foreground")
    }

    override fun onStop() {
        VoiceDiagnostics.record("activity_background")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        setContent { ErpanTheme { Surface(Modifier.fillMaxSize(), color = ErpanColors.Paper) { ErpanApp() } } }
    }

    @Composable private fun ErpanApp() {
        val store = remember { SettingsStore(this) }
        var notice by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(runCatching { store.load() }.getOrElse {
            notice = "配置读取失败，请重新填写并保存。"; MobileSettings(SpeechConfig.bailianDefaults())
        }) }
        var draft by remember { mutableStateOf(saved) }
        var editingProfileId by remember { mutableStateOf(saved.currentVoiceProfile()?.id) }
        var page by rememberSaveable { mutableStateOf("home") }
        var aboutReturn by rememberSaveable { mutableStateOf("home") }
        var target by remember { mutableStateOf("") }
        val state by VoiceService.state.collectAsState()
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var listing by remember { mutableStateOf(false) }
        var chats by remember { mutableStateOf<List<OperitChat>?>(null) }
        var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
        var askSave by remember { mutableStateOf(false) }
        var checking by remember { mutableStateOf(false) }
        var checkJob by remember { mutableStateOf<Job?>(null) }
        var showCheck by remember { mutableStateOf(false) }
        var checkResult by remember { mutableStateOf("") }
        var voicesGroupTab by rememberSaveable { mutableStateOf(false) }
        var configReturn by rememberSaveable { mutableStateOf("home") }
        var recentRoles by remember(saved.chatId) { mutableStateOf(emptyList<String>()) }
        var roleError by remember { mutableStateOf("") }
        var namingProfile by remember { mutableStateOf(false) }
        var profileError by remember { mutableStateOf("") }
        var localVoices by remember { mutableStateOf(emptyList<LocalVoice>()) }
        var catalogBase by remember { mutableStateOf<SpeechConfig?>(null) }
        var catalogFresh by remember { mutableStateOf(false) }
        var floatingPermissionTarget by remember { mutableStateOf("ball") }
        val avatarStore = remember { Phone10AvatarStore(this) }
        val avatarLoader = remember { Phone10AvatarLoader(this) }
        val appearanceStore = remember { AvatarAppearanceStore(this) }
        var appearance by remember { mutableStateOf(appearanceStore.load()) }
        var appearanceDraft by remember { mutableStateOf(appearance) }
        var avatar by remember { mutableStateOf<Drawable?>(null) }
        val sttDrafts = remember { mutableMapOf<String, SpeechConfig>() }
        val ttsDrafts = remember { mutableMapOf<String, Pair<SpeechConfig, String?>>() }
        LaunchedEffect(Unit) {
            try { avatar = withContext(Dispatchers.IO) { avatarLoader.load(avatarStore.source()) } }
            catch (_: Exception) { notice = "头像无法显示，请在外观里重新选择图片。" }
        }

        fun openConfig(section: String = "") {
            draft = saved; target = section; notice = ""; configReturn = "home"; page = "config"
            editingProfileId = saved.currentVoiceProfile()?.id
            sttDrafts.clear(); ttsDrafts.clear()
        }
        fun openAppearance() {
            appearanceDraft = appearance
            notice = ""
            page = "appearance"
        }
        fun refreshFloatingAvatar() {
            if (VoiceService.state.value.running) startService(Intent(this@MainActivity, VoiceService::class.java)
                .setAction(VoiceService.AVATAR))
        }
        fun back() {
            if (checking) checkJob?.cancel()
            when (page) {
                "about" -> page = aboutReturn
                "keepsakes", "voices", "capture_probe" -> page = "home"
                "config" -> if (draft != saved) askSave = true else { page = configReturn; notice = "" }
                "appearance" -> { appearanceDraft = appearance; page = "home"; notice = "" }
            }
        }
        BackHandler(enabled = page != "home") { back() }

        fun persist(next: MobileSettings, after: () -> Unit = {}) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.save(next) }
                    saved = next; notice = ""; after()
                } catch (_: Exception) { notice = "保存失败，请检查手机存储。" }
                finally { busy = false }
            }
        }
        fun openProfiles() {
            if (busy || checking || state.running) return
            profileError = ""; roleError = ""; notice = ""; page = "voices"
        }
        fun refreshLocalVoices() {
            if (busy || VoiceService.state.value.running) return
            val connection = saved.localVoiceConnection() ?: return
            busy = true; catalogFresh = false; profileError = ""
            scope.launch {
                try {
                    val entries = fetchLocalVoices(connection)
                    localVoices = entries; catalogBase = connection; catalogFresh = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    profileError = if (error is SpeechApiException) error.message.orEmpty()
                        else "声音列表未更新，请检查电脑语音服务；已保存方案保持不变。"
                } finally { busy = false }
            }
        }
        fun useVoiceSelection(next: MobileSettings) {
            if (busy || VoiceService.state.value.running) return
            busy = true; profileError = ""
            scope.launch {
                try {
                    if (next.speech.effectiveTtsProvider == SpeechConfig.QWEN_LOCAL) {
                        val entries = fetchLocalVoices(next.speech)
                        requireLocalVoice(entries, next.speech)
                        localVoices = entries; catalogBase = next.speech; catalogFresh = true
                    }
                    check(!VoiceService.state.value.running) { "call-started" }
                    withContext(Dispatchers.IO) { store.save(next) }
                    saved = next; draft = next
                    notice = "已保存并使用「${next.currentVoiceProfile()?.name ?: next.voiceName.orEmpty()}」，下次通话生效。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    catalogFresh = false
                    profileError = if (error is SpeechApiException) error.message.orEmpty()
                        else "未切换声音，请结束通话并检查连接后再试；原方案保留。"
                } finally { busy = false }
            }
        }
        fun requestNewProfileName() {
            askSave = false
            try {
                draft.speech.validate(includeRecognition = !draft.listenOnly)
                profileError = ""; notice = ""; askSave = false; namingProfile = true
            } catch (invalid: IllegalArgumentException) {
                notice = invalid.message.orEmpty()
            }
        }
        fun saveEditor(newName: String? = null) {
            if (busy || state.running) return
            askSave = false
            val existing = saved.profiles().firstOrNull { it.id == editingProfileId }
            if (newName == null && existing == null) { requestNewProfileName(); return }
            try {
                val next = draft.copy(voiceProfiles = saved.voiceProfiles).saveVoiceProfile(
                    newName ?: requireNotNull(existing).name, if (newName == null) existing?.id else null)
                persist(next) {
                    draft = next; editingProfileId = next.activeVoiceProfileId
                    namingProfile = false; askSave = false; profileError = ""; page = configReturn
                    notice = "已保存并使用「${next.currentVoiceProfile()?.name}」。"
                }
            } catch (invalid: IllegalArgumentException) {
                profileError = invalid.message.orEmpty(); notice = profileError
            }
        }
        fun start() {
            if (state.running || busy) return
            try {
                if (!saved.listenOnly && saved.confirmBeforeSend &&
                    !Settings.canDrawOverlays(this@MainActivity)) {
                    notice = "发送前确认需要悬浮显示权限：请先打开悬浮字幕或悬浮球，按提示允许耳畔悬浮显示，再开始通话。"
                    return
                }
                require(saved.chatId.isNotBlank()) { "请先选中聊天窗口。" }
                saved.speech.validate(includeRecognition = !saved.listenOnly)
                if (!saved.listenOnly && saved.smartEndpoint && !saved.confirmBeforeSend)
                    requireNotNull(saved.endJudge) { "请填写智能判断服务。" }.validate()
                notice = ""
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, VoiceService::class.java))
            } catch (invalid: IllegalArgumentException) {
                openConfig(); notice = invalid.message ?: "请先补齐语音配置。"
            } catch (_: Exception) { notice = "开始通话失败，请检查麦克风权限和音频占用。" }
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (saved.listenOnly || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else notice = "需要麦克风权限才能开始通话。"
        }
        fun requestStart() {
            val issues = configurationIssues(saved)
            if (issues.isNotEmpty()) {
                openConfig(); notice = issues.joinToString("\n"); return
            }
            if (saved.listenOnly) {
                if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else start()
                return
            }
            permissions.launch(if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        fun overlayPreference(enabled: Boolean) {
            persist(saved.copy(overlayEnabled = enabled)) {
                if (state.running) startService(Intent(this@MainActivity, VoiceService::class.java)
                    .setAction(VoiceService.OVERLAY).putExtra("enabled", enabled))
            }
        }
        fun captionsPreference(enabled: Boolean) {
            persist(saved.copy(captionsEnabled = enabled)) {
                if (VoiceService.state.value.running) startService(Intent(this@MainActivity, VoiceService::class.java)
                    .setAction(VoiceService.CAPTIONS).putExtra("enabled", enabled))
            }
        }
        val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                when (floatingPermissionTarget) {
                    "captions" -> captionsPreference(true)
                    "review" -> persist(saved.copy(confirmBeforeSend = true))
                    "review_draft" -> if (!VoiceService.showPendingDraft())
                        notice = "草稿已结束；下次识别后会在悬浮待发栏显示。"
                    else -> overlayPreference(true)
                }
            } else notice = "请在系统悬浮权限列表中找到「耳畔」并允许显示，再返回开启悬浮功能。"
        }
        LaunchedEffect(permissionReviewEntry) {
            if (intent?.getBooleanExtra(VoiceService.REVIEW_PERMISSION_REQUEST, false) == true) {
                intent?.removeExtra(VoiceService.REVIEW_PERMISSION_REQUEST)
                if (Settings.canDrawOverlays(this@MainActivity)) VoiceService.showPendingDraft()
                else {
                    floatingPermissionTarget = "review_draft"
                    try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { notice = "请在系统应用设置中允许耳畔悬浮显示。" }
                }
            }
        }
        fun floatingPreference(feature: String, enabled: Boolean) {
            if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                floatingPermissionTarget = feature
                try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { notice = "无法打开悬浮权限设置，请在系统应用设置中授权。" }
            } else if (feature == "captions") captionsPreference(enabled) else overlayPreference(enabled)
        }
        val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                try {
                    withContext(Dispatchers.IO) { avatarStore.import(uri) }
                    avatar = withContext(Dispatchers.IO) { avatarLoader.load(avatarStore.source()) }
                    notice = ""
                    refreshFloatingAvatar()
                }
                catch (error: Exception) {
                    notice = (error as? IOException)?.message ?: "图片读取失败，请换一张图片。"
                }
            }
        }
        fun showAbout() { aboutReturn = page; page = "about" }

        fun checkConnections() {
            if (checking || listing || busy || VoiceService.state.value.running) return
            val snapshot = draft
            checking = true; showCheck = true; checkResult = "正在检查填写内容…"
            checkJob = scope.launch {
                var connection: OperitBridge? = null
                try {
                    val issues = configurationIssues(snapshot)
                    if (issues.isNotEmpty()) {
                        checkResult = issues.joinToString("\n\n")
                        return@launch
                    }
                    val cloud = CloudSpeech(snapshot.speech)
                    val o = OperitBridge(this@MainActivity).also { connection = it }
                    val checker = ConnectionCheck({
                        val choices = o.listChats()
                        if (snapshot.listenOnly && choices.any { it.id == snapshot.chatId }) o.readReplies(snapshot.chatId, null)
                        choices
                    }, cloud::checkRecognitionConnection,
                        cloud::checkSynthesisConnection, {
                            snapshot.endJudge?.let { CloudEndJudge(it).isComplete("我已经说完了。") }
                        })
                    VoiceDiagnostics.record("connection_check_started")
                    checkResult = checker.run(snapshot) { checkResult = it }
                } catch (cancelled: CancellationException) {
                    checkResult = "检查已取消。"; throw cancelled
                } catch (_: Exception) { checkResult = "检查未完成，请稍后重试。" }
                finally {
                    connection?.close()
                    checking = false; checkJob = null
                    VoiceDiagnostics.record("connection_check_finished")
                }
            }
        }

        when (page) {
            "capture_probe" -> CaptureProbePage(onBack = { back() })
            "voices" -> VoiceProfilesPage(saved,busy,state.running,profileError.ifBlank {notice},
                groupTab=voicesGroupTab,onTab={voicesGroupTab=it},
                onUse={id->if(!busy && !VoiceService.state.value.running)useVoiceSelection(saved.selectVoiceProfile(id))},
                onRemove={id->if(!busy && !VoiceService.state.value.running) {
                    val next=saved.removeVoiceProfile(id)
                    persist(next){draft=draft.copy(voiceProfiles=next.voiceProfiles);profileError="";notice="方案已移除。"}
                }},onCreate={openConfig();configReturn="voices";editingProfileId=null},onBack={back()},
                localVoices=localVoices,catalogFresh=catalogFresh,onRefresh={refreshLocalVoices()},
                onLocalVoice={voice->
                    try {catalogBase?.let {useVoiceSelection(saved.addLocalVoice(it,voice))}}
                    catch(error:Exception){profileError=error.message ?: "无法保存这个声音。"}
                },roles=recentRoles,roleError=roleError,onRefreshRoles={
                if (!busy && !state.running) scope.launch {
                    busy=true
                    val selectedChat=saved.chatId
                    val reader=OperitBridge(this@MainActivity)
                    try {
                        val snapshot=reader.readReplies(selectedChat,null,true)
                        if(saved.chatId==selectedChat){recentRoles=snapshot.roleNames;roleError=if(recentRoles.isEmpty())"最近消息没有角色名，可以手动填写。" else ""}
                    } catch (_: CancellationException) { throw CancellationException() }
                    catch (e: Exception) { roleError=if(e.message=="OPERIT_PLUGIN_UPDATE_REQUIRED")"请更新 code58 配套的 O 插件并运行 setup。" else "暂时读取失败，已有绑定保留；也可以手动填写。" }
                    finally {reader.close();busy=false}
                }
            },onBind={role,id->
                if(!busy && !state.running)try {
                    val next=saved.bindRoleVoice(role,id)
                    persist(next){draft=draft.copy(roleVoiceBindings=next.roleVoiceBindings);roleError=""}
                }catch(e:Exception){roleError=e.message ?: "保存失败"}
            })
            "keepsakes" -> VoiceKeepsakesPage { back() }
            "appearance" -> ErpanAppearance(appearanceDraft, avatar, notice, busy,
                onChange = { appearanceDraft = it.safe() },
                onPick = { avatarPicker.launch("image/*") },
                onReset = {
                    if (!busy) scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) {
                                avatarStore.restoreDefault()
                                appearanceStore.save(AvatarAppearance())
                            }
                            avatar = null
                            appearance = AvatarAppearance()
                            appearanceDraft = appearance
                            notice = "已恢复默认头像与外观。"
                            refreshFloatingAvatar()
                        } catch (_: Exception) { notice = "恢复默认失败，请检查手机存储。" }
                        finally { busy = false }
                    }
                },
                onSave = {
                    if (!busy) scope.launch {
                        busy = true
                        try {
                            val next = appearanceDraft.safe()
                            withContext(Dispatchers.IO) { appearanceStore.save(next) }
                            appearance = next
                            notice = ""
                            page = "home"
                            refreshFloatingAvatar()
                        } catch (_: Exception) { notice = "外观保存失败，请检查手机存储。" }
                        finally { busy = false }
                    }
                },
                onBack = { back() })
            "config" -> ErpanConfig(draft, enabled = !state.running && !busy && !checking, listing = listing, notice = notice, target = target,
                onChange = { draft = it }, onStt = { provider ->
                    val current = draft.speech
                    val currentProvider = current.provider ?: SpeechConfig.OPENAI
                    if (provider != currentProvider) {
                        sttDrafts[currentProvider] = current
                        draft = draft.copy(speech = current.withSttProvider(provider, sttDrafts[provider]))
                    }
                }, onTts = { provider ->
                    val current = draft.speech
                    if (provider != current.effectiveTtsProvider) {
                        ttsDrafts[current.effectiveTtsProvider] = current to draft.voiceName
                        val old = ttsDrafts[provider]
                        val next = current.withTtsProvider(provider)
                        draft = draft.copy(speech = if (old == null) next else next.copy(ttsBaseUrl = old.first.ttsBaseUrl,
                            ttsKey = old.first.ttsKey, ttsModel = old.first.ttsModel, voice = old.first.voice,
                            volcResource = old.first.volcResource, volcAppId = old.first.volcAppId,
                            voicePrompt = old.first.voicePrompt, mossSpeed = old.first.mossSpeed,
                            mossTextMode = old.first.mossTextMode), voiceName = old?.second)
                    }
                }, onChats = {
                    if (!listing) {
                        listing = true; notice = ""
                        scope.launch {
                            val o = OperitBridge(this@MainActivity)
                            try { chats = o.listChats() }
                            catch (_: Exception) { notice = "Operit 未返回聊天列表。请检查 Operit 已打开、连接插件与工作流已启用。" }
                            finally { o.close(); listing = false }
                        }
                    }
                }, onSave = { saveEditor() }, onBack = { back() }, onAbout = { showAbout() },
                checking = checking, onCheck = { checkConnections() },
                profileName = saved.profiles().firstOrNull { it.id == editingProfileId }?.name,
                onSaveAs = { requestNewProfileName() })
            "about" -> ErpanAbout(onBack = { back() }, onDetails = { kind ->
                val file = if (kind == "许可说明") "erpan-notices.txt" else "erpan-guide.md"
                scope.launch {
                    val text = withContext(Dispatchers.IO) { runCatching { assets.open(file).bufferedReader().use { it.readText() } }
                        .getOrElse { "说明暂时无法读取。" } }
                    dialog = kind to text
                }
            })
            else -> ErpanHome(saved, state, avatar, appearance, busy, notice,
                onStart = { requestStart() }, onEnd = {
                    state.pendingDraft?.let { VoiceService.cancelPendingDraft(it.id) }
                    stopService(Intent(this@MainActivity, VoiceService::class.java))
                },
                onOverlay = { enabled -> floatingPreference("ball", enabled) }, onSmart = { enabled ->
                    if (enabled && runCatching { requireNotNull(saved.endJudge).validate() }.isFailure) {
                        openConfig("judge"); draft = draft.copy(smartEndpoint = true, endJudge = draft.endJudge ?: EndJudgeConfig())
                        notice = "请填写判断服务；它会额外调用文本模型并计费。"
                    } else persist(saved.copy(smartEndpoint = enabled))
                }, onConfirmBeforeSend = { enabled ->
                    if (!state.running) {
                        if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                            floatingPermissionTarget = "review"
                            try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"))) }
                            catch (_: Exception) { notice = "请在系统应用设置中允许耳畔悬浮显示。" }
                        } else persist(saved.copy(confirmBeforeSend = enabled))
                    }
                }, onVoiceInterruption = { enabled ->
                    persist(saved.copy(disableVoiceInterruption = !enabled)) {
                        if (VoiceService.state.value.running) startService(Intent(this@MainActivity, VoiceService::class.java)
                            .setAction(VoiceService.VOICE_INTERRUPTION).putExtra("enabled", enabled))
                    }
                }, onReviewDraft = {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        floatingPermissionTarget = "review_draft"
                        try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { notice = "请在系统应用设置中允许耳畔悬浮显示。" }
                    } else if (!VoiceService.showPendingDraft())
                        notice = "草稿已结束；下次识别后会在悬浮待发栏显示。"
                }, onAvatar = { openAppearance() }, onConfig = { openConfig() },
                onLogs = { dialog = "最近语音状态" to VoiceDiagnostics.snapshot().ifBlank { "暂无记录" } }, onAbout = { showAbout() },
                onProfiles = { openProfiles() },
                onCaptions = { enabled -> floatingPreference("captions", enabled) },
                onCaptionAppearance = {
                    showCaptionAppearance(this@MainActivity)
                },
                onKeepsakes = { page="keepsakes" },
                onListenOnly = { enabled -> if (!state.running) persist(saved.copy(listenOnly = enabled)) },
                onInterrupt = { startService(Intent(this@MainActivity, VoiceService::class.java).setAction(VoiceService.INTERRUPT)) })
        }
        if (namingProfile) VoiceProfileNameDialog("", busy || state.running,
            profileError.ifBlank { notice }, onSave = { name -> saveEditor(name) }, onClose = { namingProfile = false })
        if (showCheck) AlertDialog(onDismissRequest = { checkJob?.cancel(); showCheck = false },
            title = { Text(if (checking) "正在检查连接" else "连接检查结果") },
            text = { Text(checkResult, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { checkJob?.cancel(); showCheck = false }) {
                Text(if (checking) "取消检查" else "关闭")
            } })
        if (askSave) AlertDialog(onDismissRequest = { askSave = false }, title = { Text("保存这次修改？") },
            text = { Text("配置有尚未保存的内容。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { saveEditor() }) { Text("保存并使用") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { draft = saved; askSave = false; page = configReturn; notice = "" }) { Text("放弃修改") } })
        dialog?.let { (title, text) -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(title) },
            text = { Text(text, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } }) }
        chats?.let { choices -> AlertDialog(onDismissRequest = { chats = null }, title = { Text("选中聊天窗口") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (choices.isEmpty()) Text("Operit 中还没有聊天，请先创建一条。")
                choices.forEach { chat -> TextButton(onClick = { draft = draft.copy(chatId = chat.id, chatTitle = chat.title); chats = null }) {
                    Text(chat.title.ifBlank { "未命名聊天" })
                } }
            } }, confirmButton = { TextButton(onClick = { chats = null }) { Text("关闭") } }) }
    }

    @Composable private fun ErpanAbout(onBack: () -> Unit, onDetails: (String) -> Unit) {
        val version = remember { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            PageHeading("关于耳畔", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("耳畔", fontFamily = FontFamily.Serif, fontSize = 38.sp)
                Text("erpan voice · $version", color = ErpanColors.Muted, fontSize = 14.sp)
                Text("让对话 · 靠近一点", color = ErpanColors.Rose, fontFamily = FontFamily.Serif, fontSize = 18.sp)
                Text("连接你在 Operit 中的聊天与自己选择的语音服务。声音在手机播放，文字留在 Operit。", fontSize = 15.sp, lineHeight = 24.sp)
                ErpanNavigationCard("使用说明", onClick = { onDetails("使用说明") })
                ErpanNavigationCard("开源许可", onClick = { onDetails("许可说明") })
                Text("开源仓库：尚未发布", color = ErpanColors.Muted, fontSize = 14.sp)
                Text("录音发送至识别服务，回复文字发送至合成服务；开启智能判断后，识别文字还会发送至判断服务。各服务商分别计费。语音由 AI 合成。", color = ErpanColors.Muted, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
    }
}
