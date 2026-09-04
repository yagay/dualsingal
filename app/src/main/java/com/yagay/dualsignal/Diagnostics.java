package com.yagay.dualsignal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostic logger.
 *
 * Primary channel: Android Log (tag DualSignal102) — always visible in LSPosed.
 * Secondary channel: explicit broadcast to the module app for the in-app diagnostic page.
 * On Android 14+ the broadcast is best-effort; Log is the reliable source of truth.
 */
final class Diagnostics {
    private static final String TAG = "DualSignal102";
    private static final String PACKAGE = "com.yagay.dualsignal";
    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();
    private static final int MAX_PENDING = 48;

    private Diagnostics() {}

    static void record(Context context, String level, String event, String detail) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " | " + level + " | " + event + " | pid=" + Process.myPid()
                + " sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " | " + detail;

        // Always write to logcat — this is what LSPosed captures.
        int priority = "E".equals(level) ? Log.ERROR : ("W".equals(level) ? Log.WARN : Log.INFO);
        Log.println(priority, TAG, event + ": " + detail);

        if (context == null) {
            synchronized (PENDING) {
                if (PENDING.size() >= MAX_PENDING) PENDING.removeFirst();
                PENDING.addLast(line);
            }
            return;
        }
        flush(context);
        deliver(context, line);
    }

    private static void flush(Context context) {
        synchronized (PENDING) {
            while (!PENDING.isEmpty()) {
                deliver(context, PENDING.removeFirst());
            }
        }
    }

    private static void deliver(Context context, String line) {
        try {
            Intent intent = new Intent(DiagnosticReceiver.ACTION)
                    .setPackage(PACKAGE)
                    .setComponent(new ComponentName(PACKAGE, DiagnosticReceiver.class.getName()))
                    .putExtra(DiagnosticReceiver.EXTRA_LINE, line)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "diagnostic broadcast (explicit) failed: " + t.getMessage());
        }
    }
}
