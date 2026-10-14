package com.huigu.phone10.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LibraryRose=Color(0xFFAA5F76)
private val LibraryLine=Color(0xFFE9E2E2)

@Composable internal fun VoiceKeepsakesPage(onBack: () -> Unit) {
    val context=LocalContext.current
    val store=remember { VoiceKeepsakes(File(context.cacheDir,"voice-recent"),File(context.filesDir,"voice-saved")) }
    val scope=rememberCoroutineScope()
    val state by VoiceService.state.collectAsState()
    val replay by VoiceService.replayState.collectAsState()
    var recent by remember { mutableStateOf(emptyList<VoiceClip>()) }
    var saved by remember { mutableStateOf(emptyList<VoiceClip>()) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<VoiceClip?>(null) }
    var pendingPlay by remember { mutableStateOf<Pair<VoiceClip,Boolean>?>(null) }
    var info by remember { mutableStateOf(false) }
    var previewRequested by remember { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportSaved by rememberSaveable { mutableStateOf(true) }

    fun command(action: String, configure: Intent.() -> Unit = {}) {
        context.startService(Intent(context,VoiceService::class.java).setAction(action).apply(configure))
    }
    suspend fun refresh() {
        val entries=withContext(Dispatchers.IO) { store.recent() to store.saved() }
        recent=entries.first; saved=entries.second
    }
    fun action(work: suspend () -> Unit) {
        if (busy) return
        busy=true
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice="没有完成，请检查文件或手机存储后重试。" }
            finally { busy=false }
        }
    }
    fun save(clip: VoiceClip, index: Int?) {
        selected=null
        action {
            withContext(Dispatchers.IO) { store.save(clip.id,index) }
            refresh(); notice="已留下这段声音"
        }
    }
    fun start(clip: VoiceClip, permanent: Boolean) {
        try {
            previewRequested=true
            ContextCompat.startForegroundService(context,Intent(context,VoiceService::class.java)
                .setAction(VoiceService.REPLAY).putExtra("clip",clip.id).putExtra("saved",permanent))
            notice=""
        } catch (_: Exception) { notice="试听没有启动，请回到耳畔重试。" }
    }
    fun listen(clip: VoiceClip, permanent: Boolean) {
        val current=VoiceService.replayState.value
        if (current.active && current.id==clip.id && current.saved==permanent) command(VoiceService.REPLAY_PAUSE)
        else if (VoiceService.state.value.running && current.id.isEmpty()) pendingPlay=clip to permanent
        else if (VoiceService.state.value.running && !current.active) action {
            withTimeout(5000) { VoiceService.state.first { !it.running } }
            start(clip,permanent)
        }
        else start(clip,permanent)
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id=exportId; val permanent=exportSaved
        exportId=null
        if (uri!=null && id!=null) action {
            withContext(Dispatchers.IO) {
                store.openAudio(id,permanent).use { input ->
                    input.seek(0)
                    requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { out ->
                        val buffer=ByteArray(8192)
                        while (true) { val n=input.read(buffer); if (n<0) break; out.write(buffer,0,n) }
                    }
                }
            }
            notice="原音频已导出"
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice="声音列表暂时无法读取，请点击刷新。" }
            delay(2000)
        }
    }
    LaunchedEffect(replay.error) { replay.error?.let { notice=it } }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving this page stops its preview, never the live call.
            runCatching { if (previewRequested || VoiceService.replayState.value.active) command(VoiceService.REPLAY_STOP) }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=18.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,modifier=Modifier.semantics { contentDescription="返回" }) { LineIcon(ErpanIcon.BACK) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick={action { refresh();notice="列表已刷新" }},enabled=!busy,
                modifier=Modifier.semantics { contentDescription="刷新声音" }) { LibraryIcon("refresh") }
            IconButton(onClick={info=true},modifier=Modifier.semantics { contentDescription="收藏说明" }) { LibraryIcon("info") }
        }
        Text("声音收藏",fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=ErpanColors.Ink,
            modifier=Modifier.padding(top=8.dp,start=4.dp))
        Text("把喜欢的语气，留在这里",fontSize=14.sp,color=ErpanColors.Muted,
            modifier=Modifier.padding(start=4.dp,top=8.dp,bottom=20.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(24.dp),modifier=Modifier.padding(start=4.dp)) {
            LibraryTab("最近声音",!favorites) { favorites=false }
            LibraryTab("已收藏 · ${saved.size}",favorites) { favorites=true }
        }
        HorizontalDivider(color=LibraryLine)
        if (notice.isNotBlank()) Text(notice,color=LibraryRose,fontSize=12.sp,
            modifier=Modifier.padding(vertical=8.dp,horizontal=4.dp))
        val clips=if (favorites) saved else recent
        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=18.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(if (favorites) "留下的声音" else "最近",fontSize=16.sp,fontWeight=FontWeight.Medium)
            Text("${clips.size} 段声音",fontSize=13.sp,color=ErpanColors.Muted)
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)) {
            if (clips.isEmpty()) item {
                Text(if (favorites) "听到喜欢的一句，就把它留下。" else "还没有声音。播放一条回复后，回来听听吧。",
                    color=ErpanColors.Muted,modifier=Modifier.padding(vertical=36.dp,horizontal=8.dp))
            }
            items(clips,key={it.id}) { clip ->
                val permanent=favorites
                val starred=permanent || saved.any { it.sourceId==clip.id && it.sourceSegment==null }
                val current=replay.takeIf { it.id==clip.id && it.saved==permanent }
                LibraryClipRow(clip,current,starred,!busy,
                    onPlay={listen(clip,permanent)},onStar={if (!starred) save(clip,null) else notice="这段声音已收藏"},
                    onSeek={frame -> if (current?.active==true) command(VoiceService.REPLAY_SEEK) { putExtra("frame",frame) }},
                    onSelect=if (!permanent && clip.segments.size>1) ({selected=clip}) else null,
                    onCopy={
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("耳畔声音文字",clip.text));notice="文字已复制"
                    },onExport={ if (exportId==null) {
                        exportId=clip.id; exportSaved=permanent
                        exporter.launch("耳畔-${clip.id.take(8)}.wav")
                    } })
                HorizontalDivider(Modifier.padding(horizontal=6.dp),color=LibraryLine,thickness=0.5.dp)
            }
        }
        Text("点一下就能听，喜欢再收藏",fontSize=12.sp,color=ErpanColors.Muted,
            modifier=Modifier.align(Alignment.CenterHorizontally).padding(vertical=12.dp))
    }
    if (info) AlertDialog(onDismissRequest={info=false},title={Text("留下当时的声音")},text={
        Text("试听和导出使用已经播放过的原音频，不重新合成。最近声音最多保留 24 条、128 MB；收藏后长期保留。\n\n“未播完”只保留当时已播放的部分，文字可能包含没读到的内容。选一句按已有合成段落保存。")
    },confirmButton={TextButton(onClick={info=false}) {Text("知道了")}})
    pendingPlay?.let { (clip,permanent) ->
        AlertDialog(onDismissRequest={pendingPlay=null},title={Text("结束当前语音并试听？")},
            text={Text("试听会结束当前语音连接，停止正在朗读的内容。")},
            confirmButton={TextButton(onClick={
                pendingPlay=null
                action {
                    command(VoiceService.STOP)
                    withTimeout(5000) { VoiceService.state.first { !it.running } }
                    start(clip,permanent)
                }
            }) {Text("结束并试听")}},dismissButton={TextButton(onClick={pendingPlay=null}) {Text("继续通话")}})
    }
    selected?.let { clip ->
        AlertDialog(onDismissRequest={selected=null},title={Text("选一句收藏")},text={
            LazyColumn(Modifier.heightIn(max=420.dp)) {
                items(clip.segments.indices.toList()) { index ->
                    TextButton(onClick={save(clip,index)}) {
                        Text(clip.segments[index].text + if (clip.segments[index].complete) "" else "（未播完）")
                    }
                }
            }
        },confirmButton={TextButton(onClick={selected=null}) {Text("返回")}})
    }
}

