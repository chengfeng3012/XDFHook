# v1.0.12

## 新增
- **解除软件包安装限制**（新模块 PackageInstallerHooks，process `com.android.packageinstaller`）：
  - 绕过 Roco 签名白名单 `FF7CC6961980EC50752930878B0B80FE`（`checkCallingSignRight` / `checkInstallPackageSign` 强制放行）；
  - 越过 `no_install_apps` / `no_install_unknown_sources` / AppOps 等安装限制（`checkIfAllowedAndInitiateInstall` 直通 `initiateInstall`）；
  - 作用在独立进程，`kill` 后即生效，无需重启系统。
- **静默安装旁路**：操作页新增「静默安装」按钮（root `pm install`，借 system 权限绕过安装 UI）。
- 选项页新增「安装解锁」开关。

## 修复
- InputMethodHooks：4 个 `Settings` 读写 hook 改用 `safeHook` 独立容错。修复 MTK 定制 ROM 个别 `systemui` 子进程不含 `putStringForUser` 时整个输入法 Hook 链中断的问题。

## 其他
- versionCode 12 / versionName 1.0.12，README 更新 scope（含 `com.android.packageinstaller`）。
- 推荐 scope（scope.list）增补 `com.android.packageinstaller`；移除 `cn.cf3012.xdf`（新版 API 模块不可 Hook 自身）。