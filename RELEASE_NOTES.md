# XDFHook v1.0.14

## 里程碑：配置架构 v2（per-scope）+ 全新 HyperOS 风格 UI

### 新架构
- **per-scope 配置模型**：主页重构为 7 个作用域卡片（system 系统框架 / android 系统界面 / Zeus 管控 / 系统设置 / 桌面 / 相册 / 应用安装器），每个 scope 独立开关与功能配置
- **作用域管理直连 LSPosed**：读取/加入/移出作用域全部走官方接口（getScope / requestScope / removeScope），无需 root 改写配置库
- **详情页**：每个 scope 点入可配置自己的全部功能开关（解除管控限制 / 完整设置 / 桌面增强 / 图片编辑 / 自由安装 / 设备信息伪装 / 分享面板修复…），命名改为结果导向、用户可读
- **配置 schema v2 + 通用链式迁移器**：旧版扁平行配置自动迁移，后续版本可平滑升级

### 技术升级
- AGP 9.4.1 / Gradle 9.6 / Kotlin 2.4 / Compose Multiplatform 1.12 / compileSdk 37（兼容 Android 10 设备）
- UI 全量重写为 Compose + Miuix（HyperOS 设计风格）
- 文案全部人话化，去技术黑话

### 修复
- 受控开关状态即时刷新（修复"配置点不了"）
- 导航切换按方向区分：切三键重启 system UI，切全面屏不重启
- 操作页按钮显示当前状态 + 等宽网格对齐
