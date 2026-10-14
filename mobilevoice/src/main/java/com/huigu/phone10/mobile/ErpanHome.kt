package com.huigu.phone10.mobile

import android.graphics.drawable.Drawable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable internal fun ErpanHome(settings: MobileSettings, state: VoiceState, avatar: Drawable?,
    appearance: AvatarAppearance,
    busy: Boolean, notice: String, onStart: () -> Unit, onEnd: () -> Unit,
    onOverlay: (Boolean) -> Unit, onSmart: (Boolean) -> Unit, onConfirmBeforeSend: (Boolean) -> Unit,
    onVoiceInterruption: (Boolean) -> Unit, onReviewDraft: () -> Unit,
    onAvatar: () -> Unit,
    onConfig: () -> Unit, onLogs: () -> Unit, onAbout: () -> Unit,
    onProfiles: () -> Unit, onCaptions: (Boolean) -> Unit, onCaptionAppearance: () -> Unit, onKeepsakes: () -> Unit,
    onListenOnly: (Boolean) -> Unit, onInterrupt: () -> Unit) {
    var showTitle by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val configured = configurationIssues(settings).isEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 17.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("耳畔", fontFamily = FontFamily.Serif, fontSize = 34.sp)
                Box(Modifier.width(32.dp).height(2.dp).background(ErpanColors.Rose))
            }
            Text("让对话 · 靠近一点", color = ErpanColors.Muted, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 5.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.37f).aspectRatio(1f).border(0.7.dp, ErpanColors.Line, CircleShape).padding(10.dp)
                .border(0.7.dp, ErpanColors.Line, CircleShape).padding(4.dp).clip(CircleShape)
                .background(ErpanColors.Blush).clickable(enabled = !state.running, onClick = onAvatar), contentAlignment = Alignment.Center) {
                AndroidView(factory = { context ->
                    Phone10MicView(context).apply {
                        render(Phone10MicrophoneState(enabled = true))
                        isClickable = false
                    }
                }, modifier = Modifier.fillMaxSize(), update = { view ->
                    view.previewScale = minOf(2f, 90f / appearance.windowDp)
                    view.appearance = appearance
                    if (view.avatar !== avatar) view.avatar = avatar
                })
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center) {
                    Text("头像与光效", color = Color.White, fontSize = 11.sp)
                }
            }
            Surface(Modifier.weight(0.63f), color = ErpanColors.Paper, shape = RoundedCornerShape(15.dp),
                border = BorderStroke(0.8.dp, ErpanColors.Line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("当前状态", fontSize = 16.sp, fontFamily = FontFamily.Serif)
                    Box(Modifier.width(23.dp).height(1.5.dp).background(ErpanColors.Rose))
                    Text(settings.displayChat(), fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 36.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { showTitle = true })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(9.dp).background(if (state.running && state.micEnabled) ErpanColors.Rose else Color(0xFF9A9AA0), CircleShape))
                        Text(state.homeStatus(configured), color = ErpanColors.Muted, fontSize = 15.sp)
                    }
                    HorizontalDivider(color = ErpanColors.Line, thickness = 0.6.dp)
                    Text("声音回手机 · 文字回 Operit", color = ErpanColors.Muted, fontSize = 10.sp, maxLines = 2)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !settings.listenOnly, onClick = { onListenOnly(false) }, enabled = !state.running && !busy,
                    label = { Text("语音通话") })
                FilterChip(selected = settings.listenOnly, onClick = { onListenOnly(true) }, enabled = !state.running && !busy,
                    label = { Text("只听回复") })
            }
            Text(if (settings.speech.clientSegmentedTts) "首个断句标点到达即提交合成，后续合段；实际出声仍需等待模型生成。"
            else if (settings.speech.wholeReplyTts) {
                if (settings.listenOnly) "在 Operit 所选聊天中打字，整条回复写完后开始朗读；无需开麦。"
                else "你说话，等待 AI 整条回复写完后播放声音。"
            } else if (settings.listenOnly) "在 Operit 所选聊天中打字，回复生成时陆续朗读；无需开麦。" else "你说话，AI 用语音回复。",
                color = ErpanColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CallButton(if (settings.listenOnly) "开始听回复" else "开始通话", ErpanIcon.PHONE, enabled = !state.running && !busy, primary = true,
                modifier = Modifier.weight(1.04f), onClick = onStart)
            CallButton("结束语音", ErpanIcon.END, enabled = state.running, primary = false,
                modifier = Modifier.weight(0.96f), onClick = onEnd)
        }
        if (state.running && state.listenOnly) TextButton(onClick = onInterrupt) { Text("停止播放当前及排队语音") }
        if (notice.isNotBlank()) Text(notice, color = ErpanColors.Rose, fontSize = 13.sp, lineHeight = 19.sp)
        if (state.running || state.message.contains("失败") || state.message.contains("占用"))
            Text(state.message, color = ErpanColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        if (state.pendingDraft != null) Surface(onClick = onReviewDraft, shape = RoundedCornerShape(14.dp),
            color = ErpanColors.Blush, border = BorderStroke(0.8.dp, ErpanColors.Rose), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("语音待确认 · 尚未发送", fontSize = 17.sp, color = ErpanColors.Ink)
                Text("点开悬浮待发栏改字或发送；挂断会取消。", fontSize = 13.sp, color = ErpanColors.Muted)
            }
        }
        Surface(onClick = onConfig, shape = RoundedCornerShape(14.dp), color = ErpanColors.Paper,
            border = BorderStroke(0.8.dp, ErpanColors.Line), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal=18.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("连接配置",fontFamily=FontFamily.Serif,fontSize=20.sp)
                    Text("聊天 · 识别 · 合成",color=ErpanColors.Muted,fontSize=12.sp)
                }
                LineIcon(ErpanIcon.NEXT,modifier=Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            VoiceShortcut("声音收藏", "", true, true, Modifier.weight(1f), onKeepsakes)
            VoiceShortcut("音色切换", settings.currentVoiceProfile()?.name ?: settings.displayVoice(),
                false, !state.running && !busy, Modifier.weight(1f), onProfiles)
        }
        if (state.running) Text("结束语音后可切换音色",color=ErpanColors.Muted,fontSize=12.sp)
        val panel = remember { GenericShape { size, _ ->
            val cut = minOf(size.width * 0.06f, size.height * 0.1f)
            moveTo(0f, 0f); lineTo(size.width - cut, 0f); lineTo(size.width, cut)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        } }
        Surface(color = ErpanColors.Coal, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().clip(panel)) {
            Column(Modifier.padding(horizontal = 19.dp, vertical = 22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionTitle("通话设置", dark = true)
                    TextButton(onClick = onLogs, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text("日志", fontSize = 12.sp, color = ErpanColors.CoalMuted)
                        Spacer(Modifier.width(4.dp))
                        LineIcon(ErpanIcon.ARROW, ErpanColors.CoalMuted, Modifier.size(14.dp))
                    }
                }
                Column(Modifier.padding(top = 12.dp)) {
                    CompactCallToggle("后台悬浮球", if (state.running) state.overlayVisible else settings.overlayEnabled,
                        enabled = !busy && !settings.listenOnly,
                        helper = "点悬浮球开关麦克风", onChange = onOverlay)
                    CallSettingDivider()
                    CompactCallToggle("发送前确认", settings.confirmBeforeSend,
                        enabled = !settings.listenOnly && !state.running && !busy,
                        helper = "确认后发送", onChange = onConfirmBeforeSend)
                    CallSettingDivider()
                    CompactCallToggle("智能结束判断", settings.smartEndpoint && !settings.confirmBeforeSend,
                        enabled = !settings.listenOnly && !state.running && !busy && !settings.confirmBeforeSend,
                        helper = if (settings.confirmBeforeSend) "确认发送时不启用"
                            else if (settings.smartEndpoint) "判断是否说完 · 另计模型费用" else "停顿后自动发送",
                        onChange = onSmart)
                    CallSettingDivider()
                    CompactCallToggle("人声打断", !settings.disableVoiceInterruption,
                        enabled = !settings.listenOnly && !busy,
                        helper = "说话时停止 AI 朗读", onChange = onVoiceInterruption)
                    CallSettingDivider()
                    CompactCallToggle("悬浮字幕", if (state.running) state.captionsVisible else settings.captionsEnabled,
                        enabled = !busy, helper = "字幕设置", onChange = onCaptions,
                        onSettings = onCaptionAppearance)
                }
            }
        }
        Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(14.dp), color = ErpanColors.Paper,
            border = BorderStroke(0.8.dp, ErpanColors.Line), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("扩展功能", Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 21.sp)
                LineIcon(if (expanded) ErpanIcon.DOWN else ErpanIcon.PLUS)
            }
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("更多接入方式会在这里加入。", color = ErpanColors.Muted, fontSize = 13.sp)
            TextButton(onClick = onAbout) { Text("关于耳畔") }
        }
    }
    if (showTitle) AlertDialog(onDismissRequest = { showTitle = false }, title = { Text("当前聊天") },
        text = { Text(settings.displayChat()) }, confirmButton = { TextButton(onClick = { showTitle = false }) { Text("关闭") } })
}

