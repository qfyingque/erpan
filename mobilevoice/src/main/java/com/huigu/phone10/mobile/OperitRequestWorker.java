package com.huigu.phone10.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import org.json.JSONObject;

/** Public no-arg helper loaded by O from the installed companion APK, never a download. */
public final class OperitRequestWorker {
    public void start(Context context, Object manager, String requestUri,
                      String id, String nonce) throws Exception {
        Context application = context.getApplicationContext();
        String event = new JSONObject().put("uri", requestUri).toString();
        OperitScriptDispatch.start(manager, event, () -> {
            try {
                // A worker can fail after any number of chunks. The nonce-specific
                // terminal failure must not invent the JS producer's sequence.
                JSONObject failure = new JSONObject().put("version", 1).put("id", id)
                    .put("nonce", nonce).put("type", "worker_error");
                ContentValues values = new ContentValues();
                values.put("event", failure.toString());
                application.getContentResolver().insert(Uri.parse(requestUri), values);
            } catch (Exception unavailable) {
                // Cancellation/process death can already have revoked this URI.
                Log.w("Phone10Mobile", "OPERIT_WORKER_FAILURE_DELIVERY_UNAVAILABLE");
            }
        });
    }
}
