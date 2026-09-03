# 双排信号 / DualSignal (libxposed API 102)

面向 OxygenOS / ColorOS SystemUI 的双 SIM 状态栏双排信号实验模块。

## 与旧 Magisk overlay 版本的区别

旧包通过 vendor overlay APK 替换大量 SystemUI drawable/resource。系统更新后资源名、资源 ID、布局结构变化，很容易失效或连带影响其它状态栏图标。

本项目：

- 使用 `io.github.libxposed:api:102.0.0`
- scope 仅 `com.android.systemui`
- 不替换任何系统 drawable / resources.arsc
- 运行时寻找两张原生 mobile signal view
- 直接复用原 View 并纵向组合，所以信号强度、4G/5G、漫游、无服务等仍由系统原 controller 更新
- 找不到明确的两个移动信号 View 时直接不操作，避免 SystemUI 崩溃
- 使用 WeakHashMap 防止长期持有 SystemUI View/Context
- 同时处理直接子 View 与 OPlus 常见的一层 wrapper

## 编译环境

- Gradle / AGP: 9.2.0
- Java 17
- compileSdk / targetSdk: 37
- minSdk: 31
- libxposed API: 102.0.0

## 使用

1. 编译并安装 APK。
2. LSPosed 中启用“ 双排信号 ”。
3. 作用域为系统界面 `com.android.systemui`（模块声明 static scope）。
4. 重启 SystemUI 或手机。
5. 查看 LSPosed 日志标签 `DualSignal102`。

## 调试日志

成功时应看到：

- `View attach hook installed`
- `stacked two mobile views: ... + ...`

如果只有第一条没有第二条，说明当前 ROM 的移动信号 View 类名/资源名与候选不一致。此时导出 SystemUI/LSPosed 日志即可继续精确适配，无需再猜资源 ID。
