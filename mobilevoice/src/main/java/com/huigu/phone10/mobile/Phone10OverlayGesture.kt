package com.huigu.phone10.mobile

import kotlin.math.abs

/** A drag is latched until release, even when the finger returns to its origin. */
class Phone10OverlayGesture(private val slop: Float) {
    private var x = 0f
    private var y = 0f
    private var active = false
    private var dragged = false

    fun down(rawX: Float, rawY: Float) {
        x = rawX; y = rawY; active = true; dragged = false
    }

    fun move(rawX: Float, rawY: Float): Boolean {
        if (active && (abs(rawX - x) > slop || abs(rawY - y) > slop)) dragged = true
        return active && dragged
    }

    fun up(rawX: Float, rawY: Float): Boolean {
        move(rawX, rawY)
        val click = active && !dragged
        active = false
        return click
    }

    fun cancel() { active = false }
}
