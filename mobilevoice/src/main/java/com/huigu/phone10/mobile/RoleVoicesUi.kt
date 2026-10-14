package com.huigu.phone10.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun RoleVoicesContent(settings: MobileSettings, enabled: Boolean, roles: List<String>, error: String,
    adding: Boolean, onAdding: (Boolean) -> Unit, onRefresh: () -> Unit, onBind: (String, String?) -> Unit,
    modifier: Modifier=Modifier) {
    var name by rememberSaveable(settings.chatId) { mutableStateOf("") }
    var editing by rememberSaveable(settings.chatId) { mutableStateOf<String?>(null) }
    val bindings=settings.roleVoiceBindings.orEmpty().filter {it.chatId==settings.chatId}
    val names=(bindings.map {it.roleName}+roles).distinct()
    val available=enabled && settings.chatId.isNotBlank()
    Column(modifier) {
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=24.dp,vertical=18.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前聊天",fontSize=12.sp,color=ErpanColors.Muted)
                        Text(settings.chatTitle.ifBlank {if(settings.chatId.isBlank())"尚未选择聊天" else "当前聊天"},fontSize=16.sp,
                            modifier=Modifier.padding(top=4.dp))
                    }
                    TextButton(enabled=available,onClick=onRefresh) {
                        VoiceControlIcon("refresh",if(available)VoiceSelectionAccent else ErpanColors.Muted)
                        Text("读取成员",modifier=Modifier.padding(start=8.dp))
                    }
                }
                if(error.isNotBlank()) Text(error,color=VoiceSelectionAccent,fontSize=13.sp,modifier=Modifier.padding(bottom=12.dp))
                Row(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
                    Text("成员",Modifier.weight(0.4f),fontSize=12.sp,color=ErpanColors.Muted)
                    Text("使用音色",Modifier.weight(0.6f),fontSize=12.sp,color=ErpanColors.Muted)
                }
                HorizontalDivider(color=ErpanColors.Line,thickness=0.6.dp)
            }
            items(names,key={it}) { role ->
                val binding=bindings.firstOrNull {it.roleName==role}
                val profile=binding?.let {b->settings.profiles().firstOrNull {it.id==b.profileId}}
                Row(Modifier.fillMaxWidth().heightIn(min=76.dp).clickable(enabled=available){editing=role}.padding(vertical=18.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Text(role,Modifier.weight(0.4f).padding(end=12.dp),fontSize=17.sp,fontWeight=FontWeight.Medium)
                    Text(if(binding==null)"跟随当前音色" else profile?.name ?: "方案已移除，请重选",
                        Modifier.weight(0.6f).padding(end=8.dp),fontSize=15.sp,
                        color=if(binding!=null && profile==null)VoiceSelectionAccent else ErpanColors.Ink)
                    LineIcon(ErpanIcon.NEXT,ErpanColors.Muted,Modifier.size(16.dp))
                }
                HorizontalDivider(color=ErpanColors.Line,thickness=0.6.dp)
            }
            if(names.isEmpty()) item {Text("读取最近说话的成员，或手动添加。",fontSize=14.sp,color=ErpanColors.Muted,modifier=Modifier.padding(vertical=22.dp))}
            item {
                VoiceAddAction("添加成员",available) {onAdding(true)}
                Text("未绑定成员跟随当前音色。",fontSize=12.sp,color=ErpanColors.Muted,modifier=Modifier.padding(top=14.dp))
            }
        }
        Text("仅用于当前聊天 · 同名成员共用绑定",fontSize=12.sp,color=ErpanColors.Muted,
            modifier=Modifier.padding(horizontal=24.dp,vertical=16.dp))
    }
    if(adding) AlertDialog(onDismissRequest={onAdding(false)},containerColor=ErpanColors.Paper,
        title={Text("添加成员",fontFamily=FontFamily.Serif)},text={
            Column {
                Text("填写 O 消息中的角色名，改名后需重新绑定。",fontSize=13.sp,color=ErpanColors.Muted)
                OutlinedTextField(name,{name=it.take(256)},enabled=available,singleLine=true,label={Text("角色名")},modifier=Modifier.padding(top=12.dp))
            }
        },confirmButton={TextButton(enabled=available && name.isNotBlank(),onClick={editing=name.trim();name="";onAdding(false)}) {Text("选择音色")}},
        dismissButton={TextButton(onClick={onAdding(false)}) {Text("取消")}})
    editing?.let { role ->
        val binding=bindings.firstOrNull {it.roleName==role}
        AlertDialog(onDismissRequest={editing=null},containerColor=ErpanColors.Paper,
            title={Text("给 $role 选音色",fontFamily=FontFamily.Serif)},text={
                LazyColumn(Modifier.heightIn(max=420.dp)) {
                    item {VoiceProfileRow("跟随当前音色","",binding==null,available,{onBind(role,null);editing=null})}
                    items(settings.profiles(),key={it.id}) { profile ->
                        VoiceProfileRow(profile.name,profile.description(),binding?.profileId==profile.id,available,
                            {onBind(role,profile.id);editing=null})
                    }
                    if(settings.profiles().isEmpty()) item {Text("先保存声音方案，再回来绑定。",fontSize=13.sp,modifier=Modifier.padding(vertical=14.dp))}
                }
            },confirmButton={TextButton(onClick={editing=null}) {Text("返回")}})
    }
}