/** Small utilities share one row; avatar editing remains on the existing portrait above. */
@Composable private fun VoiceShortcut(label: String, detail: String, heart: Boolean, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val tint=if(enabled) Color(0xFFB77E90) else ErpanColors.Muted.copy(alpha=0.45f)
    Surface(onClick=onClick,enabled=enabled,color=ErpanColors.Paper,shape=RoundedCornerShape(12.dp),modifier=modifier) {
        Column(Modifier.padding(horizontal=8.dp,vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Canvas(Modifier.size(32.dp)) {
                val stroke=Stroke(width=1.5f,cap=StrokeCap.Round,join=StrokeJoin.Round)
                scale(size.width/28f,size.height/28f,pivot=Offset.Zero) {
                    if(heart) {
                        val path=Path().apply {
                            moveTo(14f,24f)
                            cubicTo(11f,21.4f,2.5f,15.2f,2.5f,9.1f)
                            cubicTo(2.5f,2.9f,10.1f,1.5f,14f,7.2f)
                            cubicTo(17.9f,1.5f,25.5f,2.9f,25.5f,9.1f)
                            cubicTo(25.5f,15.2f,17f,21.4f,14f,24f);close()
                        }
                        drawPath(path,tint,style=stroke)
                    } else {
                        drawLine(tint,Offset(2f,8f),Offset(5.5f,8f),1.5f,StrokeCap.Round)
                        drawLine(tint,Offset(12.5f,8f),Offset(26f,8f),1.5f,StrokeCap.Round)
                        drawCircle(tint,3.5f,Offset(9f,8f),style=stroke)
                        drawLine(tint,Offset(2f,20f),Offset(15.5f,20f),1.5f,StrokeCap.Round)
                        drawLine(tint,Offset(22.5f,20f),Offset(26f,20f),1.5f,StrokeCap.Round)
                        drawCircle(tint,3.5f,Offset(19f,20f),style=stroke)
                    }
                }
            }
            Text(label,fontFamily=FontFamily.Serif,fontSize=17.sp,maxLines=1,
                color=if(enabled) ErpanColors.Ink else ErpanColors.Muted)
            Text(detail,fontSize=12.sp,color=ErpanColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun CallSettingDivider() {
    HorizontalDivider(color = ErpanColors.CoalMuted.copy(alpha = 0.2f), thickness = 0.7.dp)
}

@Composable private fun CompactCallToggle(title: String, checked: Boolean, enabled: Boolean,
    helper: String, onChange: (Boolean) -> Unit, onSettings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (onSettings == null) 3.dp else 0.dp)) {
            Text(title, fontSize = 17.sp, color = ErpanColors.CoalText)
            if (onSettings == null) {
                Text(helper, color = ErpanColors.CoalMuted, fontSize = 12.sp, lineHeight = 17.sp)
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onSettings),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(helper, color = ErpanColors.CoalMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    LineIcon(ErpanIcon.NEXT, ErpanColors.CoalMuted, Modifier.size(14.dp))
                }
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
            thumbContent = { Box(Modifier.size(16.dp)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFF4F2F1), uncheckedThumbColor = Color(0xFFF4F2F1),
                checkedTrackColor = Color(0xFF646266), uncheckedTrackColor = Color(0xFF49484C),
                checkedBorderColor = Color(0xFFCAC7CA), uncheckedBorderColor = Color(0xFFCAC7CA),
                disabledCheckedThumbColor = Color(0xFFAAA8AB), disabledUncheckedThumbColor = Color(0xFFAAA8AB),
                disabledCheckedTrackColor = Color(0xFF454448), disabledUncheckedTrackColor = Color(0xFF454448),
                disabledCheckedBorderColor = Color(0xFF777579), disabledUncheckedBorderColor = Color(0xFF777579)))
    }
}

@Composable private fun CallButton(label: String, icon: ErpanIcon, enabled: Boolean, primary: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val tint = if (enabled) { if (primary) ErpanColors.Rose else ErpanColors.Ink } else Color(0xFFA0A0A5)
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 54.dp),
        color = if (primary && enabled) ErpanColors.Blush else ErpanColors.Paper,
        shape = RoundedCornerShape(50), border = BorderStroke(0.8.dp, if (primary && enabled) ErpanColors.Rose else ErpanColors.Line)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            LineIcon(icon, tint, Modifier.size(21.dp)); Spacer(Modifier.width(9.dp))
            Text(label, fontSize = 17.sp, fontFamily = FontFamily.Serif, color = if (enabled) ErpanColors.Ink else tint)
        }
    }
}
