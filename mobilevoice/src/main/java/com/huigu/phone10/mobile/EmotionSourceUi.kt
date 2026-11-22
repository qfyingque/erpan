package com.huigu.phone10.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun EmotionSourceEditor(config: EmotionSourceConfig?, enabled: Boolean,
    onChange: (EmotionSourceConfig) -> Unit) {
    val source = config ?: EmotionSourceConfig()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("情绪补充（选用）")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("允许补充情绪识别", Modifier.weight(1f), fontSize = 15.sp)
            Switch(checked = source.enabled, enabled = enabled,
                onCheckedChange = { onChange(source.copy(enabled = it)) })
        }
        Text("当前识别未返回情绪时，可把同一段录音额外发送给 Qwen ASR，只取情绪，保留原来的转写。每段最多增加一次云端调用，按百炼 API 计费，也可能多等几秒。默认关闭。",
            color = ErpanColors.Muted, fontSize = 12.sp, lineHeight = 19.sp)
        if (source.enabled) {
            FormField("情绪补充地址", source.baseUrl, enabled) { onChange(source.copy(baseUrl = it.trim())) }
            FormField("情绪补充 API Key", source.key, enabled, secret = true,
                placeholder = "填写使用者自己的普通百炼 Key") { onChange(source.copy(key = it.trim())) }
            FormField("情绪补充模型", source.model, enabled) { onChange(source.copy(model = it.trim())) }
            Text("地址与 Key 须属于同一地域；Token Plan 套餐 Key 不适用。还需在首页通话设置打开「传递情绪线索」。已有原生情绪时不会重复调用；失败时照常发送原转写。",
                color = ErpanColors.Muted, fontSize = 12.sp, lineHeight = 19.sp)
            val errors = source.validationErrors()
            if (errors.isNotEmpty()) Text(errors.joinToString("\n") + "\n未配好时只发送原文。",
                color = ErpanColors.Rose, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}
