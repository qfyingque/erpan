package com.huigu.phone10.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val VoiceSelectionAccent=Color(0xFFAA5F76)

@Composable internal fun VoiceProfilesPage(settings: MobileSettings, busy: Boolean, running: Boolean,
    error: String, groupTab: Boolean, onTab: (Boolean) -> Unit,
    onUse: (String) -> Unit, onRemove: (String) -> Unit, onCreate: () -> Unit, onBack: () -> Unit,
    localVoices: List<LocalVoice>, catalogFresh: Boolean, onRefresh: () -> Unit,
    onLocalVoice: (LocalVoice) -> Unit, roles: List<String>, roleError: String,
    onRefreshRoles: () -> Unit, onBind: (String, String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<VoiceProfile?>(null) }
    var catalog by rememberSaveable { mutableStateOf(false) }
    var addingMember by rememberSaveable { mutableStateOf(false) }
    val enabled=!busy && !running
    val profiles=settings.profiles()
    val current=settings.currentVoiceProfile()?.id
    val matches=profiles.filter { it.name.contains(query.trim(),true) || it.description().contains(query.trim(),true) }
    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=VoiceSelectionAccent)) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=12.dp),
            verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,modifier=Modifier.size(48.dp).semantics {contentDescription="返回"}) {LineIcon(ErpanIcon.BACK)}
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                Text("音色",fontSize=26.sp,fontFamily=FontFamily.Serif)
            }
            IconButton(onClick={if(groupTab) addingMember=true else onCreate()},
                enabled=enabled && (!groupTab || settings.chatId.isNotBlank()),
                modifier=Modifier.size(48.dp).semantics {contentDescription=if(groupTab)"添加成员" else "添加声音方案"}) {
                LineIcon(ErpanIcon.PLUS,if(enabled) VoiceSelectionAccent else ErpanColors.Muted)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(false to "我的音色",true to "群聊绑定").forEach { (group,label) ->
                Tab(selected=groupTab==group,onClick={onTab(group)},modifier=Modifier.weight(1f),
                    selectedContentColor=ErpanColors.Ink,unselectedContentColor=ErpanColors.Muted) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(label,modifier=Modifier.padding(vertical=14.dp),fontSize=16.sp,
                            fontWeight=if(groupTab==group) FontWeight.Medium else FontWeight.Normal)
                        HorizontalDivider(Modifier.width(82.dp),thickness=2.dp,
                            color=if(groupTab==group) VoiceSelectionAccent else Color.Transparent)
                    }
                }
            }
        }
        HorizontalDivider(color=ErpanColors.Line,thickness=0.6.dp)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=VoiceSelectionAccent,trackColor=ErpanColors.Line)
        if(running) Text("结束通话后可切换音色。",modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp),
            color=ErpanColors.Muted,fontSize=13.sp)
        if(error.isNotBlank()) Text(error,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp),color=VoiceSelectionAccent,fontSize=13.sp)
        if(groupTab) {
            RoleVoicesContent(settings,enabled,roles,roleError,addingMember,{addingMember=it},onRefreshRoles,onBind,
                modifier=Modifier.weight(1f))
        } else {
            TextField(query,{query=it},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=14.dp),
                singleLine=true,placeholder={Text("搜索音色",fontSize=15.sp,color=ErpanColors.Muted)},
                leadingIcon={VoiceControlIcon("search",ErpanColors.Muted)},
                colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,
                    focusedIndicatorColor=VoiceSelectionAccent,unfocusedIndicatorColor=ErpanColors.Line))
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("已保存",fontSize=12.sp,color=ErpanColors.Muted)
                        Text("${matches.size}",fontSize=12.sp,color=ErpanColors.Muted)
                    }
                    HorizontalDivider(color=ErpanColors.Line,thickness=0.6.dp)
                }
                items(matches,key={it.id}) { profile ->
                    VoiceProfileRow(profile.name,profile.description(),current==profile.id,enabled,
                        onClick={if(current!=profile.id)onUse(profile.id)},onRemove={confirmation=profile})
                }
                if(matches.isEmpty()) item {
                    Text(if(profiles.isEmpty())"还没有保存声音方案。" else "没有找到匹配的音色。",
                        modifier=Modifier.padding(vertical=28.dp),fontSize=14.sp,color=ErpanColors.Muted)
                }
                item {
                    VoiceAddAction("添加声音方案",enabled,onCreate)
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable {catalog=true},
                        verticalAlignment=Alignment.CenterVertically) {
                        Text("电脑上的声音",Modifier.weight(1f),fontSize=14.sp,color=ErpanColors.Muted)
                        LineIcon(ErpanIcon.NEXT,ErpanColors.Muted,Modifier.size(16.dp))
                    }
                }
            }
        }
    }
    confirmation?.let { profile ->
        AlertDialog(onDismissRequest={if(!busy)confirmation=null},containerColor=ErpanColors.Paper,
            title={Text("移除「${profile.name}」？")},
            text={Text("当前语音配置仍会保留。绑定了这个方案的成员，需要重新选择音色。")},
            confirmButton={TextButton(enabled=enabled,onClick={confirmation=null;onRemove(profile.id)}) {Text("移除")}},
            dismissButton={TextButton(enabled=!busy,onClick={confirmation=null}) {Text("取消")}})
    }
    if(catalog) AlertDialog(onDismissRequest={catalog=false},containerColor=ErpanColors.Paper,
        title={Text("电脑上的声音",fontFamily=FontFamily.Serif)},text={
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
                TextButton(enabled=enabled && settings.localVoiceConnection()!=null,onClick=onRefresh) {
                    Text(if(busy)"正在读取…" else "刷新声音列表")
                }
                if(error.isNotBlank()) Text(error,fontSize=13.sp,color=VoiceSelectionAccent)
                if(settings.localVoiceConnection()==null) Text("先在连接配置保存本机语音地址和密钥。",fontSize=13.sp)
                else if(localVoices.isEmpty()) Text("刷新后可查看电脑已注册的声音。",fontSize=13.sp,color=ErpanColors.Muted)
                if(localVoices.isNotEmpty() && !catalogFresh) Text("保留上次列表，使用时会重新确认。",fontSize=12.sp,color=ErpanColors.Muted)
                localVoices.forEach { voice ->
                    Column(Modifier.padding(vertical=10.dp)) {
                        Text(voice.name,fontSize=16.sp)
                        Text("${voice.model} · ${voice.id}",fontSize=12.sp,color=ErpanColors.Muted)
                        Text(if(voice.ready)"已就绪 · ${voice.supportedModes.joinToString().ifBlank {"待确认"}}" else "当前不可用，请先在电脑端准备",
                            fontSize=12.sp,color=ErpanColors.Muted)
                        TextButton(enabled=enabled && voice.ready && voice.supportedModes.any {it in setOf("none","sentence")},
                            onClick={catalog=false;onLocalVoice(voice)}) {Text("保存并使用")}
                    }
                    HorizontalDivider(color=ErpanColors.Line,thickness=0.6.dp)
                }
            }
        },confirmButton={TextButton(onClick={catalog=false}) {Text("完成")}})
    }
}

