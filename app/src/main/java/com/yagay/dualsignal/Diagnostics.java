package com.yagay.dualsignal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayDeque;
import java.util.Locale;

final class Diagnostics {
    private static final String TAG = "DualSignal102";
    private static final String PACKAGE = "com.yagay.dualsignal";
    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();
    private static final int MAX_PENDING = 32;
    private Diagnostics() {}
    static void record(Context context, String level, String event, String detail) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " | " + level + " | " + event + " | pid=" + Process.myPid()
                + " sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " | " + detail;
        Log.println("E".equals(level) ? Log.ERROR : Log.INFO, TAG, event + ": " + detail);
        if (context == null) {
            synchronized (PENDING) {
                if (PENDING.size() == MAX_PENDING) PENDING.removeFirst();
                PENDING.addLast(line);
            }
            return;
        }
        flush(context);
        deliver(context, line);
    }

    private static void flush(Context context) {
        synchronized (PENDING) {
            while (!PENDING.isEmpty()) deliver(context, PENDING.removeFirst());
        }
    }

    private static void deliver(Context context, String line) {
        try {
            Intent intent = new Intent(DiagnosticReceiver.ACTION)
                    .setPackage(PACKAGE)
                    .setComponent(new ComponentName(PACKAGE, DiagnosticReceiver.class.getName()))
                    .putExtra(DiagnosticReceiver.EXTRA_LINE, line);
            context.sendBroadcast(intent);
        } catch (Throwable t) { Log.w(TAG, "diagnostic delivery failed", t); }
    }
}
