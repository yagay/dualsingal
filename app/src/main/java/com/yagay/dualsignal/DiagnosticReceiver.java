package com.yagay.dualsignal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class DiagnosticReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yagay.dualsignal.DIAGNOSTIC";
    static final String EXTRA_LINE = "line";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String line = intent.getStringExtra(EXTRA_LINE);
        if (line == null || line.length() > 8192) return;
        DiagnosticStore.append(context, line);
    }
}
