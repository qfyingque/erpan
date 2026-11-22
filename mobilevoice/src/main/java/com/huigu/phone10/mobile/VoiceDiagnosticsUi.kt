package com.huigu.phone10.mobile

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@Composable internal fun VoiceDiagnosticsDialog(settings: MobileSettings, state: VoiceState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    fun report(): String {
        val speech = settings.speech
        fun label(value: String?): String {
            val raw = value.orEmpty()
            return if (raw.length in 1..96 && raw.matches(Regex("[A-Za-z0-9_. /-]+")) &&
                !raw.contains("sk-", true) && listOf(speech.sttKey, speech.ttsKey, settings.emotionSource?.key.orEmpty()).none { it.isNotEmpty() && raw.contains(it) }) raw else "未记录"
        }
        return "耳畔语音诊断 · $version\n设备：${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
            "当前状态：${if (state.running) "运行中" else "已停止"}；模式：${if (settings.listenOnly) "只听回复" else "语音通话"}\n" +
            "识别：${label(speech.provider)} / ${label(speech.sttModel)}\n合成：${label(speech.effectiveTtsProvider)} / ${label(speech.ttsModel)}\n" +
            (if (speech.effectiveTtsProvider == SpeechConfig.MINIMAX) "MiniMax 接法：${label(speech.effectiveMiniMaxTransport)}\n" else "") +
            "人声打断：${!settings.disableVoiceInterruption}；智能判断：${settings.smartEndpoint}；发送前确认：${settings.confirmBeforeSend}\n" +
            "传递情绪线索：${settings.sendEmotionHints}；传递停顿信息：${settings.sendTimingHints}\n" +
            "情绪补充：${settings.emotionSource?.enabled == true}；已配置：${settings.emotionSource?.let { it.enabled && it.validationErrors().isEmpty() } == true}\n" +
            "仅本次应用进程的记录；配置为当前保存值。没有录音、聊天正文、密钥或完整接口地址。\n\n" + VoiceDiagnostics.snapshot()
    }
    var text by remember { mutableStateOf(report()) }
    var pending by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = pending
        pending = ""
        if (uri != null && snapshot.isNotEmpty()) scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                }
                notice = "日志已保存，可以把这个文本文件发来排查。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "日志保存失败，请重新选择保存位置。" }
            finally { saving = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onClose() }, title = { Text("语音诊断日志") },
        text = { Column {
            Text("出错后直接导出即可。强制关闭应用后，本次记录会清空。", fontSize = 12.sp)
            if (notice.isNotEmpty()) Text(notice, fontSize = 13.sp)
            Text(text, Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = !saving && pending.isEmpty(), onClick = {
            pending = report(); text = pending
            try { export.launch("耳畔-语音日志-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt") }
            catch (_: Exception) { pending = ""; notice = "无法打开保存器，请检查系统文件应用。" }
        }) { Text(if (saving) "正在保存…" else "导出日志") } },
        dismissButton = { Row {
            TextButton(enabled = !saving, onClick = { text = report() }) { Text("刷新") }
            TextButton(enabled = !saving, onClick = onClose) { Text("关闭") }
        } })
}
