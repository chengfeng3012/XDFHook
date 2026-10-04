package cn.cf3012.xdf;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Properties;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.service.XposedService;

/**
 * AppConfig — 模块配置（UI 进程与所有 hook 进程共享）。
 *
 * 数据链路（官方通道，LSPosed daemon 中转，binder 推送变更）：
 *   hook 侧：XposedInterface.getRemotePreferences(GROUP)   —— api-102 内置
 *   UI 侧  ：XposedService.getRemotePreferences(GROUP)     —— service aar
 *   两侧同一 GROUP 指向同一份 daemon 托管存储（LSPosed 私有 root 目录），
 *   不怕删、不怕改、无 SELinux/DAC 问题；UI 改开关后 hook 侧经
 *   OnSharedPreferenceChangeListener 实时生效，无需轮询。
 *
 * 降级链：
 *   remote 不可用（老 LSPosed / api 异常）→ 回退旧方案：
 *   /data/local/tmp/XDFHook.cfg（hook 进程 3s TTL 读，UI 写后 chmod 0666）。
 *   UI 侧 service 未绑定时为只读展示态。
 *
 * 一次性迁移：UI/hook 侧首次拿到空的 remote 配置时，把旧 cfg 文件的
 * 值导入 remote（保留用户既有开关状态），随后 remote 为唯一真源。
 */
public final class AppConfig {

    /** remote preferences 分组名（两侧必须一致） */
    public static final String GROUP = "xdfhook_cfg";

    /** 配置 schema 版本：>=2 为 per-scope 结构（scope + hook 单元）；1 为旧扁平键 */
    public static final int VERSION = 2;
    public static final String K_VERSION = "version";

    /** scope 条目（LSPosed 作用域：system_server='system'，框架 UI='android'，其余为包名） */
    public static final String SCOPE_SYSTEM = "system";
    public static final String SCOPE_ANDROID = "android";
    public static final String SCOPE_ZEUS = "cn.xdf.zeus";
    public static final String SCOPE_SETTINGS = "com.android.settings";
    public static final String SCOPE_LAUNCHER = "com.android.launcher3";
    public static final String SCOPE_GALLERY = "com.android.gallery3d";
    public static final String SCOPE_PACKAGE_INSTALLER = "com.android.packageinstaller";
    public static final String SCOPE_SYSTEMUI = "com.android.systemui";

    /** hook 单元（per-scope 功能开关；键 = scopes.<scope>.hooks.<unit>） */
    public static final String H_UNLOCK_CTRL = "unlockControl";     // 解除管控限制（system A 组 / zeus B 组）
    public static final String H_SPOOF_DEVICE = "spoofDevice";      // 设备信息伪装（型号/SN，zeus）
    public static final String H_HOME_UNLOCK = "homeUnlock";        // 默认桌面解锁（system PMS / launcher block）
    public static final String H_DESKTOP_PROTECT = "desktopProtect"; // 桌面锁定防护（PMS 激进拦截）
    public static final String H_IME_GUARD = "imeGuard";            // 输入法保护
    public static final String H_SHARE_CHOOSER = "shareChooser";    // 分享面板修复
    public static final String H_SETTINGS_UNLOCK = "settingsUnlock"; // 完整设置
    public static final String H_LOCK_UNLOCK = "lockUnlock";        // 锁屏方式恢复（滑动/PIN/图案/密码）
    public static final String H_RECENT_TASKS = "recentTasks";      // 桌面增强（最近任务）
    public static final String H_GALLERY_EDIT = "galleryEdit";      // 图片编辑
    public static final String H_INSTALL_UNLOCK = "installUnlock";  // 自由安装
    public static final String H_QS_FIX = "qsFix";                // 控制中心修复（systemui）
    public static final String H_USB_AUTH = "usbAuth";          // USB 授权弹窗修复（system）
    public static final String H_USB_AUTH_DIAG = "usbAuthDiag";  // USB 授权诊断日志（system）

    /** scope hook 单元的配置键名 */
    public static String hookKey(String scope, String hook) {
        return "scopes." + scope + ".hooks." + hook;
    }

    /**
     * legacy 配置文件路径（root 写，hook 进程读）。
     *
     * <p>仅在 remote（Remote Preferences）不可用时使用。</p>
     */
    public static final String PATH = "/data/local/tmp/XDFHook.cfg";

    /**
     * UI 进程私有备份文件（<b>不需要 root</b>）。
     *
     * <p>★★★ 2026-10 修复「配置全部保存失败」的落点：remote 通道不可用时
     * 原实现只有一条降级路 —— root 写 /data/local/tmp/XDFHook.cfg。该路在
     * 实际环境里几乎必然失败：su 未授权 / Magisk 首次弹窗被拒 / Root.exec
     * 6s 超时，且失败<b>完全静默</b>，于是用户看到「开关能切、切完归零」。</p>
     *
     * <p>本文件写在 App 自己的 filesDir（{@code getFilesDir()}）里，权限天然
     * 属于本 App uid，<b>不依赖 su、不依赖任何外部权限</b>，是 UI 侧最后也最稳
     * 的兜底。UI 写入后由 root 同步到 PATH 供 hook 进程读；root 不可用时
     * 至少 UI 侧状态不再丢（下次 App 启动能读回自己写的内容）。</p>
     */
    private static volatile java.io.File sUiFile;

    /** 设置 UI 进程私有配置文件（由 XdfApp.onCreate 调，传入 getFilesDir()） */
    public static void setUiFileDir(java.io.File dir) {
        if (dir == null) {
            return;
        }
        try {
            java.io.File f = new java.io.File(dir, "XDFHook.cfg");
            sUiFile = f;
            DebugProbe.log("UI private cfg file = " + f.getAbsolutePath());
        } catch (Throwable t) {
            DebugProbe.log("setUiFileDir failed: " + t);
        }
    }

    public static final String K_MASTER = "master";
    public static final String K_ZEUS = "mod_zeus";
    public static final String K_SETTINGS = "mod_settings";
    public static final String K_CHOOSER = "mod_chooser";
    public static final String K_LAUNCHER = "mod_launcher";
    public static final String K_GALLERY = "mod_gallery";
    public static final String K_HOME = "mod_home";
    public static final String K_AGGRESSIVE = "pms_aggressive";
    public static final String K_LOG_LEVEL = "log_level";
    public static final String K_MOD_INPUT_METHOD = "mod_input_method";
    public static final String K_INPUT_METHOD_MODE = "input_method_mode";
    public static final String K_INPUT_METHOD_LIST = "input_method_list";
    /** 解除「软件包安装程序」安装限制 */
    public static final String K_MOD_PACKAGE_INSTALL = "mod_package_install";