@Composable private fun LibraryTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick=onClick).heightIn(min=48.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(text,fontSize=16.sp,fontWeight=if(selected) FontWeight.SemiBold else FontWeight.Normal,
            color=if(selected) LibraryRose else ErpanColors.Muted,modifier=Modifier.padding(vertical=10.dp))
        Box(Modifier.width(64.dp).height(3.dp).clip(CircleShape).background(if(selected) LibraryRose else Color.Transparent))
    }
}

@Composable private fun LibraryClipRow(clip: VoiceClip, replay: ClipReplayState?, starred: Boolean,
    enabled: Boolean, onPlay: () -> Unit, onStar: () -> Unit, onSeek: (Long) -> Unit,
    onSelect: (() -> Unit)?, onCopy: () -> Unit, onExport: () -> Unit) {
    var expanded by remember(clip.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var scrub by remember(clip.id) { mutableStateOf<Float?>(null) }
    val active=replay?.active==true
    val fraction=if (clip.frames>0) (replay?.position ?: 0).toFloat()/clip.frames else 0f
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if(active) Color(0xFFF8EDF0) else Color.Transparent).padding(vertical=16.dp,horizontal=6.dp),
        verticalAlignment=Alignment.Top) {
        IconButton(onClick=onPlay,enabled=enabled,modifier=Modifier.size(48.dp).semantics {
            contentDescription=if(active && replay?.paused==false) "暂停试听" else "播放这段声音"
        }) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(if(active) LibraryRose else Color.Transparent),contentAlignment=Alignment.Center) {
                LibraryIcon(if (replay?.loading==true) "loading" else if(active && !replay!!.paused) "pause" else "play",
                    modifier=Modifier.size(30.dp),tint=if(active) Color.White else LibraryRose)
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(clip.text.ifBlank { "这段声音没有对应文字" },fontSize=16.sp,lineHeight=25.sp,
                maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.fillMaxWidth().clickable {expanded=!expanded})
            Row(Modifier.fillMaxWidth().padding(top=5.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(clip.voice,fontSize=12.sp,color=ErpanColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(clip.created)),fontSize=11.sp,color=ErpanColors.Muted)
                }
                IconButton(onClick=onStar,enabled=enabled,modifier=Modifier.size(48.dp).semantics {contentDescription=if(starred) "已收藏" else "收藏声音"}) {
                    LibraryIcon(if(starred) "starred" else "star",tint=LibraryRose)
                }
                Box {
                    IconButton(onClick={menu=true},modifier=Modifier.size(48.dp).semantics {contentDescription="更多操作"}) {LibraryIcon("more",tint=ErpanColors.Muted)}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        onSelect?.let { select -> DropdownMenuItem(text={Text("选一句收藏")},enabled=enabled,onClick={menu=false;select()}) }
                        DropdownMenuItem(text={Text("复制文字")},onClick={menu=false;onCopy()})
                        DropdownMenuItem(text={Text("导出原音频")},enabled=enabled,onClick={menu=false;onExport()})
                    }
                }
            }
            if (!clip.complete) Text("未播完",fontSize=11.sp,color=ErpanColors.Muted,
                modifier=Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFECE8E8)).padding(horizontal=6.dp,vertical=2.dp))
            Slider(value=scrub ?: fraction,onValueChange={scrub=it},onValueChangeFinished={
                scrub?.let {onSeek((it*clip.frames).toLong())};scrub=null
            },enabled=active && replay?.loading==false,modifier=Modifier.fillMaxWidth().height(48.dp).semantics {contentDescription="试听进度"},
                colors=SliderDefaults.colors(thumbColor=LibraryRose,activeTrackColor=LibraryRose,inactiveTrackColor=LibraryLine,
                    disabledThumbColor=LibraryLine,disabledActiveTrackColor=LibraryLine,disabledInactiveTrackColor=LibraryLine))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(libraryTime(((scrub ?: fraction)*clip.frames).toLong(),clip.rate),fontSize=11.sp,color=ErpanColors.Muted)
                Text(libraryTime(clip.frames,clip.rate),fontSize=11.sp,color=ErpanColors.Muted)
            }
        }
    }
}

