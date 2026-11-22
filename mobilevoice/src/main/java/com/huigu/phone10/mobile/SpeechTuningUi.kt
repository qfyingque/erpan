package com.huigu.phone10.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable internal fun SpeechTuningEditor(config: SpeechConfig, profileId: String?, enabled: Boolean,
    onReadSettings: (SpeechConfig, String?, ElevenVoiceSettings) -> Boolean, onChange: (SpeechConfig) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var readJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val currentConfig by rememberUpdatedState(config)
    val currentReadSettings by rememberUpdatedState(onReadSettings)
    val mini = config.effectiveTtsProvider == SpeechConfig.MINIMAX
    val miniSettings = config.miniMaxVoiceSettings ?: MiniMaxVoiceSettings()
    val elevenSettings = config.elevenVoiceSettings
    val editable = enabled && !loading
    LaunchedEffect(enabled) { if (!enabled) { readJob?.cancel(); loading = false } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("声音调节", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                val summary = if (mini) {
                    val emotion = miniSettings.emotion?.let { id -> miniMaxEmotions(config.ttsModel).firstOrNull { it.first == id }?.second ?: "需重选情绪" } ?: "自动情绪"
                    "${String.format(Locale.ROOT, "%.2f", miniSettings.speed ?: 1.0)} 倍 · $emotion"
                } else if (elevenSettings == null) "沿用官网设置" else "使用耳畔中的设置"
                Text(summary, color = ErpanColors.Muted, fontSize = 13.sp)
            }
            Text(if (expanded) "收起" else "调整", fontSize = 14.sp)
        }
        if (expanded) {
            if (mini) {
                TuningSlider("语速", miniSettings.speed ?: 1.0, 0.5f..2f, 29, editable,
                    "${String.format(Locale.ROOT, "%.2f", miniSettings.speed ?: 1.0)} 倍") {
                    onChange(config.copy(miniMaxVoiceSettings = miniSettings.copy(speed = (it * 20).roundToInt() / 20.0)))
                }
                ProviderField("情绪", miniSettings.emotion ?: "auto", editable,
                    listOf("auto" to "自动 · 跟随文字") + miniMaxEmotions(config.ttsModel)) {
                    onChange(config.copy(miniMaxVoiceSettings = miniSettings.copy(emotion = it.takeUnless { value -> value == "auto" })))
                }
                TuningHint("日常用自动；手动选择后，会以这种情绪朗读。")
                TextButton(enabled = editable, onClick = { onChange(config.copy(miniMaxVoiceSettings = null)) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复默认") }
            } else {
                ProviderField("设置来源", if (elevenSettings == null) "cloud" else "local", editable,
                    listOf("cloud" to "沿用官网设置", "local" to "在耳畔调整")) {
                    notice = ""
                    onChange(config.copy(elevenVoiceSettings = if (it == "cloud") null else elevenSettings ?:
                        ElevenVoiceSettings(0.5, if (config.elevenSimilaritySupported) 0.75 else null)))
                }
                if (elevenSettings == null) {
                    TuningHint("沿用该音色在 ELE 保存的默认参数；官网临时试听的调整需先保存。")
                } else {
                    if (config.ttsModel == "eleven_v3") ProviderField("稳定性", elevenSettings.stability?.toString().orEmpty(), editable,
                        listOf("0.0" to "灵动 · 表达更多", "0.5" to "自然 · 均衡", "1.0" to "稳健 · 起伏更少")) {
                        onChange(config.copy(elevenVoiceSettings = elevenSettings.copy(stability = it.toDouble())))
                    } else TuningSlider("稳定性", elevenSettings.stability ?: 0.5, 0f..1f, 99, editable,
                        elevenSettings.stability?.let { "${(it * 100).roundToInt()}%" } ?: "官网默认") {
                        onChange(config.copy(elevenVoiceSettings = elevenSettings.copy(stability = (it * 100).roundToInt() / 100.0)))
                    }
                    TuningHint("低一些，表达起伏更多；高一些，更稳定，也可能更平。")
                    if (config.elevenSimilaritySupported) {
                        TuningSlider("相似度", elevenSettings.similarityBoost ?: 0.75, 0f..1f, 99, editable,
                            elevenSettings.similarityBoost?.let { "${(it * 100).roundToInt()}%" } ?: "官网默认") {
                            onChange(config.copy(elevenVoiceSettings = elevenSettings.copy(similarityBoost = (it * 100).roundToInt() / 100.0)))
                        }
                        TuningHint("越高越贴近原音色；原素材有杂音时，不宜一味调高。")
                    } else TuningHint(if (config.ttsModel == "eleven_v3") "v3 支持三档稳定性，相似度由模型处理。"
                        else "当前实时接口可调稳定性；相似度可在 v4 分段模式中调整。")
                }
                OutlinedButton(enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val requested = currentConfig
                    val requestedProfile = profileId
                    notice = ""; loading = true
                    readJob = scope.launch {
                        try {
                            val fetched = CloudSpeech(requested.copy(elevenVoiceSettings = null)).readElevenVoiceSettings()
                            if (currentReadSettings(requested, requestedProfile, fetched)) {
                                notice = "已读入官网参数，可以继续调整。保存后生效。"
                            } else notice = "配置已变更，请重新读取官网参数。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            notice = if (failure is SpeechApiException || failure is IllegalArgumentException)
                                failure.message.orEmpty() else "读取失败，当前设置已保留。"
                        } finally { loading = false }
                    }
                }) { Text(if (loading) "正在读取…" else "读取官网参数") }
                if (notice.isNotBlank()) TuningHint(notice)
            }
            config.voiceTuningErrors().forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            TuningHint("点下方「保存并使用」生效；每个声音方案可以单独保存。")
        }
    }
}

@Composable private fun TuningSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>,
    steps: Int, enabled: Boolean, display: String, onChange: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 15.sp)
            Text(display, color = ErpanColors.Rose, fontSize = 15.sp)
        }
        Slider(value = value.takeIf { it.isFinite() }?.toFloat()?.coerceIn(range.start, range.endInclusive) ?: range.start,
            onValueChange = { onChange(it.toDouble()) }, enabled = enabled, valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label })
    }
}

@Composable private fun TuningHint(text: String) {
    Text(text, color = ErpanColors.Muted, fontSize = 13.sp, lineHeight = 20.sp)
}