    /** Zeus 设备信息冒充：仅影响 zeus 读取到的 model/sn */
    public static final String K_ZEUS_MODEL = "zeus_model";
    public static final String K_ZEUS_SN = "zeus_sn";
    /** QS 修复：目标列数（1-6） */
    public static final String K_QS_COLUMNS = "qs_columns";
    /** QS 修复：目标最大行数（1-3） */
    public static final String K_QS_MAX_ROWS = "qs_max_rows";
    /** QS 修复：footer 前景色（ARGB） */
    public static final String K_QS_FOOTER_FG = "qs_footer_fg";
    /** QS 修复：编辑页 tile 文本色（ARGB） */
    public static final String K_QS_TILE_TEXT_DARK = "qs_tile_text_dark";


    /** 可选的冒充型号；空串表示"未设置（上报真实）" */
    public static final String[] ZEUS_MODELS = {
            "XDF-N2", "XDF-N1", "XDF-N2-L",
            "XDF-X1-S", "XDF-N1-GM", "XDF-N2-GM", "XDF-X1-GM"
    };

    // 注：原「日志落盘」三项（log_file_enabled / log_file_path / log_file_cap_kb）
    // 已随 FileLogger 重构删除：hook 进程不再写任何文件，日志统一走 logcat +
    // framework api.log()，无需路径/上限配置。

    public boolean master = true;

    // ---- per-scope hook 单元开关（全部读出，各进程按需取用） ----
    // system
    public boolean hSystemUnlockControl = true;   // 解除管控限制（framework 放行）
    public boolean hSystemHomeUnlock = true;      // 默认桌面解锁（PMS 探针）
    public boolean hSystemImeGuard = false;       // 输入法保护（默认关闭：会拦系统切输入法）
    public boolean hSystemDesktopProtect = false; // 桌面锁定防护（激进，原 pms_aggressive）
    public boolean hSystemUsbAuth = true;        // USB 授权弹窗修复（A10 UVC/CAMERA 误判 + BAL 放行）
    public boolean hSystemUsbDiag = false;       // USB 授权诊断日志（热路径，默认关）
    // android
    public boolean hAndroidShareChooser = true;   // 分享面板修复
    // zeus
    public boolean hZeusUnlockControl = true;     // 解除管控限制（检查链/云控）
    public boolean hZeusSpoofDevice = true;       // 设备信息伪装
    // settings
    public boolean hSettingsUnlock = true;        // 完整设置
    public boolean hSettingsLockUnlock = true;    // 锁屏方式恢复（与「完整设置」相互独立）
    // launcher
    public boolean hLauncherRecentTasks = true;   // 桌面增强
    public boolean hLauncherHomeUnlock = true;    // 默认桌面解锁（launcher block）
    // gallery
    public boolean hGalleryEdit = true;           // 图片编辑
    // packageinstaller
    public boolean hInstallUnlock = true;         // 自由安装
    // systemui
    public boolean hSystemuiQsFix = true;          // 控制中心修复

    public int logLevel = Log.INFO;
    public int inputMethodMode = 0; // 0=固化, 1=黑名单
    public String inputMethodList = "";
    /** Zeus 设备信息冒充；空串 = 未设置（上报真实） */
    public String zeusModel = "";
    public String zeusSn = "";

    public int qsColumns = 5;
    public int qsMaxRows = 2;
    public int qsFooterFg = 0xFFFFFFFF;
    public int qsTileTextDark = 0xFF212121;

    /** 底层通道：null = 未初始化或降级到文件 */
    private static volatile SharedPreferences sRemote;
    private static volatile boolean sRemoteTried;
    /** 文件模式 TTL 缓存 */
    private static volatile AppConfig sFileCache;
    private static volatile long sFileLoadedAt;
    private static final long TTL_MS = 3000;

    private AppConfig() {
    }

    /* ==================== 初始化（各侧一次） ==================== */

    /**
     * hook 侧初始化（XDFHook 入口最先调用，早于任何日志/配置读取）。
     * 失败不抛出：降级文件模式。
     */
    public static void hookInit(XposedInterface api) {
        initRemote(new RemoteGetter() {
            @Override
            public SharedPreferences get(XposedService svc) {
                return null; // hook 侧不走 XposedService
            }

            @Override
            public SharedPreferences getHook(XposedInterface x) {
                return x.getRemotePreferences(GROUP);
            }
        }, api, null);
    }

    /** UI 侧初始化（XposedServiceHolder 绑定回调） */
    public static void uiInit(XposedService service) {
        initRemote(new RemoteGetter() {
            @Override
            public SharedPreferences get(XposedService svc) {
                return svc.getRemotePreferences(GROUP);
            }

            @Override
            public SharedPreferences getHook(XposedInterface x) {
                return null;
            }
        }, null, service);
    }

    private interface RemoteGetter {
        SharedPreferences get(XposedService svc);

        SharedPreferences getHook(XposedInterface api);
    }

    /**
     * 统一初始化入口。
     * 来源标记（阻断 F 修复）：UI 进程会被两条链初始化——
     *   onModuleLoaded 的 hookInit（fork 的 hook 侧实现 edit() 抛 UOE，只读）和
     *   daemon 推送的 uiInit（可写）。后到覆盖的旧逻辑若让 hook 侧只读实现
     *   覆盖了 UI 侧可写实现 → 所有开关不可保存。故 hook 侧来源不得覆盖
     *   已建立的 UI 侧来源。
     */
    private static synchronized void initRemote(RemoteGetter getter,
                                                XposedInterface api, XposedService svc) {
        final int src = api != null ? SRC_HOOK : SRC_UI;
        if (src == SRC_HOOK && sRemoteSource == SRC_UI) {
            DebugProbe.log("initRemote: keep ui-side writable remote, skip hook-side");
            return;
        }
        try {
            SharedPreferences p = api != null ? getter.getHook(api) : getter.get(svc);
            if (p != null) {
                sRemote = p;
                sRemoteTried = true;
                sRemoteSource = src;
                if (api == null) {
                    sUiService = svc;
                }
                // fork 的 hook 侧 RemotePreferences.edit() 直接抛 UOE（只读），
                // 一次性迁移只有 UI 侧能写——hook 侧跳过（审查结论 6）
                if (api == null) {
                    migrateToLatest(p);
                    // ★ 补齐：把 legacy 文件里 remote 缺失的键搬进 remote，
                    //   成功后删除 legacy 文件（避免旧值长期固化，详见 adoptLegacyIntoRemote）
                    adoptLegacyIntoRemote(p);
                }
                // 静态强引用（审查结论 5）：部分实现用弱引用持有 listener
                try {
                    p.registerOnSharedPreferenceChangeListener(sListener);
                } catch (Throwable ignored) {
                }
                applyLogLevel(p);
                DebugProbe.log("initRemote OK via "
                        + (api != null ? "hook-side api (read-only)" : "ui-side service (writable)"));
                return;
            }
            DebugProbe.log("initRemote: getter returned null, fallback to file cfg");
        } catch (Throwable t) {
            DebugProbe.log("initRemote FAILED: " + t);
        }
        sRemote = null;
        sRemoteTried = true;
    }