@Composable internal fun VoiceProfileRow(name: String, detail: String, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min=78.dp).semantics {this.selected=selected},verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min=78.dp).clickable(enabled=enabled,onClick=onClick).padding(vertical=14.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name,fontSize=16.sp,color=if(selected)VoiceSelectionAccent else ErpanColors.Ink,
                    fontWeight=FontWeight.Medium)
                if(detail.isNotBlank()) Text(detail,fontSize=12.sp,color=ErpanColors.Muted,maxLines=2,overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=5.dp))
            }
            if(selected) {
                Spacer(Modifier.width(8.dp))
                VoiceControlIcon("check",VoiceSelectionAccent)
                if(onRemove!=null) Text("当前",fontSize=11.sp,color=VoiceSelectionAccent,modifier=Modifier.padding(start=4.dp))
            }
        }
        if(onRemove!=null) Box {
            IconButton(onClick={menu=true},enabled=enabled,modifier=Modifier.size(48.dp).semantics {contentDescription="${name}的更多操作"}) {
                VoiceControlIcon("more",ErpanColors.Muted)
            }
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text("移除方案")},onClick={menu=false;onRemove()})
            }
        }
    }
    HorizontalDivider(color=ErpanColors.Line,thickness=0.6.dp)
}

@Composable internal fun VoiceAddAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=60.dp).clickable(enabled=enabled,onClick=onClick),verticalAlignment=Alignment.CenterVertically) {
        LineIcon(ErpanIcon.PLUS,if(enabled)VoiceSelectionAccent else ErpanColors.Muted,Modifier.size(22.dp))
        Text(label,modifier=Modifier.padding(start=12.dp),color=if(enabled)VoiceSelectionAccent else ErpanColors.Muted,fontSize=15.sp)
    }
}

@Composable internal fun VoiceControlIcon(kind: String, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero) {
            when(kind) {
                "more" -> for(y in listOf(5f,12f,19f)) drawCircle(color,1.4f,Offset(12f,y))
                "check" -> {drawLine(color,Offset(4f,12f),Offset(9f,17f),1.8f,StrokeCap.Round);drawLine(color,Offset(9f,17f),Offset(20f,6f),1.8f,StrokeCap.Round)}
                "search" -> {drawCircle(color,7f,Offset(10f,10f),style=Stroke(1.6f));drawLine(color,Offset(15f,15f),Offset(21f,21f),1.6f,StrokeCap.Round)}
                "refresh" -> {drawArc(color,45f,295f,false,Offset(3f,3f),androidx.compose.ui.geometry.Size(18f,18f),style=Stroke(1.6f));drawLine(color,Offset(21f,3f),Offset(21f,9f),1.6f);drawLine(color,Offset(15f,9f),Offset(21f,9f),1.6f)}
            }
        }
    }
}

@Composable internal fun VoiceProfileNameDialog(initialName: String, busy: Boolean, error: String,
    onSave: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initialName.take(40)) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("给新方案起个名字") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存当前的识别服务、合成服务和音色。密钥继续加密保存在本机。", fontSize = 13.sp)
            OutlinedTextField(name, onValueChange = { name = it.take(40) }, enabled = !busy,
                label = { Text("方案名称") }, placeholder = { Text("例如：日常百炼、家里本地声音") }, singleLine = true)
            if (error.isNotBlank()) Text(error, color = VoiceSelectionAccent, fontSize = 13.sp)
        } }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { onSave(name) }) { Text("保存并使用") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("取消") } })
}
