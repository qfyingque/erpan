package com.huigu.phone10.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun ErpanConfig(settings: MobileSettings, enabled: Boolean, listing: Boolean, notice: String,
    target: String, onChange: (MobileSettings) -> Unit, onStt: (String) -> Unit, onTts: (String) -> Unit,
    onChats: () -> Unit, onSave: () -> Unit, onBack: () -> Unit, onAbout: () -> Unit) {
    val voiceTarget = remember { BringIntoViewRequester() }
    val judgeTarget = remember { BringIntoViewRequester() }
    LaunchedEffect(target) {
        delay(120)
        if (target == "voice") voiceTarget.bringIntoView()
        if (target == "judge" && settings.smartEndpoint) judgeTarget.bringIntoView()
    }
    val speech = settings.speech
    val ttsProvider = speech.effectiveTtsProvider
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageHeading("连接配置", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Text("填好服务信息，就可以开始通话", color = ErpanColors.Muted, fontSize = 13.sp)
            if (!enabled) Text("通话中可查看配置；结束语音后再修改。", color = ErpanColors.Rose, fontSize = 13.sp)
            if (notice.isNotBlank()) Text(notice, color = ErpanColors.Rose, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            SectionTitle("聊天连接")
            ErpanNavigationCard("选中聊天窗口", subtitle = if (listing) "正在读取…" else settings.displayChat(),
                enabled = enabled && !listing, onClick = onChats)
            Hint("聊天模型、角色和历史在 Operit 中设置")
            ConfigDivider()
            SectionTitle("语音识别")
            ProviderField("服务商", speech.provider ?: SpeechConfig.OPENAI, enabled,
                listOf(SpeechConfig.BAILIAN to "阿里云百炼", SpeechConfig.OPENAI to "Audio API 兼容"), onStt)
            FormField("识别接口地址", speech.sttBaseUrl, enabled, placeholder = "填写识别服务的地址") { onChange(settings.copy(speech = speech.copy(sttBaseUrl = it.trim()))) }
            FormField("识别 API Key", speech.sttKey, enabled, secret = true,
                placeholder = if (speech.isBailian) "填写百炼 API Key" else "填写识别服务的 API Key") { onChange(settings.copy(speech = speech.copy(sttKey = it.trim()))) }
            FormField("识别模型", speech.sttModel, enabled) { onChange(settings.copy(speech = speech.copy(sttModel = it.trim()))) }
            Hint(if (speech.isBailian) "地址、密钥和模型需属于同一地域。百炼识别使用 Paraformer 协议。" else "服务需支持 Audio API 语音识别接口。")
            ConfigDivider()
            SectionTitle("语音合成")
            ProviderField("服务商", ttsProvider, enabled,
                listOf(SpeechConfig.BAILIAN to "阿里云百炼", SpeechConfig.MINIMAX to "MiniMax 官方", SpeechConfig.OPENAI to "Audio API 兼容"), onTts)
            if (ttsProvider == SpeechConfig.MINIMAX) Hint("填写 MiniMax 官方 Key 和音色 ID；百炼 Key 不适用。此接法尚待真实账号验证。")
            FormField("合成接口地址", speech.ttsBaseUrl, enabled, placeholder = "填写合成服务的地址") { onChange(settings.copy(speech = speech.copy(ttsBaseUrl = it.trim()))) }
            FormField("合成 API Key", speech.ttsKey, enabled, secret = true,
                placeholder = when (ttsProvider) { SpeechConfig.BAILIAN -> "填写百炼 API Key"; SpeechConfig.MINIMAX -> "填写 MiniMax 官方 Key"; else -> "填写合成服务的 API Key" }) {
                onChange(settings.copy(speech = speech.copy(ttsKey = it.trim())))
            }
            if (speech.isBailian && ttsProvider == SpeechConfig.BAILIAN) TextButton(enabled = enabled, onClick = {
                onChange(settings.copy(speech = speech.copy(ttsBaseUrl = speech.sttBaseUrl, ttsKey = speech.sttKey)))
            }, contentPadding = PaddingValues(0.dp)) { Text("使用上面的百炼识别地址和密钥", fontSize = 12.sp) }
            FormField("合成模型", speech.ttsModel, enabled) { onChange(settings.copy(speech = speech.copy(ttsModel = it.trim()))) }
            Column(Modifier.bringIntoViewRequester(voiceTarget), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                FormField("音色 ID", speech.voice, enabled, placeholder = "粘贴服务商提供的音色 ID") {
                    onChange(settings.copy(speech = speech.copy(voice = it.trim()), voiceName = null))
                }
                FormField("音色名称（选填）", settings.voiceName.orEmpty(), enabled, placeholder = "给这个声音起个名字") {
                    onChange(settings.copy(voiceName = it.take(80)))
                }
                Hint("名称仅用于首页显示，不改变音色。音色 ID 须匹配所选服务和模型。")
            }
            if (settings.smartEndpoint) Column(Modifier.bringIntoViewRequester(judgeTarget), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                ConfigDivider(); SectionTitle("智能结束判断")
                Hint("此项额外调用文本模型，按该服务计费；关闭开关后不调用。")
                val judge = settings.endJudge ?: EndJudgeConfig()
                FormField("判断接口地址", judge.baseUrl, enabled) { onChange(settings.copy(endJudge = judge.copy(baseUrl = it.trim()))) }
                FormField("判断 API Key", judge.key, enabled, secret = true) { onChange(settings.copy(endJudge = judge.copy(key = it.trim()))) }
                FormField("判断模型", judge.model, enabled) { onChange(settings.copy(endJudge = judge.copy(model = it.trim()))) }
            }
            Button(onClick = onSave, enabled = enabled && !listing, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp).heightIn(min = 50.dp)) { Text("保存配置", fontSize = 17.sp) }
            Hint("密钥加密保存在本机。识别、合成及可选判断的费用由相应服务商结算。")
            TextButton(onClick = onAbout, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("关于耳畔") }
        }
    }
}

@Composable internal fun PageHeading(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { LineIcon(ErpanIcon.BACK) }
        Text(title, fontSize = 26.sp, fontFamily = FontFamily.Serif)
    }
}
@Composable private fun Hint(text: String) { Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = ErpanColors.Muted) }
@Composable private fun ConfigDivider() { HorizontalDivider(Modifier.padding(vertical = 9.dp), thickness = 0.6.dp, color = ErpanColors.Line) }

@Composable private fun ProviderField(label: String, selected: String, enabled: Boolean,
    choices: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 14.sp)
        Box {
            Surface(onClick = { open = true }, enabled = enabled, color = ErpanColors.Paper,
                shape = RoundedCornerShape(9.dp), border = BorderStroke(0.8.dp, ErpanColors.Line), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(choices.firstOrNull { it.first == selected }?.second ?: "请选择", Modifier.weight(1f), fontSize = 15.sp)
                    LineIcon(ErpanIcon.DOWN, ErpanColors.Muted, Modifier.size(17.dp))
                }
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onChange(id) }) }
            }
        }
    }
}
@Composable private fun FormField(label: String, value: String, enabled: Boolean, secret: Boolean = false,
    placeholder: String = "", onChange: (String) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 14.sp)
        OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = true, shape = RoundedCornerShape(9.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            placeholder = { Text(placeholder, fontSize = 13.sp, color = ErpanColors.Muted) },
            visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                else KeyboardOptions.Default,
            trailingIcon = if (secret) { { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示", fontSize = 12.sp) } } } else null,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = ErpanColors.Line, focusedBorderColor = ErpanColors.Rose,
                disabledBorderColor = ErpanColors.Line.copy(alpha = 0.65f)))
    }
}
