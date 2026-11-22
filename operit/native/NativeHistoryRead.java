package com.huigu.phone10.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/** Read-only adapter for O's existing getChatMessages. Called on the JS worker, never the UI thread. */
public final class NativeHistoryRead {
  private NativeHistoryRead() { }

  /** Keep O's result/message objects on the JVM side of the scripting bridge. */
  public static Map<String, Object> readSnapshot(Object reader, Object tool, long timeoutMs) throws Exception {
    Object result = read(reader, tool, timeoutMs);
    if (!Boolean.TRUE.equals(get(result, "getSuccess"))) throw new IllegalStateException("history read failed");
    Object data = get(result, "getResult");
    Object records = get(data, "getMessages");
    if (!(records instanceof Iterable)) throw new IllegalStateException("invalid history messages");
    List<Map<String, Object>> messages = new ArrayList<>();
    for (Object record : (Iterable<?>) records) {
      if (messages.size() >= 64) throw new IllegalStateException("history exceeds requested limit");
      Map<String, Object> message = new LinkedHashMap<>();
      Object timestamp = get(record, "getTimestamp");
      if (!(timestamp instanceof Number)) throw new IllegalStateException("invalid timestamp");
      message.put("timestamp", ((Number) timestamp).longValue());
      message.put("sender", string(get(record, "getSender")));
      message.put("content", string(get(record, "getContent")));
      Object role = get(record, "getRoleName");
      message.put("roleName", role == null ? "" : string(role));
      messages.add(message);
    }
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("chatId", string(get(data, "getChatId")));
    snapshot.put("messages", messages);
    return snapshot;
  }

  private static Object get(Object target, String method) throws Exception {
    if (target == null) throw new IllegalStateException("missing history value");
    return target.getClass().getMethod(method).invoke(target);
  }

  private static String string(Object value) {
    if (!(value instanceof String)) throw new IllegalStateException("invalid history text");
    return (String) value;
  }

  /** Insert synchronously: preserve provider backpressure and cancellation acknowledgements. */
  public static boolean emitEvent(Object context, Object uri, String eventJson) throws Exception {
    Class<?> contextClass = Class.forName("android.content.Context");
    Class<?> resolverClass = Class.forName("android.content.ContentResolver");
    Class<?> valuesClass = Class.forName("android.content.ContentValues");
    Class<?> uriClass = Class.forName("android.net.Uri");
    Object values = valuesClass.getConstructor().newInstance();
    valuesClass.getMethod("put", String.class, String.class).invoke(values, "event", eventJson);
    Object resolver = contextClass.getMethod("getContentResolver").invoke(context);
    Object result = resolverClass.getMethod("insert", uriClass, valuesClass).invoke(resolver, uri, values);
    return result != null && "accepted".equals(uriClass.getMethod("getQueryParameter", String.class).invoke(result, "status"));
  }

  public static Object read(Object reader, Object tool, long timeoutMs) throws Exception {
    if (timeoutMs < 1 || timeoutMs > 5000) throw new IllegalArgumentException("invalid read deadline");
    CompletableFuture<Object> result = new CompletableFuture<>();
    Continuation<Object> continuation = new Continuation<Object>() {
      @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
      @Override public void resumeWith(Object value) {
        try {
          ResultKt.throwOnFailure(value);
          result.complete(value);
        } catch (Throwable error) { result.completeExceptionally(error); }
      }
    };
    Method method = reader.getClass().getMethod("getChatMessages", tool.getClass(), Continuation.class);
    try {
      Object immediate = method.invoke(reader, tool, continuation);
      if (immediate != IntrinsicsKt.getCOROUTINE_SUSPENDED()) result.complete(immediate);
    } catch (InvocationTargetException error) { result.completeExceptionally(error.getCause()); }
    try { return result.get(timeoutMs, TimeUnit.MILLISECONDS); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
    catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw new IllegalStateException("native history read failed", cause);
    }
  }
}
