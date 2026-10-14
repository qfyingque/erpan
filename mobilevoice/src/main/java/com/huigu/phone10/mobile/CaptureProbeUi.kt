package com.huigu.phone10.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable internal fun CaptureProbePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val probe by CaptureProbe.state.collectAsState()
    val voice by VoiceService.state.collectAsState()
    var notice by remember { mutableStateOf("") }
    var technical by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ProbeMode?>(null) }
    var exportSamples by remember { mutableStateOf<Map<ProbeMode, ProbeSample>>(emptyMap()) }
    var exporting by remember { mutableStateOf(false) }
    fun launch(mode: ProbeMode, playback: Boolean) {
        if (VoiceService.state.value.running || CaptureProbe.state.value.busy) return
        try {
            notice = ""
            ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java)
                .setAction(if (playback) VoiceService.PROBE_PLAY else VoiceService.PROBE).putExtra("mode",mode.name))
        } catch (_: Exception) { notice = "测试未启动，请保持耳畔在前台并检查麦克风权限。" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val selected = pending; pending = null
        if (granted && selected != null) launch(selected,false) else notice = "录音测试需要麦克风权限。"
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val samples = exportSamples; exportSamples = emptyMap()
        if (uri != null && samples.isNotEmpty()) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { CaptureProbe.export(it,samples) }
                }
                notice = "已导出录音和报告，可交给电脑复测。"
            } catch (_: Exception) { notice = "导出失败，请换一个保存位置。" }
            finally { exporting = false }
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (CaptureProbe.state.value.busy) context.stopService(Intent(context,VoiceService::class.java)) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("收音对比",fontSize = 28.sp,fontFamily = FontFamily.Serif)
        Text("每组录 8 秒：先安静一下，再轻轻呼气，最后说一句短话。两组尽量保持相同距离和音量。",fontSize=14.sp)
        Text("原音只留在本机内存。这里不会发聊天，也不会调用转文字服务；点导出才保存文件。",fontSize=12.sp,color=ErpanColors.Muted)
        if (voice.running && !probe.busy) Text("请先结束通话或试听。",color=ErpanColors.Rose)
        Text(probe.message,color=ErpanColors.Rose)
        for (mode in ProbeMode.entries) {
            val sample = probe.samples[mode]
            Surface(color=ErpanColors.Paper,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Text(mode.title,fontSize=20.sp,fontFamily=FontFamily.Serif)
                    Text(if(mode==ProbeMode.CURRENT) "复现 code63：通话模式，保留回声消除。"
                        else "普通录音模式，优先使用手机声明支持的未处理通道，关闭可控音效。",fontSize=12.sp,color=ErpanColors.Muted)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=!voice.running && !probe.busy && !exporting,onClick={
                            if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) launch(mode,false)
                            else { pending=mode; permission.launch(Manifest.permission.RECORD_AUDIO) }
                        }) { Text(if(sample==null) "录 8 秒" else "重新录制") }
                        OutlinedButton(enabled=sample!=null && !voice.running && !probe.busy,onClick={launch(mode,true)}) { Text("原音试听") }
                    }
                    if(sample!=null) {
                        Text(String.format(Locale.ROOT,"录音 %.2f 秒 · 连续全零最长 %.0f ms",sample.metrics.seconds,sample.metrics.longestZeroMillis),fontSize=12.sp)
                        if(mode==ProbeMode.MINIMAL && sample.details.contains("unprocessedAdvertised=false"))
                            Text("手机未声明支持未处理通道，本次使用语音识别通道并关闭可控音效。",fontSize=12.sp,color=ErpanColors.Rose)
                        Text(sample.modelReport,fontSize=12.sp)
                        TextButton(onClick={technical=sample.details}) { Text("查看录音通道记录") }
                    }
                }
            }
        }
        if(probe.busy) Button(onClick={context.stopService(Intent(context,VoiceService::class.java))}) { Text("停止测试／试听") }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=probe.samples.isNotEmpty() && !probe.busy && !exporting,onClick={
                exportSamples=probe.samples; export.launch("耳畔-收音对比.zip")
            }) { Text(if(exporting) "正在导出…" else "导出给电脑") }
            TextButton(enabled=!probe.busy && !exporting,onClick={CaptureProbe.clear()}) { Text("清空本次录音") }
        }
        Text("全零和模型分数只是检查线索，不能单独判定降噪或识别准确率。先听原音是否完整，再判断模型。",fontSize=12.sp,color=ErpanColors.Muted)
        if(notice.isNotEmpty()) Text(notice,color=ErpanColors.Rose)
    }
    technical?.let { text -> AlertDialog(onDismissRequest={technical=null},title={Text("录音通道记录")},
        text={Text(text)},confirmButton={TextButton(onClick={technical=null}) { Text("关闭") }}) }
}
