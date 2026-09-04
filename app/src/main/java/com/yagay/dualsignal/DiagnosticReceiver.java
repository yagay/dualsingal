package com.yagay.dualsignal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Receives diagnostic lines from SystemUI (or the module app itself)
 * and appends them to the private diagnostic file.
 */
public final class DiagnosticReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yagay.dualsignal.DIAGNOSTIC";
    static final String EXTRA_LINE = "line";
    private static final String TAG = "DualSignal102";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        // Validate a real UID when the framework provides one. Some OPlus builds
        // report an unknown UID for explicit broadcasts, so unknown cannot be rejected.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                int uid = getSentFromUid();
                if (uid > 0 && !trustedSender(context, uid)) {
                    Log.w(TAG, "diagnostic rejected: untrusted uid=" + uid);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        String line = intent.getStringExtra(EXTRA_LINE);
        if (line == null || line.isEmpty() || line.length() > 8192) return;
        DiagnosticStore.append(context, line);
    }

    private boolean trustedSender(Context context, int uid) {
        try {
            if (uid == android.os.Process.myUid()) return true;
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return false;
            for (String name : packages) {
                if (context.getPackageName().equals(name)
                        || "com.android.systemui".equals(name)
                        || "android".equals(name)) {
                    return true;
                }
            }
            for (String name : packages) {
                if (name != null && name.toLowerCase().contains("systemui")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
