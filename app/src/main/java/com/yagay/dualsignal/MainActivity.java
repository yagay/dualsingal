package com.yagay.dualsignal;

import android.app.Activity;
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
        info.setText("在 LSPosed 中启用本模块。\n作用域固定为：系统界面 (com.android.systemui)\n\n启用后重启系统界面或重启手机。\n\n本版不覆盖 SystemUI 资源，不替换系统信号状态，只把双 SIM 的原生移动信号 View 组合为上下双排。这样系统的 4G/5G、漫游、无服务和信号强度更新仍由 SystemUI 原逻辑负责。");
        info.setTextSize(16);
        info.setPadding(0, p, 0, 0);
        root.addView(info);

        setContentView(root);
    }
}