    /**
     * legacy → remote 一次性补齐（UI 侧可写时执行）。
     *
     * <p><b>为什么需要</b>：remote preferences 是唯一真源（refresh 优先读它），
     * 但历史上写入经常落到 legacy 文件（remote 尚未就绪时用户就点了开关）。
     * 于是 legacy 里攒了 remote 没有的键（如 qsFix / usbAuth / qs_columns），
     * remote 读不到 → 界面显示默认值 → 用户以为设置没生效/被重置。</p>
     *
     * <p><b>策略</b>：只补 remote <b>没有</b>的键（remote 已有的绝不覆盖，
     * 避免把用户较新的设置回退成 legacy 旧值）；按键的真实类型写入
     * （boolean/int/string，不能一律当字符串，否则 getInt 会 ClassCastException）；
     * 打 {@link #K_LEGACY_ADOPTED} 标记保证只做一次；成功后删除 legacy 文件，
     * 防止它长期残留成为「第二个真源」导致配置固化。</p>
     */
    private static void adoptLegacyIntoRemote(SharedPreferences p) {
        try {
            if (p.getBoolean(K_LEGACY_ADOPTED, false)) {
                return; // 已补齐过，绝不重复（否则 legacy 旧值会反复覆盖新值）
            }
            Properties legacy = loadProps(new File(PATH));
            // UI 私有文件同样纳入来源：remote 不可用那段时间可能写在这里
            File ui = sUiFile;
            if (ui != null && ui.exists()) {
                Properties p2 = loadProps(ui);
                for (String k : p2.stringPropertyNames()) {
                    if (!legacy.containsKey(k)) {
                        legacy.put(k, p2.getProperty(k));
                    }
                }
            }
            SharedPreferences.Editor e = p.edit();
            int moved = 0;
            for (String k : legacy.stringPropertyNames()) {
                if (p.contains(k)) {
                    continue; // remote 已有 → 保留 remote 的值，不覆盖
                }
                String v = legacy.getProperty(k);
                if (v == null) {
                    continue;
                }
                if (putTyped(e, k, v.trim())) {
                    moved++;
                }
            }
            e.putBoolean(K_LEGACY_ADOPTED, true);
            e.putLong(K_LEGACY_ADOPTED_AT, System.currentTimeMillis());
            boolean ok = e.commit();
            DebugProbe.log("adoptLegacy: moved=" + moved + " commit=" + ok
                    + " legacyKeys=" + legacy.size());
            if (ok) {
                refreshRemoteAfterWrite();
                dropLegacyFiles(moved);
            }
        } catch (Throwable t) {
            DebugProbe.log("adoptLegacy FAILED: " + t);
        }
    }

