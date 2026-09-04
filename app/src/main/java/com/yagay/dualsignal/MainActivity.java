package com.yagay.dualsignal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int p = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("双排信号 · 专用诊断");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("当前安装：" + installedVersion() + "\n包名：" + getPackageName()
                + "\n启用模块并重启 SystemUI 后点“刷新”。"
                + "\n本版直接启用 SystemUI 16.99.12 自带的原生双排信号管线。"
                + "\n安全策略：不修改 View、Drawable 或 LayoutParams。");
        info.setPadding(0, p, 0, p);
        root.addView(info);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("刷新", v -> refresh()));
        actions.addView(button("复制", v -> copy()));
        actions.addView(button("分享", v -> share()));
        actions.addView(button("清空", v -> clear()));
        root.addView(actions);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        DiagnosticStore.append(this, new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date())
                + " | I | APP_OPENED | version=" + installedVersion());
        refresh();
        logView.postDelayed(this::refresh, 300);
    }

    private Button button(String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        button.setAllCaps(false);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private void refresh() {
        logView.setText(DiagnosticStore.read(this));
    }

    private void copy() {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText("DualSignal diagnostics", DiagnosticStore.read(this)));
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "DualSignal 专用诊断日志")
                .putExtra(Intent.EXTRA_TEXT, DiagnosticStore.read(this));
        startActivity(Intent.createChooser(intent, "分享诊断日志"));
    }

    private void clear() {
        DiagnosticStore.clear(this);
        refresh();
        Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
    }

    private String installedVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
