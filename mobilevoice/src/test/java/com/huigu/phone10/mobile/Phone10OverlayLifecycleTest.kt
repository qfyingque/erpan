package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class Phone10OverlayLifecycleTest {
    @Test fun removedWindowDoesNotBlockCleanupOrKeepVisibleState() {
        val visible = mutableListOf<Boolean>()
        val lifecycle = Phone10OverlayLifecycle { visible += it }
        var removals = 0
        lifecycle.attached { removals++; throw IllegalArgumentException("view already removed") }
        lifecycle.hide()
        lifecycle.hide()
        assertFalse(lifecycle.visible)
        assertEquals(1, removals)
        assertEquals(listOf(true, false), visible)
    }

    @Test fun revokedPermissionWhileDraggingHidesTheWindowSafely() {
        val lifecycle = Phone10OverlayLifecycle {}
        lifecycle.attached { throw SecurityException("permission revoked") }
        lifecycle.move { throw SecurityException("permission revoked") }
        assertFalse(lifecycle.visible)
    }
}