    /**
     * 按键的真实类型写入。
     *
     * <p>★ 关键：不能一律 {@code putString}。remote 侧读配置走的是
     * {@code p.getInt(...)} / {@code p.getBoolean(...)}，若这里存成 String，
     * 读时类型不匹配会抛 ClassCastException（被 readFromRemote 的 catch 吞掉 →
     * 又变成「全默认值」的静默失败）。</p>
     *
     * @return true=已写入, false=键类型无法判定（跳过）
     */
    private static boolean putTyped(SharedPreferences.Editor e, String k, String v) {
        // 1) 布尔键：值恰为 true/false
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            e.putBoolean(k, Boolean.parseBoolean(v));
            return true;
        }
        // 2) 整型键：整数字面量（排除极长数字串，如序列号 XDFN1724...）
        if (INT_KEYS.contains(k)) {
            try {
                e.putInt(k, Integer.parseInt(v));
                return true;
            } catch (NumberFormatException ignored) {
                e.putString(k, v); // 兜底存串，至少不丢
                return true;
            }
        }
        // 3) 其余一律字符串（型号/SN/输入法列表等）
        e.putString(k, v);
        return true;
    }

    /** 需要按 int 存储的键（避免被当成字符串） */
    private static final java.util.Set<String> INT_KEYS =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(java.util.Arrays.asList(
                    K_LOG_LEVEL, K_INPUT_METHOD_MODE,
                    K_QS_COLUMNS, K_QS_MAX_ROWS, K_QS_FOOTER_FG, K_QS_TILE_TEXT_DARK,
                    K_VERSION)));


    /** 补齐完成标记（写进 remote，永久生效，杜绝重复补齐） */
    private static final String K_LEGACY_ADOPTED = "__legacy_adopted";
    private static final String K_LEGACY_ADOPTED_AT = "__legacy_adopted_at";

    /**
     * 补齐成功后删除 legacy 文件。
     *
     * <p>不删的危害：legacy 成为「第二个真源」，一旦 remote 临时不可用，
     * refresh 会回落到 legacy，读到早已过时的值 —— 表现为配置「固化」在
     * 某个旧状态，改不动也回不去。删掉后 remote 不可用时宁回落默认值，
     * 也不给用户看脏数据。</p>
     */
    private static void dropLegacyFiles(int moved) {
        // UI 私有文件：app 自身权限，直接删
        try {
            File ui = sUiFile;
            if (ui != null && ui.exists() && ui.delete()) {
                DebugProbe.log("dropLegacy: deleted UI private file");
            }
        } catch (Throwable ignored) {
        }
        // /data/local/tmp/XDFHook.cfg：普通 app 无权限，需 root
        try {
            File shared = new File(PATH);
            if (shared.exists()) {
                if (Root.exec("rm -f " + PATH)) {
                    DebugProbe.log("dropLegacy: deleted " + PATH);
                } else {
                    // 删不掉就改名标记为「已作废」，避免下轮再被当来源采用
                    Root.exec("mv " + PATH + " " + PATH + ".adopted");
                    DebugProbe.log("dropLegacy: renamed " + PATH + " -> .adopted");
                }
            }
        } catch (Throwable t) {
            DebugProbe.log("dropLegacy failed: " + t);
        }
    }

    /**
     * 配置变更回调（供 hook 订阅自己关心的键）。
     *
     * <p><b>解决的问题</b>：多数 hook 在 hook 体里每次调 {@code AppConfig.get()}，
     * 天然热生效；但 {@link QuickSettingsHooks} 这类把配置<b>一次性拷进静态字段</b>
     * （{@code loadParams()} → {@code sColumns} 等）的实现，会与 remote 脱钩 ——
     * 配置推到了，可没人去读那个静态字段，于是「改了设置没反应」。</p>
     *
     * <p><b>为什么不靠热重载</b>：remote prefs 的 {@code getXxx} 只是
     * {@code HashMap.getOrDefault}，纯内存、无 binder/IO，单次 26 次哈希查找
     * 在 MT8788 上是微秒级；而热重载要重载 dex + 重跑 onPackageReady + 重装
     * 全部 hook，成本高几个数量级，且 system_server 根本不支持。</p>
     *
     * <p><b>保活</b>：订阅者存强引用（用 IdentityHashMap 避免 equals 意外命中），
     * 且 {@link #notifyConfigChanged} 内部吞掉所有异常 —— 订阅者抛异常绝不能
     * 反过来打断 listener 主流程（否则其余订阅者都收不到通知）。</p>
     */
    public interface ConfigListener {
        /** @param key 变更的键名 */
        void onConfigChanged(String key);
    }

    /** 订阅者集合：强引用保活（踩过 lambda 被 GC 导致 hook 静默失效的坑） */
    private static final Map<ConfigListener, String[]> sListeners =
            Collections.synchronizedMap(new IdentityHashMap<ConfigListener, String[]>());

    /**
     * 订阅配置变更。
     *
     * @param listener 回调
     * @param keys     关心的键名；传 null 表示关心全部
     */
    public static void addConfigListener(ConfigListener listener, String... keys) {
        if (listener == null) {
            return;
        }
        sListeners.put(listener, keys == null ? null : keys.clone());
        DebugProbe.log("config listener registered, keys="
                + (keys == null ? "*" : Arrays.toString(keys)));
    }

    /** 取消订阅 */
    public static void removeConfigListener(ConfigListener listener) {
        sListeners.remove(listener);
    }

    /** 分发变更通知（由 sListener 调用） */
    private static void notifyConfigChanged(String key) {
        if (sListeners.isEmpty()) {
            return;
        }
        List<Map.Entry<ConfigListener, String[]>> snapshot;
        synchronized (sListeners) {
            snapshot = new ArrayList<>(sListeners.entrySet());
        }
        for (Map.Entry<ConfigListener, String[]> e : snapshot) {
            try {
                String[] keys = e.getValue();
                boolean hit = (keys == null);
                if (!hit) {
                    for (String k : keys) {
                        if (k.equals(key)) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (hit) {
                    e.getKey().onConfigChanged(key);
                }
            } catch (Throwable t) {
                // 单个订阅者异常绝不影响其余订阅者与 listener 主流程
                DebugProbe.log("config listener error on " + key + ": " + t);
            }
        }
    }

    /** remote 通道来源：未初始化 / hook 侧只读 / UI 侧可写 */
    private static final int SRC_NONE = 0, SRC_HOOK = 1, SRC_UI = 2;
    private static volatile int sRemoteSource = SRC_NONE;

    /**
     * UI 侧持有 XposedService 引用：写入后据此重建 RemotePreferences 实例，
     * 绕开「本地 mMap 不刷新」问题（见 refreshRemoteAfterWrite）。
     */
    private static volatile XposedService sUiService;

    /**
     * ★★★ RemotePreferences 的本地缓存陷阱（2026-10 实测定位）
     *
     * <p>反编译 service-102 的 {@code RemotePreferences}：</p>
     * <ul>
     *   <li>{@code getString/getInt/...} 只读 {@code private volatile Map mMap}
     *       —— <b>纯内存缓存，不回源 daemon</b>；</li>
     *   <li>{@code Editor.commit()} → {@code doCommit(bundle)} 走 binder 写 daemon，
     *       返回值反映的是 <b>daemon 侧是否接受</b>；</li>
     *   <li>{@code mMap} 只在 <b>daemon 主动推送</b>（{@code onServiceChanged}）时刷新。</li>
     * </ul>
     *
     * <p>于是 UI 进程「写完立刻读」必然读到旧值：自检实测
     * {@code REMOTE SELFTEST write=true readback=false}。
     * 表现就是用户看到的：<b>开关能切、页面显示新值、切走再回来值没了、
     * 重启 App 又变原样</b>（重启后 daemon 才把新 mMap 推下来）——
     * 正是「未被落盘的虚假状态 + 重启归零」。</p>
     *
     * <p>修法：UI 侧每次写入成功后，主动丢弃旧实例并重新
     * {@code getRemotePreferences(GROUP)} 取一份带最新 mMap 的新实例。</p>
     */
    private static SharedPreferences refreshRemoteAfterWrite() {
        try {
            XposedService svc = sUiService;
            if (svc == null) {
                return sRemote;
            }
            SharedPreferences fresh = svc.getRemotePreferences(GROUP);
            if (fresh != null) {
                sRemote = fresh;
                try {
                    fresh.registerOnSharedPreferenceChangeListener(sListener);
                } catch (Throwable ignored) {
                }
            }
            return sRemote;
        } catch (Throwable t) {
            DebugProbe.log("refreshRemoteAfterWrite failed: " + t);
            return sRemote;
        }
    }

    /** 静态强引用 listener：推送日志 + logLevel 实时刷新 */
    private static final SharedPreferences.OnSharedPreferenceChangeListener sListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (key == null) {
                        return;
                    }
                    FileLogger.log(Log.INFO, "cfg", "remote config changed: " + key);
                    if (K_LOG_LEVEL.equals(key)) {
                        applyLogLevel(sp);
                    }
                    // ★ 通知订阅者（如 QuickSettingsHooks 需要在参数变更时
                    //   重新 loadParams()，否则静态字段与 remote 脱钩）
                    notifyConfigChanged(key);
                }
            };

    /** 把 logLevel 同步给 FileLogger（审查结论：logLevel 此前是死配置） */
    private static void applyLogLevel(SharedPreferences p) {
        try {
            FileLogger.setMinLevel(clampLevel(p.getInt(K_LOG_LEVEL, Log.INFO)));
        } catch (Throwable ignored) {
        }
    }

    /**
     * remote 通道失效（daemon/binder 死亡）：丢弃引用，降级文件模式；
     * 允许后续重绑定时重新初始化（审查低风险备忘 2）。
     */
    public static void onRemoteDied() {
        synchronized (AppConfig.class) {
            if (sRemoteSource != SRC_NONE) {
                DebugProbe.log("remote channel died, fallback to file cfg");
            }
            sRemote = null;
            sRemoteSource = SRC_NONE;
            sRemoteTried = false;
            sFileCache = null;
        }
    }

    /**
     * 配置迁移（UI 侧写通道执行一次）：按 version 链式升级。
     * 通用框架：future v2→v3 只需在 MAPPERS 追加新的 ConfigMapper 实现。
     */
    private static void migrateToLatest(SharedPreferences p) {
        try {
            if (p.getInt(K_VERSION, 0) >= VERSION) {
                return;
            }
            synchronized (AppConfig.class) {
                if (p.getInt(K_VERSION, 0) >= VERSION) {
                    return;
                }
                Properties legacy = null;
                try {
                    legacy = loadProps();
                } catch (Throwable ignored) {
                }
                int cur = p.getInt(K_VERSION, 0);
                for (ConfigMapper m : MAPPERS) {
                    if (cur == m.from() && m.to() > cur) {
                        SharedPreferences.Editor e = p.edit();
                        m.migrate(p, legacy, e);
                        e.putInt(K_VERSION, m.to());
                        try { if (!e.commit()) DebugProbe.log("migrate commit failed"); } catch (Throwable t) { DebugProbe.log("migrate failed: " + t); }
                        cur = m.to();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 配置迁移器：FROM → TO 的键重映射（链式执行） */
    interface ConfigMapper {
        int from();

        int to();

        void migrate(SharedPreferences p, Properties legacy, SharedPreferences.Editor e);
    }

    private static final ConfigMapper[] MAPPERS = {
            new V1ToV2Mapper(),
    };

    /** v1 旧扁平键（K_MOD_xxx、K_MASTER …）迁移为 v2 per-scope hook 单元键 */
    static final class V1ToV2Mapper implements ConfigMapper {
        @Override
        public int from() {
            return 0; // 第一代配置无 version 字段
        }

        @Override
        public int to() {
            return VERSION;
        }

        @Override
        public void migrate(SharedPreferences p, Properties legacy,
                            SharedPreferences.Editor e) {
            e.putBoolean(hookKey(SCOPE_SYSTEM, H_UNLOCK_CTRL), v(p, legacy, K_ZEUS, true));
            e.putBoolean(hookKey(SCOPE_ZEUS, H_UNLOCK_CTRL), v(p, legacy, K_ZEUS, true));
            e.putBoolean(hookKey(SCOPE_ZEUS, H_SPOOF_DEVICE), v(p, legacy, K_ZEUS, true));
            e.putBoolean(hookKey(SCOPE_SETTINGS, H_SETTINGS_UNLOCK), v(p, legacy, K_SETTINGS, true));
            e.putBoolean(hookKey(SCOPE_ANDROID, H_SHARE_CHOOSER), v(p, legacy, K_CHOOSER, true));
            e.putBoolean(hookKey(SCOPE_LAUNCHER, H_RECENT_TASKS), v(p, legacy, K_LAUNCHER, true));
            e.putBoolean(hookKey(SCOPE_SYSTEM, H_HOME_UNLOCK), v(p, legacy, K_HOME, true));
            e.putBoolean(hookKey(SCOPE_LAUNCHER, H_HOME_UNLOCK), v(p, legacy, K_HOME, true));
            e.putBoolean(hookKey(SCOPE_GALLERY, H_GALLERY_EDIT), v(p, legacy, K_GALLERY, true));
            e.putBoolean(hookKey(SCOPE_SYSTEM, H_IME_GUARD), v(p, legacy, K_MOD_INPUT_METHOD, false));
            e.putBoolean(hookKey(SCOPE_PACKAGE_INSTALLER, H_INSTALL_UNLOCK),
                    v(p, legacy, K_MOD_PACKAGE_INSTALL, true));
            // 激进拦截默认关闭：只有显式开启过才迁移
            if (v(p, legacy, K_AGGRESSIVE, false)) {
                e.putBoolean(hookKey(SCOPE_SYSTEM, H_DESKTOP_PROTECT), true);
            }
            // master / 日志 / 输入法参数 / zeus 型号SN 键名保留不变（K_LOG_* 等）
        }

        private boolean v(SharedPreferences p, Properties legacy, String key, boolean def) {
            if (p.contains(key)) {
                return p.getBoolean(key, def);
            }
            if (legacy != null) {
                String s = legacy.getProperty(key);
                if (s != null) {
                    return "true".equalsIgnoreCase(s.trim());
                }
            }
            return def;
        }
    }

    /* ==================== 读取 ==================== */

    /** 取当前配置（内存/零 IO），各进程热路径安全 */
    public static AppConfig get() {
        if (sRemote != null) {
            return readFromRemote();
        }
        AppConfig c = sFileCache;
        long now = SystemClock.elapsedRealtime();
        if (c == null || now - sFileLoadedAt >= TTL_MS) {
            c = refresh();
        }
        return c;
    }

    /** 强制重读 */
    /**
     * 读取当前配置。
     *
     * <p>★ 2026-10 根因修正：UI 进程<b>不能</b>用 remote preferences。<br>
     * 实测（UI 进程 logcat 自检）：<br>
     * {@code REMOTE SELFTEST EXCEPTION: java.lang.NoClassDefFoundError:
     * Failed resolution of: Lio/github/libxposed/api/XposedModule;}<br>
     * 原因：{@code api-102.0.0.jar} 在 build.gradle 是 {@code compileOnly}
     * （官方要求，api 类由框架在 <b>hook 进程</b>注入），APK 里根本没有这些类
     * （实测 APK 中 {@code Lio/github/libxposed/api/XposedModule} 命中 0）；
     * 而模块 App 自己的 UI 进程不被 hook → 拿不到 api 类 →
     * {@code RemotePreferences} 内部引用它时必抛 NoClassDefFoundError。
     * <b>所以「XposedService BOUND + initRemote OK」是假象，写入会立刻炸。</b></p>
     *
     * <p>现策略按进程分道：</p>
     * <ul>
     *   <li><b>UI 进程</b>：一律走 App 私有 filesDir 文件（不需 su、必定落盘）；
     *       写完用 root 尽力同步一份到 {@link #PATH} 供 hook 进程读。</li>
     *   <li><b>hook 进程</b>：remote preferences 可用（api 类已注入），
     *       优先读 remote；读不到再读 {@link #PATH}。</li>
     * </ul>
     */
    public static synchronized AppConfig refresh() {
        // ★ remote 优先（UI 与 hook 进程都一样）。
        //   实测（2026-10 真机）：UI 侧 RemotePreferences 写入+读回均正常
        //   （SELFTEST write=true getString=true contains=true），
        //   因此它是唯一真源，private 文件只是 remote 未就绪时的兜底。
        if (sRemote != null) {
            try {
                return readFromRemote();
            } catch (Throwable t) {
                DebugProbe.log("refresh: readFromRemote failed -> " + t);
            }
        }
        // 兜底：UI 进程读自己的私有文件；hook 进程读 root 写的共享文件
        AppConfig c = readFromFile();
        sFileCache = c;
        sFileLoadedAt = SystemClock.elapsedRealtime();
        return c;
    }

    /** 当前进程是否是模块 App 自己的 UI 进程（sUiFile 已设置即视为 UI 进程） */
    private static boolean isUiProcess() {
        return sUiFile != null;
    }

    private static AppConfig readFromRemote() {
        SharedPreferences p = sRemote;
        AppConfig c = new AppConfig();
        try {
            c.master = p.getBoolean(K_MASTER, true);
            // system
            c.hSystemUnlockControl = p.getBoolean(hookKey(SCOPE_SYSTEM, H_UNLOCK_CTRL), true);
            c.hSystemHomeUnlock = p.getBoolean(hookKey(SCOPE_SYSTEM, H_HOME_UNLOCK), true);
            c.hSystemImeGuard = p.getBoolean(hookKey(SCOPE_SYSTEM, H_IME_GUARD), false);
            c.hSystemDesktopProtect = p.getBoolean(hookKey(SCOPE_SYSTEM, H_DESKTOP_PROTECT), false);
            c.hSystemUsbAuth = p.getBoolean(hookKey(SCOPE_SYSTEM, H_USB_AUTH), true);
            c.hSystemUsbDiag = p.getBoolean(hookKey(SCOPE_SYSTEM, H_USB_AUTH_DIAG), false);
            // android
            c.hAndroidShareChooser = p.getBoolean(hookKey(SCOPE_ANDROID, H_SHARE_CHOOSER), true);
            // zeus
            c.hZeusUnlockControl = p.getBoolean(hookKey(SCOPE_ZEUS, H_UNLOCK_CTRL), true);
            c.hZeusSpoofDevice = p.getBoolean(hookKey(SCOPE_ZEUS, H_SPOOF_DEVICE), true);
            // settings
            c.hSettingsUnlock = p.getBoolean(hookKey(SCOPE_SETTINGS, H_SETTINGS_UNLOCK), true);
            c.hSettingsLockUnlock = p.getBoolean(hookKey(SCOPE_SETTINGS, H_LOCK_UNLOCK), true);
            // launcher
            c.hLauncherRecentTasks = p.getBoolean(hookKey(SCOPE_LAUNCHER, H_RECENT_TASKS), true);
            c.hLauncherHomeUnlock = p.getBoolean(hookKey(SCOPE_LAUNCHER, H_HOME_UNLOCK), true);
            // gallery
            c.hGalleryEdit = p.getBoolean(hookKey(SCOPE_GALLERY, H_GALLERY_EDIT), true);
            // packageinstaller
            c.hInstallUnlock = p.getBoolean(hookKey(SCOPE_PACKAGE_INSTALLER, H_INSTALL_UNLOCK), true);
            // systemui
            c.hSystemuiQsFix = p.getBoolean(hookKey(SCOPE_SYSTEMUI, H_QS_FIX), true);
            c.logLevel = clampLevel(p.getInt(K_LOG_LEVEL, Log.INFO));
            c.inputMethodMode = clampInputMethodMode(p.getInt(K_INPUT_METHOD_MODE, 0));
            c.inputMethodList = p.getString(K_INPUT_METHOD_LIST, "");
            c.zeusModel = p.getString(K_ZEUS_MODEL, "");
            c.zeusSn = p.getString(K_ZEUS_SN, "");
            c.qsColumns = clampQsColumns(p.getInt(K_QS_COLUMNS, 5));
            c.qsMaxRows = clampQsMaxRows(p.getInt(K_QS_MAX_ROWS, 2));
            c.qsFooterFg = p.getInt(K_QS_FOOTER_FG, 0xFFFFFFFF);
            c.qsTileTextDark = p.getInt(K_QS_TILE_TEXT_DARK, 0xFF212121);
        } catch (Throwable t) {
            return readFromFile();
        }
        return c;
    }

    private static int clampLevel(int lv) {
        return (lv < Log.VERBOSE || lv > Log.ERROR) ? Log.INFO : lv;
    }

    private static int clampInputMethodMode(int mode) {
        return (mode < 0 || mode > 1) ? 0 : mode;
    }
    private static int clampQsColumns(int v) {
        if (v < 1) return 1;
        if (v > 6) return 6;
        return v;
    }

    private static int clampQsMaxRows(int v) {
        if (v < 1) return 1;
        if (v > 3) return 3;
        return v;
    }


    /* ==================== 写入（UI 侧） ==================== */

    /** UI 侧：写布尔项。返回 false 表示两个通道都失败 */
    public static synchronized boolean setBoolean(String key, boolean value) {
        SharedPreferences p = sRemote;
        if (p != null) {
            try {
                // ★ commit() 返回值必须检查：apply() 是异步的、失败无感知，
                //   直接 return true 会让 UI「显示保存成功」而配置根本没落盘
                //   （新机实测：remote 通道未就绪时所有开关静默丢失）。
                //   检查失败则继续走 legacy 降级，最后再失败才 return false。
                if (p.edit().putBoolean(key, value).commit()) {
                    // ★ 必须重建：RemotePreferences.mMap 不会因本地写入而刷新，
                    //   不重建的话紧接着的 refresh() 会读到旧值 → UI 显示"没保存"
                    if (sRemoteSource == SRC_UI) {
                        refreshRemoteAfterWrite();
                    }
                    DebugProbe.log("setBoolean OK (remote) " + key + "=" + value);
                    return true;
                }
                DebugProbe.log("setBoolean commit failed, fallback legacy: " + key);
            } catch (Throwable t) {
                DebugProbe.log("setBoolean remote failed: " + key + " -> " + t);
            }
        }
        if (!writeLegacy(key, String.valueOf(value))) {
            // 两条通道都失败：UI 会弹 Toast，但这里必须留痕，
            // 否则「为什么保存不了」永远查不到（UI 进程 api.log 通道失效）
            DebugProbe.log("setBoolean WRITE FAILED key=" + key
                    + " value=" + value + " (remote=" + (sRemote != null) + ")");
            return false;
        }
        return true;
    }

    public static synchronized boolean setInt(String key, int value) {
        SharedPreferences p = sRemote;
        if (p != null) {
            try {
                if (p.edit().putInt(key, value).commit()) {
                    // ★ 必须重建：RemotePreferences.mMap 不会因本地写入而刷新，
                    //   不重建的话紧接着的 refresh() 会读到旧值 → UI 显示"没保存"
                    if (sRemoteSource == SRC_UI) {
                        refreshRemoteAfterWrite();
                    }
                    return true;
                }
                DebugProbe.log("setInt commit failed, fallback legacy: " + key);
            } catch (Throwable t) {
                DebugProbe.log("setInt remote failed: " + key + " -> " + t);
            }
        }
        if (!writeLegacy(key, String.valueOf(value))) {
            DebugProbe.log("setInt WRITE FAILED key=" + key + " value=" + value);
            return false;
        }
        return true;
    }

    public static synchronized boolean setString(String key, String value) {
        SharedPreferences p = sRemote;
        if (p != null) {
            try {
                if (p.edit().putString(key, value).commit()) {
                    // ★ 必须重建：RemotePreferences.mMap 不会因本地写入而刷新，
                    //   不重建的话紧接着的 refresh() 会读到旧值 → UI 显示"没保存"
                    if (sRemoteSource == SRC_UI) {
                        refreshRemoteAfterWrite();
                    }
                    return true;
                }
                DebugProbe.log("setString commit failed, fallback legacy: " + key);
            } catch (Throwable t) {
                DebugProbe.log("setString remote failed: " + key + " -> " + t);
            }
        }
        if (!writeLegacy(key, value)) {
            DebugProbe.log("setString WRITE FAILED key=" + key + " value=" + value);
            return false;
        }
        return true;
    }

    /** 是否走 remote 通道（UI 用于判断"能否保存"） */
    public static boolean isRemote() {
        return sRemote != null;
    }

    /** 诊断：remote 通道真实读写探测（负数=通道未建立）。会触发一次跨进程 getAll */
    public static int debugDumpRemote() {
        SharedPreferences p = sRemote;
        if (p == null) {
            return -1;
        }
        try {
            return p.getAll().size();
        } catch (Throwable t) {
            DebugProbe.log("remote getAll FAILED: " + t);
            return -2;
        }
    }

    /** UI 侧：取当前被 hook 的目标进程名列表（实时 binder 查询；失败返回空表） */
    public static java.util.List<String> runningTargets() {
        XposedService svc = XposedServiceHolder.get();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (svc == null) {
            return out;
        }
        try {
            for (io.github.libxposed.service.HookedTarget t : svc.getRunningTargets()) {
                out.add(t.getProcessName());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 是否存在任何已 hook 目标（chooser 等"任意进程"模块用） */
    public static boolean hasAnyTarget() {
        return !runningTargets().isEmpty();
    }

    /* ==================== 旧文件通道（降级 + 迁移源） ==================== */

    private static AppConfig readFromFile() {
        AppConfig c = new AppConfig();
        try {
            // UI 进程：先私有文件，没有则回落共享文件（避免"读到空 → 全默认值"）
            Properties p = null;
            if (isUiProcess() && sUiFile != null) {
                p = loadProps(sUiFile);
                if (p.isEmpty()) {
                    p = loadProps(new File(PATH));
                }
            } else {
                p = loadProps(new File(PATH));
            }
            c.master = getP(p, K_MASTER, true);
            c.hSystemUnlockControl = getP(p, hookKey(SCOPE_SYSTEM, H_UNLOCK_CTRL), true);
            c.hSystemHomeUnlock = getP(p, hookKey(SCOPE_SYSTEM, H_HOME_UNLOCK), true);
            c.hSystemImeGuard = getP(p, hookKey(SCOPE_SYSTEM, H_IME_GUARD), false);
            c.hSystemDesktopProtect = getP(p, hookKey(SCOPE_SYSTEM, H_DESKTOP_PROTECT), false);
            c.hSystemUsbAuth = getP(p, hookKey(SCOPE_SYSTEM, H_USB_AUTH), true);
            c.hSystemUsbDiag = getP(p, hookKey(SCOPE_SYSTEM, H_USB_AUTH_DIAG), false);
            c.hAndroidShareChooser = getP(p, hookKey(SCOPE_ANDROID, H_SHARE_CHOOSER), true);
            c.hZeusUnlockControl = getP(p, hookKey(SCOPE_ZEUS, H_UNLOCK_CTRL), true);
            c.hZeusSpoofDevice = getP(p, hookKey(SCOPE_ZEUS, H_SPOOF_DEVICE), true);
            c.hSettingsUnlock = getP(p, hookKey(SCOPE_SETTINGS, H_SETTINGS_UNLOCK), true);
            c.hSettingsLockUnlock = getP(p, hookKey(SCOPE_SETTINGS, H_LOCK_UNLOCK), true);
            c.hLauncherRecentTasks = getP(p, hookKey(SCOPE_LAUNCHER, H_RECENT_TASKS), true);
            c.hLauncherHomeUnlock = getP(p, hookKey(SCOPE_LAUNCHER, H_HOME_UNLOCK), true);
            c.hGalleryEdit = getP(p, hookKey(SCOPE_GALLERY, H_GALLERY_EDIT), true);
            c.hInstallUnlock = getP(p, hookKey(SCOPE_PACKAGE_INSTALLER, H_INSTALL_UNLOCK), true);
            c.hSystemuiQsFix = getP(p, hookKey(SCOPE_SYSTEMUI, H_QS_FIX), true);
            c.logLevel = clampLevel(getI(p, K_LOG_LEVEL, Log.INFO));
            c.inputMethodMode = clampInputMethodMode(getI(p, K_INPUT_METHOD_MODE, 0));
            c.inputMethodList = p.getProperty(K_INPUT_METHOD_LIST, "");
            c.zeusModel = p.getProperty(K_ZEUS_MODEL, "");
            c.zeusSn = p.getProperty(K_ZEUS_SN, "");
            c.qsColumns = clampQsColumns(getI(p, K_QS_COLUMNS, 5));
            c.qsMaxRows = clampQsMaxRows(getI(p, K_QS_MAX_ROWS, 2));
            c.qsFooterFg = getI(p, K_QS_FOOTER_FG, 0xFFFFFFFF);
            c.qsTileTextDark = getI(p, K_QS_TILE_TEXT_DARK, 0xFF212121);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static Properties loadProps() throws Exception {
        return loadProps(new File(PATH));
    }

    /** 从指定文件读 Properties（不存在/读不到返回空 Properties，不抛） */
    private static Properties loadProps(File f) {
        Properties p = new Properties();
        if (f == null || !f.exists()) {
            return p;
        }
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), "UTF-8");
            p.load(r);
            return p;
        } catch (Throwable ignored) {
            return p;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }


    private static boolean writeLegacy(String key, String value) {
        Properties p;
        try {
            p = loadProps();
        } catch (Throwable t) {
            p = new Properties();
        }
        p.setProperty(key, value);
        if (writePropsDirect(p)) {
            return true;
        }
        // ↓ direct 失败原因留痕：否则 UI 进程里看不到任何线索
        DebugProbe.log("writeLegacy: direct write to " + PATH + " failed"
                + " (app uid 无写权限，/data/local/tmp 是 0771 shell:shell)");
        // /data/local/tmp 是 0771 shell:shell —— 普通应用只有 x（可穿越不可写），
        // 直写必然失败。但它对本模块的 hook 进程（uid 1000）可读，所以
        // 「root 写 + chmod 666」是一条真实可用的降级路径：daemon service
        // 不可用时（实测：模块更新后 app 进程可能永久错过 binder 送达），
        // 配置仍能保存下来，不至于让用户所有开关都存不进去。
        return writePropsViaRoot(p);
    }

    /**
     * 直写 legacy 文件。
     *
     * <p>顺序：① UI 私有 filesDir（App 自有权限，必定成功）
     * ② /data/local/tmp（普通 app 无写权限，基本必失败，仅在 root 下碰运气）</p>
     */
    private static boolean writePropsDirect(Properties p) {
        // ① UI 私有文件：不需要 su，这是 UI 侧真正可靠的落地点
        java.io.File ui = sUiFile;
        if (ui != null) {
            if (writePropsTo(ui, p)) {
                // 顺带用 root 同步一份给 hook 进程读（失败不影响 UI 自身已落盘）
                syncToHookPathViaRoot(p);
                return true;
            }
            DebugProbe.log("writePropsDirect: UI private file write failed: " + ui);
        }
        try {
            File f = new File(PATH);
            Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                p.store(w, "XDFHook config (legacy fallback)");
            } finally {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                android.system.Os.chmod(PATH, 0666);
            } catch (Throwable ignored) {
            }
            sFileCache = readFromFile();
            sFileLoadedAt = SystemClock.elapsedRealtime();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把 Properties 写入指定文件（不做 chmod，不动缓存） */
    private static boolean writePropsTo(File f, Properties p) {
        try {
            java.io.File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                p.store(w, "XDFHook config");
            } finally {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** UI 写完后尽力用 root 同步一份到 PATH，供 hook 进程读取 */
    private static void syncToHookPathViaRoot(Properties p) {
        try {
            String proc = currentProcessName();
            if (proc == null || !(proc.equals("cn.cf3012.xdf")
                    || proc.startsWith("cn.cf3012.xdf:"))) {
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            p.store(bos, "XDFHook config");
            String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                    android.util.Base64.NO_WRAP);
            if (Root.exec("echo \"" + b64 + "\" | base64 -d > " + PATH
                    + " && chmod 666 " + PATH)) {
                DebugProbe.log("synced to hook path " + PATH);
            } else {
                DebugProbe.log("sync to hook path failed (hook 侧将读到旧值，"
                        + "但 UI 侧状态已保住)");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前进程名（ActivityThread.currentProcessName 是 @hide，反射取） */
    private static String currentProcessName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getDeclaredMethod("currentProcessName");
            m.setAccessible(true);
            Object v = m.invoke(null);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 经 root 写 legacy 配置（仅限本模块 UI 进程调用）。
     * 内容走 base64 传输，避免任何 shell/Properties 转义问题。
     */
    private static boolean writePropsViaRoot(Properties p) {        try {
            // 只在模块自己的 UI 进程里做（hook 进程绝不能 fork su）
            String proc = currentProcessName();
            // 进程名判定放宽：带子进程后缀（如 cn.cf3012.xdf:ui）也算 UI 进程。
            // 原判定要求完全相等，一旦 UI 被拉起子进程（读写配置常在子进程跑）
            // 就会直接 return false —— 表现为「所有开关都存不进去」。
            if (proc == null || !(proc.equals("cn.cf3012.xdf")
                    || proc.startsWith("cn.cf3012.xdf:"))) {
                DebugProbe.log("writePropsViaRoot skipped: not UI process, proc=" + proc);
                return false;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            p.store(bos, "XDFHook config (legacy fallback, via root)");
            String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                    android.util.Base64.NO_WRAP);
            // 用双引号而非单引号：base64 可能含 '+' '/'(在双引号内安全)，
            // 单引号在部分 su 实现下会被错误转义导致内容损坏。
            String cmd = "echo \"" + b64 + "\" | base64 -d > " + PATH
                    + " && chmod 666 " + PATH;
            if (!Root.exec(cmd)) {
                DebugProbe.log("writePropsViaRoot: Root.exec failed"
                        + " (su 未授权 / 无 root / 超时)");
                return false;
            }
            sFileCache = readFromFile();
            sFileLoadedAt = SystemClock.elapsedRealtime();
            return true;
        } catch (Throwable t) {
            DebugProbe.log("writePropsViaRoot: exception " + t);
            return false;
        }
    }

    private static boolean getP(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : "true".equalsIgnoreCase(v.trim());
    }

    private static int getI(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 查询 scope 上是否启用某 hook 单元（UI/各进程通用） */
    public boolean hookEnabled(String scope, String hook) {
        String k = hookKey(scope, hook);
        if (SCOPE_SYSTEM.equals(scope)) {
            if (H_UNLOCK_CTRL.equals(hook)) return hSystemUnlockControl;
            if (H_HOME_UNLOCK.equals(hook)) return hSystemHomeUnlock;
            if (H_IME_GUARD.equals(hook)) return hSystemImeGuard;
            if (H_DESKTOP_PROTECT.equals(hook)) return hSystemDesktopProtect;
            if (H_USB_AUTH.equals(hook)) return hSystemUsbAuth;
            if (H_USB_AUTH_DIAG.equals(hook)) return hSystemUsbDiag;
        } else if (SCOPE_ANDROID.equals(scope)) {
            if (H_SHARE_CHOOSER.equals(hook)) return hAndroidShareChooser;
        } else if (SCOPE_ZEUS.equals(scope)) {
            if (H_UNLOCK_CTRL.equals(hook)) return hZeusUnlockControl;
            if (H_SPOOF_DEVICE.equals(hook)) return hZeusSpoofDevice;
        } else if (SCOPE_SETTINGS.equals(scope)) {
            if (H_SETTINGS_UNLOCK.equals(hook)) return hSettingsUnlock;
            if (H_LOCK_UNLOCK.equals(hook)) return hSettingsLockUnlock;
        } else if (SCOPE_LAUNCHER.equals(scope)) {
            if (H_RECENT_TASKS.equals(hook)) return hLauncherRecentTasks;
            if (H_HOME_UNLOCK.equals(hook)) return hLauncherHomeUnlock;
        } else if (SCOPE_GALLERY.equals(scope)) {
            if (H_GALLERY_EDIT.equals(hook)) return hGalleryEdit;
        } else if (SCOPE_PACKAGE_INSTALLER.equals(scope)) {
            if (H_INSTALL_UNLOCK.equals(hook)) return hInstallUnlock;
        } else if (SCOPE_SYSTEMUI.equals(scope)) {
            if (H_QS_FIX.equals(hook)) return hSystemuiQsFix;
        }
        return true;
    }

    /** 获取整型配置值（UI 侧使用） */
    public int getInt(String key, int def) {
        switch (key) {
            case K_INPUT_METHOD_MODE:
                return inputMethodMode;
            case K_QS_COLUMNS:
                return qsColumns;
            case K_QS_MAX_ROWS:
                return qsMaxRows;
            case K_QS_FOOTER_FG:
                return qsFooterFg;
            case K_QS_TILE_TEXT_DARK:
                return qsTileTextDark;
            default:
                return def;
        }
    }

    /** 获取字符串配置值（UI 侧使用） */
    public String getString(String key, String def) {
        switch (key) {
            case K_INPUT_METHOD_LIST:
                return inputMethodList;
            default:
                return def;
        }
    }
}
