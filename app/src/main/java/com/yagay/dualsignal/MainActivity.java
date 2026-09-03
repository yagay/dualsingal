package com.yagay.dualsignal;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int p = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("双排信号 · API 102");
        title.setTextSize(24);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("当前安装：" + installedVersion() + "\n包名：" + getPackageName()
                + "\n\n必须先确认 LSPosed 的模块列表中出现本模块，再启用它。"
                + "\n作用域固定为：系统界面 (com.android.systemui)"
                + "\n启用后重启系统界面或重启手机。"
                + "\n\n如果 LSPosed 模块列表没有“双排信号”，说明 APK 尚未被正确安装，Hook 不会运行。"
                + "\n\n本版不覆盖 SystemUI 资源，不替换系统信号状态，只对双 SIM 的原生移动信号 View 做缩放和平移，不改变原始 View 层级。");
        info.setTextSize(16);
        info.setPadding(0, p, 0, 0);
        root.addView(info);

        setContentView(root);
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
