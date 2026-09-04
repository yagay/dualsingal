# DualSignal 1.7.0 — iOS27-style dual-row signal (LSPosed)

在 ColorOS / OxygenOS 16 上用 **LSPosed** 实现参考「iOS27 双排信号」的观感：

- **一张双排图标**：上排 = SIM1 格数，下排 = SIM2 格数（`DualRowSignalDrawable`）
- **不再**把两个系统图标缩小后上下叠放
- 右卡 layout 宽度收为 0，避免信号与电池之间空位
- 钩子：`View.onAttachedToWindow`、`ImageView.setImageDrawable`、`ImageView.setImageLevel`

## 版本

- versionName `1.7.0` / versionCode `20`
- libxposed API 102
- scope: `com.android.systemui`

## 使用

1. 编译安装，确认 App 显示 1.7.0 (20)
2. LSPosed 启用，作用域 `com.android.systemui`
3. 强制停止 SystemUI
4. 日志标签 `DualSignal102`，成功时应有 `mode=dual-row-drawable` 的 `STACK_APPLIED`
