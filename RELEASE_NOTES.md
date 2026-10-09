## XDFHook v1.0.18 (code 18)

### 🆕 新增功能

**电源键 / 自动息屏失效修复**（scope: `system`）

XDF 在 `services.jar` 的 `PowerManagerService` 中埋了一段「触摸固件升级 50 秒常亮窗口」：
`goToSleepInternal` 与 `updateUserActivitySummaryLocked` 两处会调用
`com.android.server.power.xdf.ScreenBrightnessController.isKeepScreenOnWhenUpgradeTouchDriver()`，
其判据读取 `/proc/android_touch/fw_load_comp_flag` —— **本机并不存在这个节点**，因此恒为 true。
而 50 秒窗口的起点是「该类首次初始化」时刻（即开机后首次按电源键），
导致 **开机后首次息屏请求起 50 秒内，电源键 / `input keyevent 26` / 自动息屏全部失效**。

现已 hook 该判据恒返 `false`，一处覆盖两个调用点。

**环境伪装 + 新增 appstore / updater 两个作用域**

应用商店 / 升级中心 / 家长管控 三者判断测试域时漏掉了 `isDebugApk` 双条件，
只要 `Build.IS_USER=false` 就会切到 test 域名；现已把这 3 个进程伪装为生产环境。

**OTG U 盘挂载解禁**（scope: `system`）

ROM 把 `isMountDisallowed()` 里的 USB 判定硬编码成恒 true，导致 U 盘插上不挂载。
现已恢复 AOSP 原版判定逻辑（仍遵守 `no_usb_file_transfer` / `no_physical_media` 用户限制）。

**禁自动更新**（scope: `cn.xdf.updater`）

拦截夜间静默下载安装、自动重启刷机与强制更新，仅保留手动升级路径。

**手势导航解锁 + 防复位**（scope: `com.android.systemui` / `com.android.settings`）

第三方桌面下也能在设置里勾选「全面屏手势」，且换桌面 / SystemUI 重启后不被切回三键导航。

**标准动作过滤解除**

打电话 / 发短信 / 分享 / 搜索 / 文本处理不再被劫持到家长管控页面。

### 🛠 修复 / 优化

- 更新下载支持多 host 回退（`gh.cfoss.dpdns.org` → `gh.cf3012.eu.org` → `github.com`），探测与传输超时分离
- 更新弹窗补充「后台进行中」提示
- 修复 `AppConfig` 中一处注释粘连

### ⚠️ 生效条件

- `system` 作用域单元（电源键修复 / U 盘挂载）**装完需重启 framework 才生效**
- 环境伪装类开关「关 → 开」需重启目标应用
