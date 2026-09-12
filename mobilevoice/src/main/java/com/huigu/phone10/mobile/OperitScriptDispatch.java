package com.huigu.phone10.mobile;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Runs inside O. No JS callback or broadcast lifetime owns this blocking call. */
public final class OperitScriptDispatch {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
        0, 2, 10, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "Phone10-OperitRequest");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    public static Future<?> start(Object manager, String event, Runnable failed) throws NoSuchMethodException {
        // Resolve compatibility before acknowledging dispatch. The O manager owns
        // engine acquisition, execution timeouts and engine release.
        Method execute = manager.getClass().getMethod("executeScript", String.class, Map.class);
        return WORKERS.submit(() -> {
            boolean handled;
            try {
                Object result = execute.invoke(manager, "phone10_mobile_voice.work", Collections.singletonMap("event", event));
                // Our work entry returns precisely this receipt; O errors are never
                // copied into IPC or logs. No automatic model retry is permitted.
                handled = result instanceof String && ((String) result).matches(
                    "\\s*\\{\\s*\"status\"\\s*:\\s*\"HANDLED\"\\s*}\\s*");
            } catch (Exception error) { handled = false; }
            if (!handled) failed.run();
        });
    }
}