private fun libraryTime(frames: Long, rate: Int): String {
    val seconds=frames/rate.coerceAtLeast(1)
    return "%d:%02d".format(Locale.ROOT,seconds/60,seconds%60)
}

@Composable private fun LibraryIcon(name: String, modifier: Modifier=Modifier, tint: Color=ErpanColors.Ink) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero) {
            val stroke=Stroke(1.6f,cap=StrokeCap.Round)
            when(name) {
                "play" -> {
                    drawCircle(tint,radius=10.2f,center=Offset(12f,12f),style=stroke)
                    drawPath(Path().apply {moveTo(9f,7f);lineTo(17f,12f);lineTo(9f,17f);close()},tint)
                }
                "pause" -> { drawLine(tint,Offset(9f,7f),Offset(9f,17f),2.3f,StrokeCap.Round);drawLine(tint,Offset(15f,7f),Offset(15f,17f),2.3f,StrokeCap.Round) }
                "star", "starred" -> {
                    val p=Path()
                    for(i in 0..9) {
                        val angle=-Math.PI/2+i*Math.PI/5;val r=if(i%2==0) 10.0 else 4.7
                        val x=12+(kotlin.math.cos(angle)*r).toFloat();val y=12+(kotlin.math.sin(angle)*r).toFloat()
                        if(i==0) p.moveTo(x,y) else p.lineTo(x,y)
                    }
                    p.close(); if(name=="starred") drawPath(p,tint) else drawPath(p,tint,style=stroke)
                }
                "more" -> for(y in listOf(5f,12f,19f)) drawCircle(tint,1.4f,Offset(12f,y))
                "info" -> {
                    drawCircle(tint,10f,Offset(12f,12f),style=stroke);drawCircle(tint,1f,Offset(12f,7f))
                    drawLine(tint,Offset(12f,11f),Offset(12f,17f),1.6f,StrokeCap.Round)
                }
                else -> {
                    drawArc(tint,30f,300f,false,topLeft=Offset(3f,3f),size=androidx.compose.ui.geometry.Size(18f,18f),style=stroke)
                    if(name=="refresh") drawPath(Path().apply {moveTo(16f,2f);lineTo(21f,5f);lineTo(22f,0f)},tint,style=stroke)
                }
            }
        }
    }
}
