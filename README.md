# DualSignal 1.8.2 — OxygenOS 16 原生双排信号开关

本版针对用户提供的 `System UI 16.99.12` APK 实现，不再扫描、移动或替换状态栏 View。

该 SystemUI 已包含完整的 Android 16 原生双排移动信号组件：

- `StackedMobileBindableIcon`
- `StackedMobileIconBinder`
- `StackedMobileIconViewModelImpl` / `StackedMobileIconViewModelKairos`
- `StackedMobileIconKt`

ROM 中这套功能被 `getShouldBindIcon() == false` 和 `isStackable == false` 关闭。本模块只 Hook 这些原生开关并返回启用状态，让 SystemUI 自己完成：

- 双 SIM 顺序与当前数据卡识别
- 两排真实信号等级
- 无服务、感叹号和运营商切换状态
- 网络类型、颜色与深色模式
- 隐藏原来的独立 SIM 图标
- 状态栏布局和生命周期管理

## 安全性

- 不 Hook 全局 `View.onAttachedToWindow`
- 不 Hook 全局 `ImageView.setImageDrawable/setImageLevel`
- 不调用 `removeView/addView`
- 不修改 `LayoutParams`
- 不清空或隐藏系统 Drawable
- 不伪造默认信号格数
- 不使用自定义信号 Drawable

## 环境

- versionName `1.8.2` / versionCode `24`
- libxposed API 102
- scope：`com.android.systemui`
- 针对 System UI `16.99.12`

## 1.8.2 修复

- 同时在 `onPackageLoaded` 和 `onPackageReady` 安装 Hook，兼容不会及时触发后者的 LSPosed/OPlus 组合。
- 按 ClassLoader 身份分别去重；早期与最终 ClassLoader 不同时，两套类都能正确 Hook。
- Android 16/OPlus 无法提供广播发送 UID 时不再误丢诊断日志；若能取得 UID，仍验证来源包。
- 模块 App 自身的 `APP_OPENED` 日志直接写入专用日志文件，不依赖跨进程广播。
- 增加原生开关实际被调用的日志：`NATIVE_BIND_ENABLED`、`CLASSIC_STACK_ENABLED`、`KAIROS_STACK_ENABLED`。

## 使用

1. 安装 Actions 产物中的 `DualSignal-v1.8.2-debug.apk`。
2. 在 LSPosed 中启用“双排信号”，作用域为 `com.android.systemui`。
3. 重启手机或 SystemUI。
4. 打开模块 App，点击“刷新”查看专用日志。

正常应出现：

- `MODULE_LOADED`
- `HOOK_INSTALLED mode=native-stacked-mobile`
- `SYSTEMUI_READY`
- `NATIVE_STACK_READY`
- `NATIVE_BIND_ENABLED`
- `CLASSIC_STACK_ENABLED` 或 `KAIROS_STACK_ENABLED`
