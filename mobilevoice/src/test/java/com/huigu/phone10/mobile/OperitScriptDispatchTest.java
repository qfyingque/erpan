package com.huigu.phone10.mobile;

import org.junit.Test;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OperitScriptDispatchTest {
    public static class SlowManager {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile String tool, event;
        public String executeScript(String name, Map<String, String> params) throws InterruptedException {
            tool = name; event = params.get("event"); calls.incrementAndGet();
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing");
            return "{\"status\":\"HANDLED\"}";
        }
    }
    public static class FailedManager {
        public String executeScript(String name, Map<String, String> params) {
            return "{\"error\":\"private upstream detail\"}";
        }
    }
    public static class ThrowingManager {
        public String executeScript(String name, Map<String, String> params) {
            throw new IllegalStateException("private upstream detail");
        }
    }

    @Test public void nativeWorkSurvivesCallerReturnAndInvokesOnlyTheFixedTool() throws Exception {
        SlowManager manager = new SlowManager(); AtomicInteger errors = new AtomicInteger();
        Future<?> work = OperitScriptDispatch.start(manager, "uri-only-event", errors::incrementAndGet);
        try {
            assertTrue(manager.entered.await(2, TimeUnit.SECONDS));
            assertFalse(work.isDone());
        } finally { manager.release.countDown(); }
        work.get(2, TimeUnit.SECONDS);
        assertEquals(1, manager.calls.get());
        assertEquals("phone10_mobile_voice.work", manager.tool);
        assertEquals("uri-only-event", manager.event);
        assertEquals(0, errors.get());
    }

    @Test public void nativeFailuresSignalOnceWithoutRetryingOrExposingDetails() throws Exception {
        AtomicInteger errors = new AtomicInteger();
        OperitScriptDispatch.start(new FailedManager(), "uri-only", errors::incrementAndGet).get(2, TimeUnit.SECONDS);
        OperitScriptDispatch.start(new ThrowingManager(), "uri-only", errors::incrementAndGet).get(2, TimeUnit.SECONDS);
        assertEquals(2, errors.get());
    }

    @Test public void saturatedWorkersRejectInsteadOfQueuingStaleSpeech() throws Exception {
        SlowManager one = new SlowManager(), two = new SlowManager();
        Future<?> first = OperitScriptDispatch.start(one, "one", () -> {});
        Future<?> second = OperitScriptDispatch.start(two, "two", () -> {});
        try {
            assertTrue(one.entered.await(2, TimeUnit.SECONDS));
            assertTrue(two.entered.await(2, TimeUnit.SECONDS));
            try {
                OperitScriptDispatch.start(new SlowManager(), "three", () -> {});
                fail("must reject without queueing");
            } catch (java.util.concurrent.RejectedExecutionException expected) { }
        } finally { one.release.countDown(); two.release.countDown(); first.get(); second.get(); }
    }
}
