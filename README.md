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
2. 安装后先打开 APK，确认显示 `1.3.0 (4)` 和包名 `com.yagay.dualsignal`。
3. 确认 LSPosed 的模块列表中出现“双排信号”，再启用它。
4. 作用域固定为系统界面 `com.android.systemui`（模块声明 static scope）。
5. 重启 SystemUI 或手机。
6. 查看 LSPosed 日志标签 `DualSignal102`。

## 调试日志

成功时应看到：

- `View attach hook installed`
- `stacked two mobile views: ... + ...`

如果只有第一条没有第二条，说明当前 ROM 的移动信号 View 类名/资源名与候选不一致。此时导出 SystemUI/LSPosed 日志即可继续精确适配，无需再猜资源 ID。

从 `1.2.0` 起，模块 App 内置专用诊断页。`1.3.0` 修正了候选 View、跨 wrapper 配对、过早完成标记、累计位移和布局重算问题；真正变换的是命中的信号 View，不再缩放整个 wrapper，也不要求两个信号拥有同一个直接父容器。SystemUI Hook 会把模块加载、Hook 安装、状态栏结构、候选 View、配对失败原因、尺寸/坐标、变换结果和异常写回 App。打开“双排信号”后可以刷新、复制、分享或清空；后续排查只需分享这里的文本，无需导出整包 LSPosed 日志。日志上限约 512 KiB，并限制单次 SystemUI 进程的诊断量。
