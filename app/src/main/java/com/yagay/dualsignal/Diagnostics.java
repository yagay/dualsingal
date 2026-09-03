package com.yagay.dualsignal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Diagnostics {
    private static final String TAG = "DualSignal102";
    private static final String PACKAGE = "com.yagay.dualsignal";
    private Diagnostics() {}
    static void record(Context context, String level, String event, String detail) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " | " + level + " | " + event + " | pid=" + Process.myPid()
                + " sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " | " + detail;
        Log.println("E".equals(level) ? Log.ERROR : Log.INFO, TAG, event + ": " + detail);
        if (context == null) return;
        try {
            Intent intent = new Intent(DiagnosticReceiver.ACTION)
                    .setPackage(PACKAGE)
                    .setComponent(new ComponentName(PACKAGE, DiagnosticReceiver.class.getName()))
                    .putExtra(DiagnosticReceiver.EXTRA_LINE, line);
            context.sendBroadcast(intent);
        } catch (Throwable t) { Log.w(TAG, "diagnostic delivery failed", t); }
    }
}
