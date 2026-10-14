package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackCleanupTest {
    @Test fun cancelledPlaybackStillReleasesEveryOwnerBeforeVisualReset() = runBlocking {
        val visual = Executors.newSingleThreadExecutor { Thread(it, "visual-dispatcher") }.asCoroutineDispatcher()
        try {
            val entered = CountDownLatch(1)
            val events = mutableListOf<String>()
            val job = launch(Dispatchers.Default) {
                try {
                    try {
                        entered.countDown()
                        awaitCancellation()
                    } finally {
                        finishPlayback(visual,
                            finishFocus = { events += "focus" },
                            clearPlayer = { events += "reference" },
                            closePlayer = { events += "close" },
                            clearVisual = { events += "visual:${Thread.currentThread().name}" },
                            finishArchive = { delay(1); events += "archive" })
                    }
                } catch (_: CancellationException) { }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertEquals(listOf("focus", "reference", "close"), events.take(3))
            assertTrue(events.single { it.startsWith("visual:") }.startsWith("visual:visual-dispatcher"))
            assertEquals(5, events.size)
            assertEquals("archive",events.last())
        } finally { visual.close() }
    }

    @Test fun closeStillRunsWhenFocusReleaseThrows() = runBlocking {
        val visual = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val events = mutableListOf<String>()
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    finishPlayback(visual,
                        finishFocus = { events += "focus"; error("focus") },
                        clearPlayer = { events += "reference" },
                        closePlayer = { events += "close" },
                        clearVisual = { events += "visual" }, finishArchive = { events += "archive" })
                }
            }
            assertEquals(listOf("focus", "reference", "close", "visual", "archive"), events)
        } finally { visual.close() }
    }
}
