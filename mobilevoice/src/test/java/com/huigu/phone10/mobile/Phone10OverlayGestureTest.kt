package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class Phone10OverlayGestureTest {
    @Test fun smallFingerJitterStillClicks() {
        val gesture = Phone10OverlayGesture(8f)
        gesture.down(100f, 100f)
        assertFalse(gesture.move(103f, 104f))
        assertTrue(gesture.up(103f, 104f))
    }

    @Test fun dragReturningToOriginNeverClicks() {
        val gesture = Phone10OverlayGesture(8f)
        gesture.down(100f, 100f)
        assertTrue(gesture.move(125f, 100f))
        assertTrue(gesture.move(100f, 100f))
        assertFalse(gesture.up(100f, 100f))
    }

    @Test fun releaseFarAwayWithoutMoveEventDoesNotToggle() {
        val gesture = Phone10OverlayGesture(8f)
        gesture.down(100f, 100f)
        assertFalse(gesture.up(150f, 100f))
    }

    @Test fun cancellationCannotTurnIntoClick() {
        val gesture = Phone10OverlayGesture(8f)
        gesture.down(100f, 100f)
        gesture.cancel()
        assertFalse(gesture.up(100f, 100f))
        gesture.down(100f, 100f)
        assertTrue(gesture.up(100f, 100f))
    }
}
