# XDFHook v1.0.21

## 新增：媒体库元数据补全（新作用域「媒体库」）

**修的问题**：截图之后 QQ 相册里看不到刚截的图；把文件名改一下（`Screenshot_xxx` → `IMG_xxx`）
就又看得见了。

**根因**（这台 ROM 的两个特性叠加）：

- 存储层是 **sdcardfs** 而不是 FUSE，没有 Android 10 的「写后自动索引」；
- SystemUI 截图走 `MediaStore.createPending / openPending / publish`，而本机 MediaProvider
  **没有 publish 分支**（全 dex 搜 "publish" 0 命中），publish 只是一次 `update(is_pending=0)`
  → 行永远停在 `_size = NULL`、`width/height = NULL`；
- QQ 相册用 `MediaFileFilter.imageMinSize = 1000` 过滤
  （`PhotoCommonBaseData$MyFilterListener.filterMedia()`），`_size = NULL` 读作 0
  → 被当成「0 字节图片」丢弃。
  改名会触发一次真实扫描把 size 补上，所以看起来像「改名才可见」，其实与文件名无关。

**怎么修**：新单元 `MediaMetaFixHooks`（作用域 `com.android.providers.media`，进程 `android.process.media`）

1. `MediaProvider.update()` **proceed 之前**：命中发布（`is_pending = 0`）时，把
   `_size`（`File.length()`）与 `width/height`（`inJustDecodeBounds`，只读文件头、不分配像素）
   直接塞进同一个 `ContentValues`，由 Provider 自己写下去 —— 不重入、不涉及跨 uid 权限；
2. `MediaProvider.insert()` **proceed 之前**：非 pending 且文件已存在时同样补；
3. `insert` 之后延迟 1.2s / 4s 各回查一次（兼容「先建行、后写文件」且不发 publish 的实现）；
4. `MediaProvider.onCreate()` 之后后台补存量：扫 `_size IS NULL` / `width IS NULL` 的行逐条补全
   （单表 2000 行上限、90s 预算、每 50 行让一次 IO），文件已不存在的僵尸行直接跳过，不做删除。

**入口**：App → 概览 → **媒体库**（两个开关，默认开）

- 「媒体库元数据补全」：热路径，开 → 关 立即生效
- 「存量数据补全」：媒体库进程启动后跑一次
- 该进程不是 system_server，`kill android.process.media` 即生效，**不需要重启手机**

## 其它

- 推荐作用域清单同步：`META-INF/xposed/scope.list` 与 `resources` 里的 `xposed_scope` 数组
  补上 `com.android.providers.media`（此前两处都漏了）
- 概览页新增第 11 个作用域卡片「媒体库」，并列入「关→开需重启目标进程」提示

## 升级说明

- 覆盖安装后请在 LSPosed 里给模块勾选 **`com.android.providers.media`**，否则新单元不会注入
- 首次生效后建议结束一次媒体库进程（`su -c "killall android.process.media"`），让存量补全跑一遍
- 风险面：只写 `_size` / `width` / `height` 三列，**不改** `date_modified` / `date_added`
  （QQ 相册按修改时间排序，改时间会打乱顺序）

## 实测记录（XDF-N1 / Android 10）

- 修复前：`Pictures/Screenshots` 下 63 行 `_size IS NULL`，QQ 相册看不到
- 修复后：646 → 285 行（清掉 362 条指向已删文件的僵尸行），`_size` / `width` / `height` 缺失全部为 0
- 新截图：发布瞬间即写入 `_size` 与 `width/height`（2176x1600），QQ 相册立即可见
