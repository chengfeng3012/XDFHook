# XDFHook

一个为 XDF-N1 学习机（MT8183 / Android 10）打造的综合自用 Xposed 模块，尽可能在留着原系统的情况下获得原生 AOSP 的体验而不必刷机。

注意：该模块仅供交流学习使用，模块并不保证 100％ 的稳定，可能随着系统 / APP 更新导致 Hook 点位变化。使用此模块一定要先刷好救砖模块（如 Magisk 层的相关模块），数据无价，谨慎行事！

## 功能总览

模块以单包 `cn.cf3012.xdf` 组织，入口类 `XDFHook`，按进程分发到下列子模块：

| 子模块 | 承载进程 | 功能 |
| --- | --- | --- |
| **ZeusUnlocker** | `system_server` / `cn.xdf.zeus` | 家长管控三层解除（A 组 framework/services 放行 + B 组 zeus 进程短路 + C 组 Chooser Activity UI 修复） |
| **SettingsHooks** | `com.android.settings` | 属性放行 / 开发者选项 / 系统导航 / 主页「更多设置」选项卡 |
| **UiRestorer** | `com.android.settings` | 显示页 / 声音页 / 系统页被裁条目的注入与还原 |
| **HomeUnlocker** | `system_server` / `com.android.launcher3` | 解除默认桌面（HOME）强制锁定：拦 ROM 的强制写入行为，不拦用户手动设置 |
| **LauncherHooks** | `com.android.launcher3` | 最近任务隐藏列表解除 |
| **GalleryHooks** | `com.android.gallery3d` | 相册「编辑」按钮恢复（多层保险） |
| **SystemHooks** | 所有 scope 进程 | Resolver「仅此一次」按钮 + Chooser 占位目标修复 |
| **InputMethodHooks** | `system_server` + 所有 scope 进程 | 输入法拦截器（固化模式 / 黑名单模式，从源头防止输入法被篡改） |

> 设计原则：**大问题、难排查的问题从源头 Hook 解决**，而不是对千疮百孔的系统逐个补丁。例如输入法拦截直接拦 `Settings.Secure/Global.putString`，而非去瞎猜各进程改了什么、又是哪个蛀虫在偷偷修改。

## 技术要点

- **Xposed API**：LibXposed 新 API 102（`libs/api-102.0.0.jar` compileOnly）。无 `XposedHelpers`/`XC_MethodHook`，反射统一走 `Reflect`，hook 统一走 `XDFHook` 静态工具（PROTECTIVE 模式，单个 hook 失败只记日志、绝不影响宿主）。之所以强制要求新版 LibXposed API 102，是因为该学习机内 APP 大量使用某壳企业版进行加壳，新版 LSPosed 可以伪造 libart 实现注入绕过。未来将考虑 Hook `学习机桌面` 等 APP 实现自定义功能，使用新版 API 不仅更加成熟，还将会绕开某壳检测，更加稳定。
- **入口声明**：新版机制 `META-INF/xposed/`（`java_init.list` → `cn.cf3012.xdf.XDFHook`，`module.prop` minApi/targetApi=102，`scope.list` 见下），非旧式 `assets/xposed_init`。
- **作用域（scope）**：`system`(system_server) / `cn.xdf.zeus` / `com.android.settings` / `com.android.launcher3` / `com.android.gallery3d` / `com.android.systemui`。注：LibXposed API 体系中，模块不可对自身 Hook ，因此作用域中找不到应用本身纯属正常，也不必刻意寻找（毕竟软件开源想要啥改啥就行了，没必要自己 Hook 自己）。
- **PROTECTIVE 铁律**：每个 hook 走 `XposedInterface.ExceptionMode.PROTECTIVE`，回调内异常不外抛到宿主；`hookAllByName` 找不到目标会明确打日志，绝不静默失败。
- **跨进程通道**：官方 LibXposed `service` AAR（`service-102.0.0-nometa.aar` + `interface-102.0.0-nometa.aar`），经 daemon 注入的 XposedProvider 贯通 UI 与 hook 进程，共享同一份 daemon 托管 remote preferences。

## 模块设计细节

