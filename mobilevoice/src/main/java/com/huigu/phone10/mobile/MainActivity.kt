package com.huigu.phone10.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

class MainActivity : ComponentActivity() {
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
        var avatar by remember { mutableStateOf<Bitmap?>(null) }
        val sttDrafts = remember { mutableMapOf<String, SpeechConfig>() }
        val ttsDrafts = remember { mutableMapOf<String, Pair<SpeechConfig, String?>>() }
        LaunchedEffect(Unit) { avatar = withContext(Dispatchers.IO) { Phone10AvatarStore(this@MainActivity).load() } }

        fun openConfig(section: String = "") {
            draft = saved; target = section; notice = ""; page = "config"
            sttDrafts.clear(); ttsDrafts.clear()
        }
        fun back() {
            when (page) {
                "about" -> page = aboutReturn
                "config" -> if (draft != saved) askSave = true else { page = "home"; notice = "" }
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
        fun saveEditor() {
            persist(draft) { askSave = false; page = "home"; notice = "配置已保存在本机。" }
        }
        fun start() {
            if (state.running || busy) return
            try {
                require(saved.chatId.isNotBlank()) { "请先选中聊天窗口。" }
                saved.speech.validate()
                if (saved.smartEndpoint) requireNotNull(saved.endJudge) { "请填写智能判断服务。" }.validate()
                notice = ""
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, VoiceService::class.java))
            } catch (invalid: IllegalArgumentException) {
                openConfig(); notice = invalid.message ?: "请先补齐语音配置。"
            } catch (_: Exception) { notice = "开始通话失败，请检查麦克风权限和音频占用。" }
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else notice = "需要麦克风权限才能开始通话。"
        }
        fun requestStart() {
            if (saved.chatId.isBlank() || runCatching { saved.speech.validate() }.isFailure) {
                openConfig(); notice = "请先选择聊天，并填写识别和合成信息。"; return
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
        val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) overlayPreference(true)
            else notice = "请在系统悬浮权限列表中找到「耳畔」并允许显示，再返回开启悬浮球。"
        }
        val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                try {
                    avatar = withContext(Dispatchers.IO) { Phone10AvatarStore(this@MainActivity).import(uri) }
                    if (VoiceService.state.value.running) startService(Intent(this@MainActivity, VoiceService::class.java)
                        .setAction(VoiceService.AVATAR))
                }
                catch (_: Exception) { notice = "图片读取失败，请换一张图片。" }
            }
        }
        fun showAbout() { aboutReturn = page; page = "about" }

        when (page) {
            "config" -> ErpanConfig(draft, enabled = !state.running && !busy, listing = listing, notice = notice, target = target,
                onChange = { draft = it }, onStt = { provider ->
                    val current = draft.speech
                    val currentProvider = current.provider ?: SpeechConfig.OPENAI
                    if (provider != currentProvider) {
                        sttDrafts[currentProvider] = current
                        val next = sttDrafts[provider] ?: if (provider == SpeechConfig.BAILIAN) SpeechConfig.bailianDefaults() else SpeechConfig.openAiDefaults()
                        draft = draft.copy(speech = current.copy(provider = provider, sttBaseUrl = next.sttBaseUrl,
                            sttKey = next.sttKey, sttModel = next.sttModel, ttsProvider = current.effectiveTtsProvider))
                    }
                }, onTts = { provider ->
                    val current = draft.speech
                    if (provider != current.effectiveTtsProvider) {
                        ttsDrafts[current.effectiveTtsProvider] = current to draft.voiceName
                        val old = ttsDrafts[provider]
                        val next = current.withTtsProvider(provider)
                        draft = draft.copy(speech = if (old == null) next else next.copy(ttsBaseUrl = old.first.ttsBaseUrl,
                            ttsKey = old.first.ttsKey, ttsModel = old.first.ttsModel, voice = old.first.voice), voiceName = old?.second)
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
                }, onSave = { saveEditor() }, onBack = { back() }, onAbout = { showAbout() })
            "about" -> ErpanAbout(onBack = { back() }, onDetails = { kind ->
                val file = if (kind == "许可说明") "erpan-notices.txt" else "erpan-guide.md"
                scope.launch {
                    val text = withContext(Dispatchers.IO) { runCatching { assets.open(file).bufferedReader().use { it.readText() } }
                        .getOrElse { "说明暂时无法读取。" } }
                    dialog = kind to text
                }
            })
            else -> ErpanHome(saved, state, avatar, busy, notice,
                onStart = { requestStart() }, onEnd = { stopService(Intent(this@MainActivity, VoiceService::class.java)) },
                onMic = { if (state.running && !state.changing) startService(Intent(this@MainActivity, VoiceService::class.java).setAction(VoiceService.TOGGLE)) },
                onOverlay = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                        try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { notice = "无法打开悬浮权限设置，请在系统应用设置中授权。" }
                    } else overlayPreference(enabled)
                }, onSmart = { enabled ->
                    if (enabled && runCatching { requireNotNull(saved.endJudge).validate() }.isFailure) {
                        openConfig("judge"); draft = draft.copy(smartEndpoint = true, endJudge = draft.endJudge ?: EndJudgeConfig())
                        notice = "请填写判断服务；它会额外调用文本模型并计费。"
                    } else persist(saved.copy(smartEndpoint = enabled))
                }, onVoiceInterruption = { enabled ->
                    persist(saved.copy(disableVoiceInterruption = !enabled)) {
                        if (VoiceService.state.value.running) startService(Intent(this@MainActivity, VoiceService::class.java)
                            .setAction(VoiceService.VOICE_INTERRUPTION).putExtra("enabled", enabled))
                    }
                }, onAvatar = { avatarPicker.launch("image/*") }, onConfig = { openConfig() }, onVoice = { openConfig("voice") },
                onLogs = { dialog = "最近语音状态" to VoiceDiagnostics.snapshot().ifBlank { "暂无记录" } }, onAbout = { showAbout() })
        }
        if (askSave) AlertDialog(onDismissRequest = { askSave = false }, title = { Text("保存这次修改？") },
            text = { Text("配置有尚未保存的内容。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { saveEditor() }) { Text("保存并返回") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { draft = saved; askSave = false; page = "home"; notice = "" }) { Text("放弃修改") } })
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
