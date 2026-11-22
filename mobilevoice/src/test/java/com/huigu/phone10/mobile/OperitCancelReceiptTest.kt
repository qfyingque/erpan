package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OperitCancelReceiptTest {
    private fun complete(pending: OperitPending, released: Boolean?) = JsonObject().apply {
        addProperty("version",1); addProperty("id",pending.id); addProperty("nonce",pending.nonce)
        addProperty("seq",0); addProperty("type","complete")
        released?.let { addProperty("released",it) }
    }
    @Test fun oldAcknowledgementDoesNotProveTheCancelledWorkerExited() = runBlocking {
        for (released in listOf(null, false)) {
            val pending=OperitPending("cancel",targetId="old")
            pending.accept(complete(pending,released))
            val error=runCatching { consumeOperitEvents(pending,100) {} }.exceptionOrNull()
            assertEquals("OPERIT_PLUGIN_UPDATE_REQUIRED",error?.message)
        }
    }
    @Test fun explicitReleaseReceiptAndOrdinaryCompletionRemainUsable() = runBlocking {
        for(kind in listOf("cancel","reply")) {
            val pending=OperitPending(kind)
            pending.accept(complete(pending,if(kind=="cancel")true else null))
            consumeOperitEvents(pending,100) {}
        }
    }
    @Test fun pendingCancellationCodeSurvivesSanitizing() {
        assertEquals("OPERIT_CANCEL_PENDING",OperitBridge.safeCode("OPERIT_CANCEL_PENDING"))
    }
}
