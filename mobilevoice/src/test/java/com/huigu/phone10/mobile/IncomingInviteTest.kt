package com.huigu.phone10.mobile
import org.junit.Assert.*
import org.junit.Test

class IncomingInviteTest {
    private val invite=IncomingInvite("computer-00000000-0000-4000-8000-000000000001","chat","Sol10","想跟你说一件事",2000,"RINGING")
    @Test fun validInviteDoesNotStartTheMicrophone(){val r=IncomingBook.offer(emptyList(),invite,"chat",false,1000);assertEquals("RINGING",r.code);assertEquals("RINGING",r.state.single().status)}
    @Test fun duplicateCannotRingAgain(){val r=IncomingBook.offer(listOf(invite),invite,"chat",false,1000);assertEquals("DUPLICATE",r.code);assertEquals(1,r.state.size)}
    @Test fun differentTargetOrBusyCallIsRejected(){assertEquals("TARGET_CHANGED",IncomingBook.offer(emptyList(),invite,"different",false,1000).code);assertEquals("BUSY",IncomingBook.offer(emptyList(),invite,"chat",true,1000).code)}
    @Test fun expiredOrOverlongInvitesCannotRing(){assertEquals("INVALID",IncomingBook.offer(emptyList(),invite,"chat",false,2000).code);assertEquals("INVALID",IncomingBook.offer(emptyList(),invite.copy(expiresAt=100000),"chat",false,1000).code)}
    @Test fun acceptAndRejectAreFinal(){for(action in listOf("ACCEPTED","REJECTED")){val done=IncomingBook.finish(listOf(invite),invite.id,action,1500);assertEquals(action,done.code);assertEquals("ALREADY_FINISHED",IncomingBook.finish(done.state,invite.id,"ACCEPTED",1600).code)}}
    @Test fun expiryWinsOverAnOldAcceptButton(){assertEquals("EXPIRED",IncomingBook.finish(listOf(invite),invite.id,"ACCEPTED",2500).code)}
    @Test fun sameIdWithChangedContentIsRejected(){assertEquals("CONFLICT",IncomingBook.offer(listOf(invite),invite.copy(text="different"),"chat",false,1000).code)}
    @Test fun onlyOnePendingInvitation(){assertEquals("BUSY",IncomingBook.offer(listOf(invite),invite.copy(id="computer-11111111-1111-4111-8111-111111111111"),"chat",false,1000).code)}
    @Test fun serviceCanConsumeAnAcceptedInviteOnlyOnce(){val r=IncomingBook.finish(listOf(invite),invite.id,"ACCEPTED",1100);val started=IncomingBook.finish(r.state,invite.id,"STARTED",1200);assertEquals("STARTED",started.code);assertEquals("ALREADY_FINISHED",IncomingBook.finish(started.state,invite.id,"STARTED",1300).code)}
    @Test fun staleAcceptedInviteCannotStartAService(){assertEquals("EXPIRED",IncomingBook.finish(listOf(invite.copy(status="ACCEPTED")),invite.id,"STARTED",2200).code)}
}
