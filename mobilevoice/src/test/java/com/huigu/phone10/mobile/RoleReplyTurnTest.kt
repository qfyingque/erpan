package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class RoleReplyTurnTest {
    @Test fun historyFailureStopsSpeechButLetsTheAcceptedSendFinish() = runBlocking {
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val readFailed=CompletableDeferred<Unit>();var cancelled=false;var sends=0
        val job=launch {
            runCatching {
                playRoleReplyTurn("hi",read={after->
                    if(after==null)ReplySnapshot(0,emptyList(),false,roleMetadata=true)
                    else {started.await();readFailed.complete(Unit);error("history offline")}
                },send={sends++;started.complete(Unit);try{finish.await()}finally{cancelled=!finish.isCompleted}},
                    render={_,_->},stop={},report={},intervalMillis=1)
            }
        }
        readFailed.await();delay(20)
        val stillRunning=job.isActive
        finish.complete(Unit);job.join()
        assertTrue("read failure must not cancel the original task",stillRunning)
        assertFalse(cancelled);assertEquals(1,sends)
    }
    @Test fun sendsOnceStreamsBeforeSendCompletesAndDrainsBothRoles() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(8)
        val finishSend=CompletableDeferred<Unit>();val firstSpoken=CompletableDeferred<Unit>()
        val heard=mutableListOf<String>();var sends=0;var cursor=10L
        val job=launch {
            playRoleReplyTurn("hi",read={after->
                if(after==null)ReplySnapshot(10,emptyList(),false,roleMetadata=true)
                else snapshots.tryReceive().getOrNull()?.also{cursor=it.cursor}
                    ?: ReplySnapshot(cursor,emptyList(),!finishSend.isCompleted,roleMetadata=true)
            },send={sends++;finishSend.await()},render={role,chunks->
                heard+=role
                for(c in chunks){heard+=c;firstSpoken.complete(Unit)}
            },stop={},report={},intervalMillis=1)
        }
        snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"一。",false,"Sol")),true))
        withTimeout(2000){firstSpoken.await()}
        assertFalse(finishSend.isCompleted);assertEquals(1,sends)
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"一。二。",true,"Sol")),true))
        snapshots.send(ReplySnapshot(12,listOf(ObservedReply(12,"三。",true,"Astra")),false))
        finishSend.complete(Unit)
        withTimeout(2000){job.join()}
        assertEquals(listOf("Sol","一。","二。","Astra","三。"),heard)
    }
    @Test fun stopCancelsTheOnlySenderAndQueuedPlayback() = runBlocking {
        val sending=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        var sends=0;var stops=0
        val job=launch{
            playRoleReplyTurn("hi",read={ReplySnapshot(0,emptyList(),false,roleMetadata=true)},
                send={sends++;sending.complete(Unit);try{awaitCancellation()}finally{cancelled.complete(Unit)}},
                render={_,_->fail("no replies")},stop={stops++},report={},intervalMillis=1)
        }
        sending.await();job.cancelAndJoin()
        assertTrue(cancelled.isCompleted);assertEquals(1,sends);assertTrue(stops>0)
    }
    @Test fun unsupportedPluginAndBusyChatNeverSend() = runBlocking {
        for(baseline in listOf(ReplySnapshot(0,emptyList(),false),ReplySnapshot(0,emptyList(),true,roleMetadata=true))) {
            var sends=0
            try{
                playRoleReplyTurn("hi",{baseline},{sends++},{_,_->},{},{},1)
                fail("preflight must reject")
            }catch(_:IllegalStateException){}
            assertEquals(0,sends)
        }
    }
    @Test fun conversationChoosesVoiceOnceForEachObservedMessage() = runBlocking {
        val heard=mutableListOf<String>();val selected=mutableListOf<String>()
        val flow=VoiceConversation(this,{"hi"},{_,_->error("direct stream must not be spoken")},{error("default renderer")},{},{},
            voiceForRole={role->selected+=role;ReplyVoice({heard+="$role:$it"},null,false,false,false,false)},
            roleReply={_,render->
                for(role in listOf("Sol","Astra","Sol")) {
                    val chunks=Channel<String>(2);chunks.send("你好。");chunks.close();render(role,chunks)
                }
            })
        withTimeout(2000){flow.submit(byteArrayOf(1)).join()}
        assertEquals(listOf("Sol","Astra","Sol"),selected)
        assertEquals(listOf("Sol:你好。","Astra:你好。","Sol:你好。"),heard)
    }
    @Test fun unavailableMemberDoesNotCancelRemainingChatOrUseDefaultVoice() = runBlocking {
        val heard=mutableListOf<String>();var submitted=0
        val flow=VoiceConversation(this,{"hi"},{_,_->error("wrong send")},{error("no fallback")},{},{},
            voiceForRole={role->require(role!="Sol");ReplyVoice({heard+=it},null,false,false,false,false)},
            roleReply={_,render->
                submitted++
                for(role in listOf("Sol","Astra")) {
                    val chunks=Channel<String>(2);chunks.send("$role 你好。");chunks.close();render(role,chunks)
                }
            })
        withTimeout(2000){flow.submit(byteArrayOf(1)).join()}
        assertEquals(1,submitted);assertEquals(listOf("Astra 你好。"),heard)
    }
}
