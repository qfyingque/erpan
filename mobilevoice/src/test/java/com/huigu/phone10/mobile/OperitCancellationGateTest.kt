package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OperitCancellationGateTest {
    @Test fun timeoutKeepsBarrierAndNextAttemptQueriesOnlyTheSameOwner() = runBlocking {
        val calls=mutableListOf<Pair<String,Boolean>>()
        var released=false
        val gate=OperitCancellationGate { id, readOnly ->
            calls += id to readOnly
            if(!released) throw OperitBridgeFailure("OPERIT_CANCEL_PENDING")
        }
        assertTrue(runCatching { gate.cancelAndAwait("chat","old") }.isFailure)
        assertTrue(runCatching { gate.awaitReady("chat") }.isFailure)
        gate.awaitReady("another-chat")
        released=true
        gate.awaitReady("chat");gate.awaitReady("chat")
        assertEquals(listOf("old" to false,"old" to true,"old" to true),calls)
    }
    @Test fun aNewerPendingOwnerCannotBeClearedByAnOlderReceipt() = runBlocking {
        val firstStarted=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val checked=mutableListOf<String>()
        val gate=OperitCancellationGate { id,readOnly ->
            if(id=="first") { firstStarted.complete(Unit);finish.await() }
            else if(!readOnly) throw OperitBridgeFailure("OPERIT_CANCEL_PENDING")
            else checked+=id
        }
        val first=launch { gate.cancelAndAwait("chat","first") };firstStarted.await()
        runCatching { gate.cancelAndAwait("chat","second") }
        finish.complete(Unit);first.join()
        gate.awaitReady("chat")
        assertEquals(listOf("second"),checked)
    }
    @Test fun cancellationOfReadbackDoesNotForgetPendingOwner() = runBlocking {
        var query=0
        val gate=OperitCancellationGate { _,readOnly ->
            if(!readOnly) throw OperitBridgeFailure("OPERIT_CANCEL_PENDING")
            query++; if(query==1) throw CancellationException("stop")
        }
        runCatching { gate.cancelAndAwait("chat","old") }
        runCatching { gate.awaitReady("chat") }
        gate.awaitReady("chat")
        assertEquals(2,query)
    }
    @Test fun knownFailureIsVisibleWithoutLeakingProviderDetails() = runBlocking {
        val events=mutableListOf<String>();val reports=mutableListOf<String>()
        val flow=VoiceConversation(this,{"hello"},{_,_->throw OperitBridgeFailure("OPERIT_BUSY")},
            {},{},reports::add,diagnose=events::add)
        flow.submit(byteArrayOf(1)).join()
        assertTrue(events.contains("operit_failed_OPERIT_BUSY"))
        assertTrue(reports.last().contains("正忙"))
    }
}