### ZeusUnlocker（家长管控解除）
- **A 组（system_server）**：`IntentStandardActionManager`（9 类标准动作禁止放行）、`ResolverActivity.setDefaultLauncher`（HOME 锁定解除）、`IActivityController` 全局 Activity 放行、`ZeusManager` 单例管控短路、`AppMetaDataChecker` 豁免。
- **B 组（zeus 进程）**：`ZeusManagerExempted` 豁免、云控 `ForceControlProvider.call` 阻断、`DeviceInfoProvider` 序列号伪造。
- **C 组（Chooser UI）**：恢复 `areAllItemsEnabled`、补注册 `OnItemClickListener`、给图标 cell 绑定 `OnClickListener`（解除「图标点击无涟漪/无反应」）。

### HomeUnlocker（HOME 解锁）
设计原则：**拦 ROM 的强制写入行为，不拦 preferred 状态本身**。framework 源头 `setDefaultLauncher` 跳过；launcher3 `setXdfDefaultHomeLauncher` block；system_server PMS preferred 写入走【只读探针】，命中 XDF 目标时记录调用方 uid/pid + IntentFilter 明细，仅在「激进拦截」开启时按调用方（cn.xdf.* / system_server）精确阻断，放行用户经设置/PermissionController 的主动设置。

### InputMethodHooks（输入法拦截器）
- 在 `system_server` 与所有 scope 进程 hook `Settings.Secure.putString` / `Settings.Global.putString`，只针对 `default_input_method` / `enabled_input_methods` / `selected_input_method` / `input_method_subtype_history` 四个键。
- **固化模式**（默认）：阻止一切对上述键的写入。
- **黑名单模式**：仅阻止黑名单中的输入法被设为默认。
- 从源头拦截，避免被恶意/意外篡改，也不去逐一修补各调用链。

## 日志与配置

- **FileLogger**：logcat + 广播上行 + 可配置单文件落盘三通道。广播 `cn.cf3012.xdf.ACTION_LOG`（setPackage 限定本模块）按 24 行/24000 字符单批双上限 flush，5s 定时兜底；**system_server 及一切 uid 1000 进程永久关闭广播**（防非保护广播触发 AMS wtf，曾致卡锁屏），但落盘独立于广播照常进行。落盘默认 `/sdcard/Android/XDFHook.log`，在选项中可配置开关 / 路径（输入后即时写入试探，成功才保存）/ 上限（单位 KB）。初期 DebugProbe 的多路径日志文件已全部删除，只剩这一份可配置落盘。
- **AppConfig**：UI 与 hook 侧共享 daemon 托管 remote prefs（LSPosed 官方通道，实时生效）；remote 不可用时降级 `/data/local/tmp/XDFHook.cfg`，并带一次性迁移。UI 侧可写实现优先，hook 侧只读实现不得覆盖。
- **状态查询**：总览页经 `XposedService.getRunningTargets()` 实时查询当前被注入进程，判断子模块「运行中/未检测到」。

## 配置界面（类 miuix 风格，零依赖纯 Java）

`ui.MainActivity`：竖屏底部导航 / 横屏左侧导航（`layout` vs `layout-land`）。

- **总览**：模块总开关 + 子模块开关与状态 pill + 服务/环境自检。
- **日志**：实时广播上行缓冲，按模块 tag 与级别 chip 过滤，2s 自动刷新。
- **操作**：占位页（暂无内容）。
- **选项**：按功能域分组——全局{日志级别、日志落盘、落盘路径、日志上限}、桌面锁定{PMS 激进拦截}、输入法锁定{拦截模式、黑名单管理}、其他{关于 / 检查更新（占位）/ 跳转仓库}。

## 编译

```bash
./gradlew assembleDebug
```

环境：Gradle 8.9（wrapper）+ AGP 8.7.3 + compileSdk 30 / minSdk 26 / targetSdk 29；JDK 17（`coreLibraryDesugaring` 支撑 service aar 的 record 类型）；`local.properties` 指明 SDK 路径 `/opt/android-sdk`，`gradle.properties` 覆盖 aapt2。
注：原始开发环境为 XDF-N1 ，不便下载依赖，因此直接使用本地依赖，防止抽风。

## 许可

[GPL-3.0](LICENSE) © chengfeng3012
