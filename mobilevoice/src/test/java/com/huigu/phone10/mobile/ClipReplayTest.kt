package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ClipReplayTest {
    private fun clip(id: String) = VoiceClip(id,"words","voice",24000,240000,true,0,emptyList())
    @Test fun switchingWaitsForOldCleanupAndIgnoresItsLateProgress() = runBlocking {
        var state = ClipReplayState()
        var writers = 0
        var maxWriters = 0
        val entered = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val replay = ClipReplay(this, { id, _ -> clip(id) }, { c, _, _, progress ->
            writers++; maxWriters=maxOf(maxWriters,writers)
            try {
                if (c.id == "a") { entered.complete(Unit); awaitCancellation() }
                else { second.complete(Unit); awaitCancellation() }
            } finally {
                withContext(NonCancellable) { delay(20); progress(9000); events += "closed:${c.id}"; writers-- }
            }
        }, {}, {}, { state=it }, { events += "ended" })
        replay.play("a",false)
        entered.await()
        replay.play("b",true)
        second.await()
        assertEquals(1,maxWriters)
        assertEquals("b",state.id)
        assertEquals(0L,state.position)
        assertEquals(listOf("closed:a"),events)
        replay.stop(); coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }
    @Test fun pauseAndSeekUseTheSameRecordingWithoutSavingOrSynthesizing() = runBlocking {
        var state = ClipReplayState()
        val calls = mutableListOf<Triple<String,Boolean,Long>>()
        val pauses = mutableListOf<Boolean>()
        val replay = ClipReplay(this,{id,_->clip(id)}, { c,saved,start,progress ->
            calls += Triple(c.id,saved,start); progress(start+24000); awaitCancellation()
        }, {}, { pauses+=it }, { state=it }, {})
        replay.play("recent",false)
        yield(); replay.togglePause()
        assertTrue(state.paused)
        replay.seek(120000)
        delay(50)
        assertEquals(Triple("recent",false,120000L),calls.last())
        assertTrue(state.paused)
        assertEquals(144000L,state.position)
        replay.togglePause(); assertFalse(state.paused)
        assertEquals(listOf(true,false),pauses)
        replay.stop(); coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }
    @Test fun unavailableCacheIsAnErrorAndCannotReplayAnotherClip() = runBlocking {
        var state = ClipReplayState(); var renders=0; var ended=0
        val replay = ClipReplay(this,{_,_->error("file expired")}, {_,_,_,_->renders++}, {}, {}, {state=it}, {ended++})
        replay.play("expired",false)
        yield()
        assertFalse(state.active); assertNotNull(state.error)
        assertEquals(0,renders); assertEquals(1,ended)
    }
}
