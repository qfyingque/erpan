package com.huigu.phone10.mobile

internal data class CaptionAppearance(val sizeSp: Float = 22f, val color: Int = -1, val transparent: Boolean = false) {
    val outline: Int get() {
        val brightness = ((color shr 16 and 255) * 299 + (color shr 8 and 255) * 587 + (color and 255) * 114) / 1000
        return if (brightness >= 145) 0xFF202024.toInt() else 0xFFF8F8F8.toInt()
    }
    companion object {
        fun normalized(size: Float, color: Int, transparent: Boolean = false) = CaptionAppearance(
            if (size.isFinite()) size.coerceIn(14f, 36f) else 22f, color or 0xFF000000.toInt(), transparent)
        fun parseColor(value: String): Int? {
            val hex = value.trim().removePrefix("#")
            return if (hex.matches(Regex("[0-9a-fA-F]{6}"))) hex.toInt(16) or 0xFF000000.toInt() else null
        }
    }
}

// Use real segment boundaries; never pretend to have word-level acoustic timestamps.
internal fun movieCaption(reply: String, status: String, range: IntRange?, tracking: Boolean): String {
    if (range != null && !range.isEmpty() && range.first >= 0 && range.last < reply.length) {
        var start = range.first
        var end = range.last + 1
        if (start > 0 && reply[start].isLowSurrogate() && reply[start - 1].isHighSurrogate()) start--
        if (end < reply.length && reply[end - 1].isHighSurrogate() && reply[end].isLowSurrogate()) end++
        return reply.substring(start, end).trim().ifBlank { status }
    }
    return if (tracking) status else reply.ifBlank { status }
}
