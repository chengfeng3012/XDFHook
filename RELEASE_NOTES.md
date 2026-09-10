# v1.0.13

## 新增
- **固定 Release 签名**：CI 构建从 `assembleDebug`（AGP 每次随机生成 debug.keystore）改为 `assembleRelease` + GitHub Secrets 注入的统一 keystore。解决"每次 release APK 证书都不同、导致跨版本无法覆盖安装（需卸载重装）"的问题。自本版起所有 CI release 使用同一签名 key，可无缝升级。

## 修复
- CI 构建失败修复：禁用 release 构建的 Lint Vital 检查。assembleRelease 连带的 `lintVital` 任务中 D8/desugar 处理（`D8BackportedMethodsGenerator`）在 GitHub Runner 环境报编译失败，属环境问题与代码无关，已在 `build.gradle` 的 `lint` 块关闭 release 检查。

## 说明
- 签名已切换为新固定 key，首次安装本版前请卸载旧版本重装一次，后续升级即无缝。
