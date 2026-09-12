package com.huigu.phone10.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object ErpanColors {
    val Paper = Color(0xFFFFFCFA)
    val Ink = Color(0xFF232326)
    val Muted = Color(0xFF73737A)
    val Rose = Color(0xFFDD7890)
    val Line = Color(0xFFECCDD3)
    val Blush = Color(0xFFFBE8ED)
    val Coal = Color(0xFF242628)
    val CoalText = Color(0xFFF7F3F2)
    val CoalMuted = Color(0xFFBEBBC0)
}

@Composable internal fun ErpanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = ErpanColors.Rose,
        onPrimary = ErpanColors.Ink, background = ErpanColors.Paper, surface = ErpanColors.Paper,
        onBackground = ErpanColors.Ink, onSurface = ErpanColors.Ink, outline = ErpanColors.Line,
        secondary = ErpanColors.Rose, error = Color(0xFFAC344C)), content = content)
}

internal enum class ErpanIcon { BACK, NEXT, DOWN, PHONE, END, PLUS, ARROW }
@Composable internal fun LineIcon(kind: ErpanIcon, tint: Color = ErpanColors.Ink, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.7f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round)
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val p = Path()
            when (kind) {
                ErpanIcon.BACK -> { p.moveTo(15f, 4f); p.lineTo(7f, 12f); p.lineTo(15f, 20f) }
                ErpanIcon.NEXT -> { p.moveTo(9f, 5f); p.lineTo(16f, 12f); p.lineTo(9f, 19f) }
                ErpanIcon.DOWN -> { p.moveTo(5f, 9f); p.lineTo(12f, 16f); p.lineTo(19f, 9f) }
                ErpanIcon.PLUS -> { p.moveTo(12f, 4f); p.lineTo(12f, 20f); p.moveTo(4f, 12f); p.lineTo(20f, 12f) }
                ErpanIcon.ARROW -> { p.moveTo(5f, 19f); p.lineTo(19f, 5f); p.moveTo(7f, 5f); p.lineTo(19f, 5f); p.lineTo(19f, 17f) }
                ErpanIcon.PHONE -> {
                    p.moveTo(5f, 3f); p.cubicTo(1f, 4f, 3f, 13f, 9f, 19f)
                    p.cubicTo(14f, 23f, 20f, 23f, 21f, 19f)
                    p.lineTo(16f, 15f); p.lineTo(13f, 17f)
                    p.cubicTo(10f, 15f, 8f, 12f, 7f, 9f)
                    p.lineTo(9f, 7f); p.lineTo(7f, 3f); p.close()
                }
                ErpanIcon.END -> {
                    p.moveTo(2f, 16f); p.cubicTo(3f, 6f, 21f, 6f, 22f, 16f)
                    p.lineTo(17f, 17f); p.lineTo(16f, 13f)
                    p.cubicTo(13f, 12f, 11f, 12f, 8f, 13f)
                    p.lineTo(7f, 17f); p.close()
                }
            }
            if (kind == ErpanIcon.PHONE || kind == ErpanIcon.END) drawPath(p, tint)
            else drawPath(p, tint, style = stroke)
        }
    }
}

@Composable internal fun SectionTitle(text: String, dark: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(text, color = if (dark) ErpanColors.CoalText else ErpanColors.Ink,
            fontFamily = FontFamily.Serif, fontSize = 23.sp)
        Box(Modifier.width(25.dp).height(2.dp).background(ErpanColors.Rose))
    }
}

@Composable internal fun ErpanNavigationCard(title: String, subtitle: String? = null,
    value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, ErpanColors.Line), color = ErpanColors.Paper) {
        Row(Modifier.padding(horizontal = 17.dp, vertical = 17.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 19.sp, fontFamily = FontFamily.Serif)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = ErpanColors.Muted)
            }
            if (value != null) Text(value, modifier = Modifier.weight(1f),
                fontSize = 15.sp, color = ErpanColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LineIcon(ErpanIcon.NEXT, if (enabled) ErpanColors.Ink else ErpanColors.Muted, Modifier.size(18.dp))
        }
    }
}

@Composable internal fun ErpanToggle(title: String, checked: Boolean, enabled: Boolean = true,
    helper: String? = null, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), fontSize = 18.sp, color = ErpanColors.CoalText,
                fontFamily = FontFamily.Serif)
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = title },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ErpanColors.Rose,
                    uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF77787F),
                    uncheckedBorderColor = Color.Transparent, disabledCheckedTrackColor = ErpanColors.Rose.copy(alpha = 0.5f),
                    disabledUncheckedTrackColor = Color(0xFF515258)))
        }
        if (helper != null) Text(helper, color = ErpanColors.CoalMuted, fontSize = 12.sp, lineHeight = 19.sp)
    }
}
