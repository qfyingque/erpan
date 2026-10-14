package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class CaptionAppearanceTest {
    @Test fun corruptOrExtremeSizeCannotCreateInvisibleOrUnboundedText() {
        assertEquals(22f, CaptionAppearance.normalized(Float.NaN, -1).sizeSp, 0f)
        assertEquals(14f, CaptionAppearance.normalized(-80f, -1).sizeSp, 0f)
        assertEquals(36f, CaptionAppearance.normalized(300f, -1).sizeSp, 0f)
    }
    @Test fun customColorsAreOpaqueAndOutlineContrastsForLightAndDarkInk() {
        val light = CaptionAppearance.normalized(22f, 0x00FFFFFF)
        val dark = CaptionAppearance.normalized(22f, 0x00101010)
        assertEquals(-1, light.color)
        assertEquals(0xFF202024.toInt(), light.outline)
        assertEquals(0xFFF8F8F8.toInt(), dark.outline)
    }
    @Test fun invalidCustomColorDoesNotSilentlyBecomeAnotherColor() {
        assertNull(CaptionAppearance.parseColor("#XYZ123"))
        assertNull(CaptionAppearance.parseColor("#00112233"))
        assertEquals(0xFFFFB7CF.toInt(), CaptionAppearance.parseColor(" #ffb7cf "))
    }
    @Test fun movieCaptionUsesPlayingRangeInsteadOfUnplayedGeneratedTail() {
        assertEquals("第二句。", movieCaption("第一句。第二句。未播放。", "等待", 4..7, true))
        assertEquals("等待", movieCaption("尚未播放的文字", "等待", null, true))
        assertEquals("你好😀", movieCaption("你好😀", "等待", 0..3, true))
        assertEquals("等待", movieCaption("短句", "等待", 8..10, true))
    }
}
