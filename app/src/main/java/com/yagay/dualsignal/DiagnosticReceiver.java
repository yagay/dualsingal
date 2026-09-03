package com.yagay.dualsignal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class DiagnosticReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yagay.dualsignal.DIAGNOSTIC";
    static final String EXTRA_LINE = "line";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 34 && !trustedSender(context, getSentFromUid())) return;
        String line = intent.getStringExtra(EXTRA_LINE);
        if (line == null || line.length() > 8192) return;
        DiagnosticStore.append(context, line);
    }

    private boolean trustedSender(Context context, int uid) {
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return false;
            for (String name : packages) {
                if (context.getPackageName().equals(name) || "com.android.systemui".equals(name)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
