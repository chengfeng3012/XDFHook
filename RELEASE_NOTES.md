## XDFHook v1.0.11

### 🔧 修复

- **输入法拦截器长时间运行后失效**：hook 回调对象（lambda）未被强引用持有，GC 后 native 层回调丢失，PROTECTIVE 模式静默吞掉异常导致 hook 失效。现已在 `XDFHook` 中加入 `sHookKeepAlive` 强引用集合，所有 hooker 注册后永久持有，不受 GC 影响。
- **`putStringForUser` 绕过**：`InputMethodHooks` 仅 hook 了 `Settings.Secure/Global.putString`，遗漏了系统内部直接调用的 `putStringForUser` 重载。现已增加 2 条 hook，Secure + Global × putString + putStringForUser 四路径全覆盖。
- **ForceControlProvider 云控短路优化**：旧版对全部 25 条命令统一返回 null，导致查询型命令（isFocusModeStatus 等 7 条）的调用方 getBoolean 拿到 null/NPE，"原本能读的属性读不到"。现已区分查询/写命令：查询型 proceed 后置 false（=未管控）保留读取通道，写命令返回同构 Bundle 不执行真实逻辑。

### ✨ 新增

- **在线检查更新**：设置页"检查更新"接入 GitHub API，读取 `releases/latest` 的 tag 与 APK 下载直链，支持进度→有更新→已最新→失败四态弹窗。
- **GitHub Actions 自动构建发布**：推送 `v*.*.*` tag 或手动触发，自动构建 Debug APK 并创建 Release（含 APK 附件）。Release 正文读取仓库 `RELEASE_NOTES.md` 文件，下方自动追加 commit 变更日志。
- **Zeus 设备信息冒充**：支持自定义 Zeus 读取的型号与序列号，仅影响 `DeviceInfoProvider.call()` 返回值，不修改系统真实值。
- **操作页**：Root 总开关 + 自动旋转/固定屏幕朝向/深色模式/导航模式/切换输入法/切换桌面/刷新媒体库 + 危险区（软重启/硬重启/重启系统UI）。

### 📝 注意

- Release 正文由仓库根目录 `RELEASE_NOTES.md` 控制，每次发版前更新该文件即可。
- `generate_release_notes: true` 会自动追加 commit 历史在正文下方。
- APK 为 Debug 签名（各 runner 独立生成），不同设备间签名不一致属正常——仅用于 OTA 更新同签名设备。
