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

    public static final String PATH = "/data/local/tmp/XDFHook.cfg";

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

    /** Zeus 设备信息冒充：仅影响 zeus 读取到的 model/sn */
    public static final String K_ZEUS_MODEL = "zeus_model";
    public static final String K_ZEUS_SN = "zeus_sn";

    /** 可选的冒充型号；空串表示"未设置（上报真实）" */
    public static final String[] ZEUS_MODELS = {
            "XDF-N2", "XDF-N1", "XDF-N2-L",
            "XDF-X1-S", "XDF-N1-GM", "XDF-N2-GM", "XDF-X1-GM"
    };

    /** 日志落盘配置 */
    public static final String K_LOG_FILE_ENABLED = "log_file_enabled";
    public static final String K_LOG_FILE_PATH = "log_file_path";
    public static final String K_LOG_FILE_CAP_KB = "log_file_cap_kb";

    /** 默认落盘路径与上限（KB） */
    public static final String DEFAULT_LOG_PATH = "/sdcard/Android/XDFHook.log";
    public static final int DEFAULT_LOG_CAP_KB = 1024;
    public static final int MIN_LOG_CAP_KB = 1;
    public static final int MAX_LOG_CAP_KB = 102400;

    public boolean master = true;
    public boolean modZeus = true;
    public boolean modSettings = true;
    public boolean modChooser = true;
    public boolean modLauncher = true;
    public boolean modGallery = true;
    public boolean modHome = true;
    public boolean modInputMethod = true;
    /** HOME 解锁探针的激进拦截：拦截一切指向 XDF 桌面的 preferred 写入 */
    public boolean pmsAggressive = false;
    public int logLevel = Log.INFO;
    public int inputMethodMode = 0; // 0=固化, 1=黑名单
    public String inputMethodList = "";
    /** Zeus 设备信息冒充；空串 = 未设置（上报真实） */
    public String zeusModel = "";
    public String zeusSn = "";
    /** 日志落盘：开关 / 路径 / 上限(KB) */
    public boolean logFileEnabled = true;
    public String logFilePath = DEFAULT_LOG_PATH;
    public int logFileCapKb = DEFAULT_LOG_CAP_KB;

    /** 底层通道：null = 未初始化或降级到文件 */
    private static volatile SharedPreferences sRemote;
    private static volatile boolean sRemoteTried;
    /** 旧配置是否已迁移到 remote */
    /** 旧配置是否已迁移到 remote */
    private static volatile boolean sMigrated;
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
                // fork 的 hook 侧 RemotePreferences.edit() 直接抛 UOE（只读），
                // 一次性迁移只有 UI 侧能写——hook 侧跳过（审查结论 6）
                if (api == null) {
                    migrateFromLegacyFileIfNeeded(p);
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

    /** remote 通道来源：未初始化 / hook 侧只读 / UI 侧可写 */
    private static final int SRC_NONE = 0, SRC_HOOK = 1, SRC_UI = 2;
    private static volatile int sRemoteSource = SRC_NONE;

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

    /** remote 配置为空且旧 cfg 文件有内容时，做一次导入（按正确类型写入） */
    private static void migrateFromLegacyFileIfNeeded(SharedPreferences p) {
        if (sMigrated) {
            return;
        }
        sMigrated = true;
        try {
            if (!p.getAll().isEmpty()) {
                return; // remote 已有配置，无需迁移
            }
            Properties legacy;
            try {
                legacy = loadProps();
            } catch (Throwable t) {
                return; // 旧文件不存在/不可读
            }
            SharedPreferences.Editor e = p.edit();
            for (String k : new String[]{K_MASTER, K_ZEUS, K_SETTINGS, K_CHOOSER,
                    K_LAUNCHER, K_GALLERY, K_HOME, K_AGGRESSIVE}) {
                String v = legacy.getProperty(k);
                if (v != null) {
                    // 必须写 Boolean 类型：读取端 getBoolean 直接类型强转
                    e.putBoolean(k, "true".equalsIgnoreCase(v.trim()));
                }
            }
            String lv = legacy.getProperty(K_LOG_LEVEL);
            if (lv != null) {
                try {
                    e.putInt(K_LOG_LEVEL, clampLevel(Integer.parseInt(lv.trim())));
                } catch (NumberFormatException ignored) {
                }
            }
            e.apply();
        } catch (Throwable ignored) {
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
    public static synchronized AppConfig refresh() {
        if (sRemote != null) {
            return readFromRemote();
        }
        AppConfig c = readFromFile();
        sFileCache = c;
        sFileLoadedAt = SystemClock.elapsedRealtime();
        return c;
    }

    private static AppConfig readFromRemote() {
        SharedPreferences p = sRemote;
        AppConfig c = new AppConfig();
        try {
            c.master = p.getBoolean(K_MASTER, true);
            c.modZeus = p.getBoolean(K_ZEUS, true);
            c.modSettings = p.getBoolean(K_SETTINGS, true);
            c.modChooser = p.getBoolean(K_CHOOSER, true);
            c.modLauncher = p.getBoolean(K_LAUNCHER, true);
            c.modGallery = p.getBoolean(K_GALLERY, true);
            c.modHome = p.getBoolean(K_HOME, true);
            c.modInputMethod = p.getBoolean(K_MOD_INPUT_METHOD, true);
            c.pmsAggressive = p.getBoolean(K_AGGRESSIVE, false);
            c.logLevel = clampLevel(p.getInt(K_LOG_LEVEL, Log.INFO));
            c.inputMethodMode = clampInputMethodMode(p.getInt(K_INPUT_METHOD_MODE, 0));
            c.inputMethodList = p.getString(K_INPUT_METHOD_LIST, "");
            c.zeusModel = p.getString(K_ZEUS_MODEL, "");
            c.zeusSn = p.getString(K_ZEUS_SN, "");
            c.logFileEnabled = p.getBoolean(K_LOG_FILE_ENABLED, true);
            c.logFilePath = p.getString(K_LOG_FILE_PATH, DEFAULT_LOG_PATH);
            c.logFileCapKb = clampLogCapKb(p.getInt(K_LOG_FILE_CAP_KB, DEFAULT_LOG_CAP_KB));
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

    /** 日志上限(KB)夹紧到合法区间；非法路径也兜底 */
    private static int clampLogCapKb(int kb) {
        if (kb < MIN_LOG_CAP_KB) {
            return MIN_LOG_CAP_KB;
        }
        if (kb > MAX_LOG_CAP_KB) {
            return MAX_LOG_CAP_KB;
        }
        return kb;
    }

    /* ==================== 写入（UI 侧） ==================== */

    /** UI 侧：写布尔项。返回 false 表示两个通道都失败 */
    public static synchronized boolean setBoolean(String key, boolean value) {
        SharedPreferences p = sRemote;
        if (p != null) {
            try {
                p.edit().putBoolean(key, value).apply();
                return true;
            } catch (Throwable ignored) {
            }
        }
        return writeLegacy(key, String.valueOf(value));
    }

    public static synchronized boolean setInt(String key, int value) {
        SharedPreferences p = sRemote;
        if (p != null) {
            try {
                p.edit().putInt(key, value).apply();
                return true;
            } catch (Throwable ignored) {
            }
        }
        return writeLegacy(key, String.valueOf(value));
    }

    public static synchronized boolean setString(String key, String value) {
        SharedPreferences p = sRemote;
        if (p != null) {
            try {
                p.edit().putString(key, value).apply();
                return true;
            } catch (Throwable ignored) {
            }
        }
        return writeLegacy(key, value);
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
            Properties p = loadProps();
            c.master = getP(p, K_MASTER, true);
            c.modZeus = getP(p, K_ZEUS, true);
            c.modSettings = getP(p, K_SETTINGS, true);
            c.modChooser = getP(p, K_CHOOSER, true);
            c.modLauncher = getP(p, K_LAUNCHER, true);
            c.modGallery = getP(p, K_GALLERY, true);
            c.modHome = getP(p, K_HOME, true);
            c.modInputMethod = getP(p, K_MOD_INPUT_METHOD, true);
            c.pmsAggressive = getP(p, K_AGGRESSIVE, false);
            c.logLevel = clampLevel(getI(p, K_LOG_LEVEL, Log.INFO));
            c.inputMethodMode = clampInputMethodMode(getI(p, K_INPUT_METHOD_MODE, 0));
            c.inputMethodList = p.getProperty(K_INPUT_METHOD_LIST, "");
            c.zeusModel = p.getProperty(K_ZEUS_MODEL, "");
            c.zeusSn = p.getProperty(K_ZEUS_SN, "");
            c.logFileEnabled = getP(p, K_LOG_FILE_ENABLED, true);
            c.logFilePath = p.getProperty(K_LOG_FILE_PATH, DEFAULT_LOG_PATH);
            c.logFileCapKb = clampLogCapKb(getI(p, K_LOG_FILE_CAP_KB, DEFAULT_LOG_CAP_KB));
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static Properties loadProps() throws Exception {
        Properties p = new Properties();
        Reader r = new InputStreamReader(new FileInputStream(PATH), "UTF-8");
        try {
            p.load(r);
        } finally {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
        return p;
    }

    private static boolean writeLegacy(String key, String value) {
        try {
            Properties p;
            File f = new File(PATH);
            try {
                p = loadProps();
            } catch (Throwable t) {
                p = new Properties();
            }
            p.setProperty(key, value);
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

    /** 子模块开关查询（按 cfg 键） */
    public boolean enabled(String key) {
        switch (key) {
            case K_MASTER:
                return master;
            case K_ZEUS:
                return modZeus;
            case K_SETTINGS:
                return modSettings;
            case K_CHOOSER:
                return modChooser;
            case K_LAUNCHER:
                return modLauncher;
            case K_GALLERY:
                return modGallery;
            case K_HOME:
                return modHome;
            case K_MOD_INPUT_METHOD:
                return modInputMethod;
            default:
                return true;
        }
    }

    /** 获取整型配置值（UI 侧使用） */
    public int getInt(String key, int def) {
        switch (key) {
            case K_INPUT_METHOD_MODE:
                return inputMethodMode;
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
