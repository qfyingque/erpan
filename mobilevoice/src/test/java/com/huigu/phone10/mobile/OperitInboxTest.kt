package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OperitInboxTest {
    @Test fun detachedWorkerFailureIsVisibleAfterPartialOutputWithoutGuessingSequence() = runBlocking {
        val pending = OperitPending("reply", "chat", "hello")
        assertTrue(pending.accept(event(pending, 0)))
        val failed = event(pending, -1).apply { addProperty("type", "worker_error") }
        assertFalse(pending.accept(failed))
        assertEquals("OPERIT_WORKER_FAILED", pending.failure)
        assertTrue(pending.events.tryReceive().isFailure)
    }

    private fun event(pending: OperitPending, seq: Int, nonce: String = pending.nonce) = JsonObject().apply {
        addProperty("version", 1); addProperty("id", pending.id); addProperty("nonce", nonce)
        addProperty("seq", seq); addProperty("type", "chunk"); addProperty("text", "test")
    }

    @Test fun wrongNonceAndOutOfOrderCannotReachConsumer() = runBlocking {
        val pending = OperitPending("reply", "chat", "hello")
        assertFalse(pending.accept(event(pending, 0, "wrong")))
        assertTrue(pending.events.tryReceive().isFailure)
        val next = OperitPending("reply", "chat", "hello")
        assertFalse(next.accept(event(next, 1)))
        assertTrue(next.events.tryReceive().isFailure)
    }

    @Test fun cancelledRequestRejectsLateEventsAndClearsQueuedText() = runBlocking {
        val pending = OperitPending("reply", "chat", "hello")
        assertTrue(pending.accept(event(pending, 0)))
        pending.cancel()
        assertFalse(pending.accept(event(pending, 1)))
        assertTrue(pending.events.tryReceive().isFailure)
        assertTrue(pending.cancelled)
    }

    @Test fun fullQueueFailsClosedWithObservableFailure() {
        val pending = OperitPending("reply", "chat", "hello")
        repeat(128) { assertTrue(pending.accept(event(pending, it))) }
        assertFalse(pending.accept(event(pending, 128)))
        assertTrue(pending.cancelled)
        assertEquals("OPERIT_EVENT_OVERFLOW", pending.failure)
    }

    @Test fun terminalEventClosesDeliveryAndPreservesExistingSequence() = runBlocking {
        val pending = OperitPending("reply", "chat", "hello")
        assertTrue(pending.accept(event(pending, 0)))
        val complete = event(pending, 1).apply { addProperty("type", "complete") }
        assertTrue(pending.accept(complete))
        assertFalse(pending.accept(event(pending, 2)))
        assertEquals("chunk", pending.events.receive()["type"].asString)
        assertEquals("complete", pending.events.receive()["type"].asString)
    }

    @Test fun acceptedIsOneTimeEvenAfterOperitLosesItsJournal() = runBlocking {
        val pending = OperitPending("reply", "chat", "hello")
        val accepted = event(pending, 0).apply { addProperty("type", "accepted") }
        assertTrue(pending.accept(accepted))
        assertFalse(pending.accept(accepted))
        assertFalse(pending.cancelled)
        assertTrue(pending.accept(event(pending, 1)))
        assertEquals("accepted", pending.events.receive()["type"].asString)
        assertEquals("chunk", pending.events.receive()["type"].asString)
    }

    @Test fun protocolFailureIsAnErrorRatherThanUserCancellation() = runBlocking {
        val pending = OperitPending("reply", "chat", "hello")
        pending.accept(event(pending, 1))
        try {
            consumeOperitEvents(pending, 100) {}
            fail("Expected protocol failure")
        } catch (error: IllegalStateException) {
            assertEquals("OPERIT_INVALID_EVENT", error.message)
        }
    }

    @Test fun ownTimeoutIsVisibleButParentCancellationRemainsCancellation() = runBlocking {
        try {
            consumeOperitEvents(OperitPending("list"), 10) {}
            fail("Expected timeout")
        } catch (error: IllegalStateException) {
            assertEquals("OPERIT_TIMEOUT", error.message)
        }
        try {
            withTimeout(10) { consumeOperitEvents(OperitPending("list"), 1000) {} }
            fail("Expected parent cancellation")
        } catch (_: TimeoutCancellationException) { }
    }
}
