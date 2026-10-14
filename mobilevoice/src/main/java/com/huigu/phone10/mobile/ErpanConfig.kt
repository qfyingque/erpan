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
import kotlin.math.roundToInt
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun ErpanConfig(settings: MobileSettings, enabled: Boolean, listing: Boolean, notice: String,
    target: String, onChange: (MobileSettings) -> Unit, onStt: (String) -> Unit, onTts: (String) -> Unit,
    onChats: () -> Unit, onSave: () -> Unit, onBack: () -> Unit, onAbout: () -> Unit,
    checking: Boolean, onCheck: () -> Unit, profileName: String?, onSaveAs: () -> Unit) {
    var more by remember { mutableStateOf(false) }
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
            Text(if (settings.listenOnly) "只听回复：选择聊天，填好语音合成即可" else "填好服务信息，就可以开始通话", color = ErpanColors.Muted, fontSize = 13.sp)
            if (!enabled) Text(if (checking) "正在检查当前填写的配置…" else "通话或保存期间可查看配置，稍后再修改。", color = ErpanColors.Rose, fontSize = 13.sp)
            if (notice.isNotBlank()) Text(notice, color = ErpanColors.Rose, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            SectionTitle("聊天连接")
            ErpanNavigationCard("选中聊天窗口", subtitle = if (listing) "正在读取…" else settings.displayChat(),
                enabled = enabled && !listing, onClick = onChats)
            Hint("聊天模型、角色和历史在 Operit 中设置")
            ConfigDivider()
            SectionTitle("语音方案")
            Text(profileName ?: "新方案", fontSize = 17.sp)
            ConfigDivider()
            if (!settings.listenOnly) {
            SectionTitle("语音识别")
            ProviderField("服务商", speech.provider ?: SpeechConfig.OPENAI, enabled,
                listOf(SpeechConfig.BAILIAN to "阿里云百炼", SpeechConfig.VOLCENGINE to "火山引擎 / 豆包语音",
                    SpeechConfig.MINIMAX to "MiniMax 官方", SpeechConfig.MOSSLAND to "模思 Mossland",
                    SpeechConfig.OPENAI to "Audio API 兼容"), onStt)
            FormField("识别接口地址", speech.sttBaseUrl, enabled, placeholder = "填写识别服务的地址") { onChange(settings.copy(speech = speech.copy(sttBaseUrl = it.trim()))) }
            FormField("识别 API Key", speech.sttKey, enabled, secret = true,
                placeholder = if (speech.isBailian) "填写百炼 API Key" else "填写识别服务的 API Key") { onChange(settings.copy(speech = speech.copy(sttKey = it.trim()))) }
            if (speech.provider == SpeechConfig.VOLCENGINE) {
                ProviderField("识别类型", speech.effectiveSttVolcResource, enabled, listOf(
                    "volc.seedasr.sauc.duration" to "豆包识别 2.0（小时版）",
                    "volc.seedasr.sauc.concurrent" to "豆包识别 2.0（并发版）",
                    "volc.bigasr.sauc.duration" to "豆包识别 1.0（小时版）",
                    "volc.bigasr.sauc.concurrent" to "豆包识别 1.0（并发版）")) {
                    onChange(settings.copy(speech = speech.copy(sttVolcResource = it)))
                }
                FormField("识别旧版 App ID（选填）", speech.sttVolcAppId.orEmpty(), enabled,
                    placeholder = "新版 API Key 留空；旧版填 App ID") {
                    onChange(settings.copy(speech = speech.copy(sttVolcAppId = it.trim())))
                }
            } else if (speech.provider == SpeechConfig.MOSSLAND) {
                ProviderField("识别模型", speech.sttModel, enabled, listOf(
                    "moss-transcribe-1.0" to "普通转写", "moss-transcribe-diarize-pro" to "多说话人转写")) {
                    onChange(settings.copy(speech = speech.copy(sttModel = it)))
                }
            } else FormField("识别模型", speech.sttModel, enabled) { onChange(settings.copy(speech = speech.copy(sttModel = it.trim()))) }
            Hint(when (speech.provider) {
                SpeechConfig.BAILIAN -> "地址、密钥和模型需属于同一地域。百炼识别使用 Paraformer 协议。"
                SpeechConfig.VOLCENGINE -> "选择账号已开通的识别类型。新版填写 API Key；旧版在密钥框填写 Access Token，并填写识别 App ID。"
                SpeechConfig.MINIMAX -> "使用 MiniMax 官方密钥，识别模型保持 asr-1.0。"
                SpeechConfig.MOSSLAND -> "使用 Moss API 密钥，日常通话可先选普通转写。"
                else -> "服务需支持 Audio API 语音识别接口。"
            })
            ConfigDivider()
            }
            SectionTitle("语音合成")
            ProviderField("服务商", ttsProvider, enabled,
                listOf(SpeechConfig.BAILIAN to "阿里云百炼", SpeechConfig.VOLCENGINE to "火山引擎 / 豆包语音", SpeechConfig.MINIMAX to "MiniMax 官方", SpeechConfig.ELEVENLABS to "ElevenLabs", SpeechConfig.MOSSLAND to "模思 Mossland", SpeechConfig.QWEN_LOCAL to "本机语音", SpeechConfig.OPENAI to "Audio API 兼容"), onTts)
            if (ttsProvider == SpeechConfig.ELEVENLABS) Hint("填写 ElevenLabs API Key 和 Voice ID，可使用平台官方音色。消耗 ElevenLabs 额度；连接测试也会合成一句短语。")
            if (ttsProvider == SpeechConfig.MINIMAX) Hint("填写 MiniMax 官方 Key 和音色 ID；百炼 Key 不适用。此接法尚待真实账号验证。")
            if (ttsProvider == SpeechConfig.MOSSLAND) Hint("在 Moss API 控制台创建 Key，从 Mossland 音色库复制音色 ID。支持声音边生成边播放；克隆音色请先在模思平台创建。")
            if (ttsProvider == SpeechConfig.QWEN_LOCAL) Hint("使用你电脑上的 Qwen 声音服务。先在电脑开启并完成预热；手机与电脑需保持 Tailscale 连接。原声留在电脑上，服务关闭时会提示，不会自动切换到付费云端。")
            FormField("合成接口地址", speech.ttsBaseUrl, enabled, placeholder = "填写合成服务的地址") { onChange(settings.copy(speech = speech.copy(ttsBaseUrl = it.trim()))) }
            FormField(if (ttsProvider == SpeechConfig.QWEN_LOCAL) "独立 TTS 令牌" else "合成 API Key", speech.ttsKey, enabled, secret = true,
                placeholder = when (ttsProvider) { SpeechConfig.QWEN_LOCAL -> "填写电脑提供的 TTS 令牌"; SpeechConfig.BAILIAN -> "填写百炼 API Key"; SpeechConfig.MINIMAX -> "填写 MiniMax 官方 Key"; else -> "填写合成服务的 API Key" }) {
                onChange(settings.copy(speech = speech.copy(ttsKey = it.trim())))
            }
            if (!settings.listenOnly && speech.isBailian && ttsProvider == SpeechConfig.BAILIAN) TextButton(enabled = enabled, onClick = {
                onChange(settings.copy(speech = speech.copy(ttsBaseUrl = speech.sttBaseUrl, ttsKey = speech.sttKey)))
            }, contentPadding = PaddingValues(0.dp)) { Text("使用上面的百炼识别地址和密钥", fontSize = 12.sp) }
            else if (!settings.listenOnly && speech.provider == ttsProvider && ttsProvider in setOf(SpeechConfig.VOLCENGINE, SpeechConfig.MINIMAX, SpeechConfig.MOSSLAND))
                TextButton(enabled = enabled, onClick = {
                    onChange(settings.copy(speech = speech.copy(ttsKey = speech.sttKey,
                        volcAppId = if (ttsProvider == SpeechConfig.VOLCENGINE) speech.sttVolcAppId else speech.volcAppId)))
                }, contentPadding = PaddingValues(0.dp)) { Text("使用上面的识别密钥", fontSize = 12.sp) }
            if (ttsProvider == SpeechConfig.VOLCENGINE) {
                ProviderField("模型类型", speech.effectiveVolcResource, enabled, listOf(
                    "seed-icl-2.0" to "声音复刻 2.0", "seed-tts-2.0" to "语音合成 2.0",
                    "seed-icl-1.0" to "声音复刻 1.0（字符版）", "seed-tts-1.0" to "语音合成 1.0（字符版）",
                    "seed-icl-1.0-concurr" to "声音复刻 1.0（并发版）", "seed-tts-1.0-concurr" to "语音合成 1.0（并发版）")) {
                    onChange(settings.copy(speech = speech.copy(volcResource = it)))
                }
                if (speech.effectiveVolcResource == "seed-icl-2.0") ProviderField("复刻版本", speech.ttsModel, enabled,
                    listOf("seed-tts-2.0-expressive" to "表现力增强版（支持语气描述）",
                        "seed-tts-2.0-standard" to "标准版（更低延迟）")) {
                    onChange(settings.copy(speech = speech.copy(ttsModel = it)))
                }
                FormField("旧版 App ID（选填）", speech.volcAppId.orEmpty(), enabled,
                    placeholder = "新版 API Key 留空；旧版填 App ID") {
                    onChange(settings.copy(speech = speech.copy(volcAppId = it.trim())))
                }
                Hint("新版控制台填写 API Key；旧版在上方密钥框填写 Access Token，并填写 App ID。音色需属于所选模型类型。")
                if (speech.supportsVoicePrompt) FormField("语气描述（选填）", speech.voicePrompt.orEmpty(), enabled,
                    placeholder = "例如：用自然、温柔的语气，稍微说慢一点") {
                    onChange(settings.copy(speech = speech.copy(voicePrompt = it.take(2000))))
                }
                else Hint("当前版本不使用语气描述。声音复刻 2.0 可选择表现力增强版来使用。")
            } else if (ttsProvider == SpeechConfig.QWEN_LOCAL) {
                FormField("合成模型", speech.ttsModel, enabled) { onChange(settings.copy(speech = speech.copy(ttsModel = it.trim()))) }
                Hint("填写电脑语音服务提供的模型和音色编号。每个声音可另存为方案，之后从首页切换。")
                ProviderField("本机语音生成方式", speech.effectiveQwenSplitGranularity, enabled, listOf(
                    "client_segments" to "首句先读 · 后续合段", "sentence" to "服务逐句 · 陆续播放", "none" to "整段 · 写完再读")) {
                    onChange(settings.copy(speech = speech.copy(qwenSplitGranularity = it)))
                }
                Hint(if (speech.clientSegmentedTts) "第一个逗号或句号等断句标点到达即提交首段，后续短句合段生成；收到声音即播放。需电脑服务支持连续整段请求；每段独立合成，声音与衔接需试听确认。"
                    else if (speech.wholeReplyTts) "等待整条回复写完，再按电脑服务支持的方式合成播放。请使用电脑端确认支持整段的声音；长回复上限由服务决定，失败时保留正文，不自动改回逐句。"
                    else "完整句子到达后交给电脑合成。开始播放的时间取决于模型和服务；请选择电脑端已验证的模式，每个声音可另存为独立方案。")
            }
            else if (ttsProvider == SpeechConfig.ELEVENLABS) {
                ProviderField("合成模型", speech.ttsModel, enabled, listOf(
                    "eleven_flash_v2_5" to "Flash v2.5 · 边写边说", "eleven_v3" to "v3 · 分段朗读",
                    "eleven_v4_turbo" to "v4 Turbo · 边写边说", "eleven_v4" to "v4 · 分段朗读")) {
                    onChange(settings.copy(speech = speech.copy(ttsModel = it)))
                }
                Hint(if (speech.elevenDialogueTts) "陆续发送回复文字，首个断句标点触发生成；工具等待时保持连接，收到声音就播放。实际出声时间取决于网络与平台。"
                    else if (speech.streamingTts) "陆续发送回复文字，收到声音就播放；实际出声时间取决于网络与平台。"
                    else "把回复分段交给所选模型合成，音频边生成边播放。各段独立生成，句间听感可能不同。")
            }
            else FormField("合成模型", speech.ttsModel, enabled) { onChange(settings.copy(speech = speech.copy(ttsModel = it.trim()))) }
            Column(Modifier.bringIntoViewRequester(voiceTarget), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                FormField("音色 ID", speech.voice, enabled, placeholder = "粘贴服务商提供的音色 ID") {
                    onChange(settings.copy(speech = speech.copy(voice = it.trim()), voiceName = null))
                }
                FormField("音色名称（选填）", settings.voiceName.orEmpty(), enabled, placeholder = "给这个声音起个名字") {
                    onChange(settings.copy(voiceName = it.take(80)))
                }
                Hint("名称仅用于首页显示，不改变音色。音色 ID 须匹配所选服务和模型。")
                if (ttsProvider == SpeechConfig.MOSSLAND) Column {
                    ProviderField("朗读方式", speech.effectiveMossTextMode, enabled, listOf(
                        "fast" to "快速响应", "coherent" to "语气连贯", "whole" to "整条合成")) {
                        onChange(settings.copy(speech = speech.copy(mossTextMode = it)))
                    }
                    Hint(when (speech.effectiveMossTextMode) {
                        "coherent" -> "先读完整短段，后续相邻句子适当合并，减少独立合成次数；会多等一些文字，段间仍可能有音色变化。"
                        "whole" -> "等待整条回复写完后发起一次合成，音频仍边生成边播放。等待更久，长回复受服务长度上限限制；不保证完全消除声音变化。"
                        else -> "收到可朗读的短句就开始合成，响应较快；各段独立生成，语气可能变化。"
                    })
                    Spacer(Modifier.height(12.dp))
                    val speed = speech.effectiveMossSpeed
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("语速  ${String.format(Locale.ROOT, "%.2f", speed)} 倍", Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(enabled = enabled, onClick = {
                            onChange(settings.copy(speech = speech.copy(mossSpeed = null)))
                        }) { Text("恢复默认", fontSize = 12.sp) }
                    }
                    Slider(value = speed.takeIf { it.isFinite() }?.coerceIn(0.25, 4.0)?.toFloat() ?: 1f,
                        onValueChange = {
                            onChange(settings.copy(speech = speech.copy(mossSpeed = (it * 20).roundToInt() / 20.0)))
                        }, enabled = enabled, valueRange = 0.25f..4f, steps = 74,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Moss 语速" })
                    Hint("1 倍保持音色原本的节奏；想快一点可先试 1.1～1.2 倍。随当前语音方案保存，下次通话生效。")
                }
            }
            if (!settings.listenOnly && settings.smartEndpoint && !settings.confirmBeforeSend) Column(Modifier.bringIntoViewRequester(judgeTarget), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                ConfigDivider(); SectionTitle("智能结束判断")
                Hint("此项额外调用文本模型，按该服务计费；关闭开关后不调用。")
                val judge = settings.endJudge ?: EndJudgeConfig()
                FormField("判断接口地址", judge.baseUrl, enabled) { onChange(settings.copy(endJudge = judge.copy(baseUrl = it.trim()))) }
                FormField("判断 API Key", judge.key, enabled, secret = true) { onChange(settings.copy(endJudge = judge.copy(key = it.trim()))) }
                FormField("判断模型", judge.model, enabled) { onChange(settings.copy(endJudge = judge.copy(model = it.trim()))) }
            }
            Button(onClick = onSave, enabled = enabled && !listing, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp).heightIn(min = 50.dp)) { Text("保存并使用", fontSize = 17.sp) }
            Box {
                TextButton(enabled = enabled && !listing, onClick = { more = true }) { Text("更多") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("另存为新方案") }, enabled = enabled && !listing,
                        onClick = { more = false; onSaveAs() })
                }
            }
            OutlinedButton(onClick = onCheck, enabled = enabled && !listing, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (checking) "正在检查…" else "检查连接", fontSize = 16.sp) }
            Hint("按当前填写内容检查，无需先保存。会发送 1 秒静音和一句固定文字测试语音接口，不开麦、不播放，也不发送聊天消息。语音及已开启的判断测试可能产生少量服务用量。")
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
