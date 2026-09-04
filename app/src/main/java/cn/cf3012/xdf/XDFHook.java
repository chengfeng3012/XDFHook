package cn.cf3012.xdf;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * XDFHook — XDF-N1 学习机 (XDF-N1 / MT8788 / Android 10) 综合解锁模块。
 *
 * 由三个旧模块整合而来（全部以 LibXposed api 102 重写）：
 *   - XdfSettingsHook (v1.8)  → SettingsHooks + UiRestorer + ZeusUnlocker
 *   - XposedModule  (XdfHook) → SystemHooks + LauncherHooks
 *   - GalleryHook             → GalleryHooks
 *
 * 进程分发（onSystemServerStarting / onPackageReady）：
 *   system_server          → ZeusUnlocker（A 组 framework/services 管控放行
 *                                            + C 组 ChooserActivity UI 修复）
 *                            + HomeUnlocker（PMS preferred 写入只读诊断）
 *   cn.xdf.zeus            → ZeusUnlocker（B 组检查链/云控/序列号短路）
 *   com.android.settings   → SettingsHooks（属性放行/开发者选项/更多设置入口）
 *                            + UiRestorer（显示页/声音页/系统页条目还原）
 *   com.android.gallery3d  → GalleryHooks（相册"编辑"按钮恢复）
 *   com.android.launcher3  → LauncherHooks（最近任务隐藏列表解除）
 *                            + HomeUnlocker（block setXdfDefaultHomeLauncher）
 *   其余 scope 内进程      → SystemHooks（ResolverActivity"仅此一次"按钮
 *                                            + ChooserActivity 占位目标兜底）
 *
 * 日志：logcat + /data/local/tmp/XDFHook.log 双写（FileLogger，五级 V/D/I/W/E，
 * 开机超 5MiB 自动清空，权限说明与 magiskpolicy 兜底命令见 FileLogger 头注释）。
 *
 * 注意：LibXposed 新 API 没有 XposedHelpers/XC_MethodHook，
 * 反射统一走 Reflect，hook 统一走本类静态工具（PROTECTIVE 模式，
 * 单个 hook 失败只记日志，绝不影响宿主进程）。
 */
public class XDFHook extends XposedModule {

    public static final String TAG = "XDFHook";

    /* ==================== hook 目标包 ==================== */

    public static final String PKG_SETTINGS = "com.android.settings";
    /** XDF 家长管控 APK */
    public static final String PKG_ZEUS = "cn.xdf.zeus";
    public static final String PKG_LAUNCHER = "com.android.launcher3";
    public static final String PKG_GALLERY = "com.android.gallery3d";

    /** 模块接口实例（XposedModule 实例即 XposedInterface），各子模块静态使用 */
    private static volatile XposedInterface sApi;

    /* ==================== 生命周期 ==================== */

    /**
     * 每个进程最早的生命周期点：模块入口是否被调用的最硬证据。
     */
    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        sApi = this;
        DebugProbe.setProcessName(param.getProcessName());
        // 日志通道先行：进程名 + system_server 判定（system_server 禁广播通道）
        FileLogger.hookInit(param.getProcessName(), param.isSystemServer());
        if (param.isSystemServer()) {
            DebugProbe.setAppDir(new java.io.File("/data/system"));
        }
        DebugProbe.log("onModuleLoaded: entry invoked, process=" + param.getProcessName()
                + ", isSystemServer=" + param.isSystemServer());
        if (isOwnUiProcess(param)) {
            // 模块作用域勾选了自己：UI 进程被 LSPosed 注入（可选路径）。
            // 与 XposedService binder 推送（正常路径）写同一份 daemon 托管存储。
            DebugProbe.log("UI process injected by LSPosed, init remote via hook api");
            AppConfig.hookInit(this);
            int n = AppConfig.debugDumpRemote();
            DebugProbe.log("remote prefs readback: size=" + n
                    + (n < 0 ? " (remote channel FAILED)" : " (remote channel LIVE)"));
        }
    }

    /** 是否为本模块自己的 UI 进程（包名即进程名的主进程） */
    private boolean isOwnUiProcess(ModuleLoadedParam param) {
        try {
            return param.getProcessName().equals(getModuleApplicationInfo().packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 热重载（UI → daemon → 本进程） ==================== */

    /**
     * 热重载第一段：旧一代代码即将被替换。
     * 把需要跨代保留的状态（防重集合、日志缓冲水位）打包下去；
     * hook 全部重装（本模块各 hook 入口自带 ClassLoader/静态防重）。
     */
    /**
     * 热重载：明确拒绝。
     *
     * 审查结论（阻断 B/C/E）：本框架的热重载语义 = unhook 老代 + 新代
     * 仅 attach 不重跑入口，要正确支持必须"补伪 onModuleLoaded 状态 +
     * unhook 老代 handles + 清防重 Set + 重装 hook"三处联动，且其正确性
     * 只能真机（含 system_server）验证。配置变更已由 remote prefs 推送
     * 实时生效，热重载增益极小——拒绝是行为最可预期的选择（拒绝后
     * 老代 hook 原样保留，什么都不变）。
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        logw(TAG, "hot reload refused by module (config pushes live instead)");
        FileLogger.log(Log.WARN, TAG, "hot reload refused (config pushes live instead)");
        return false; // false = 拒绝本次 hot reload，老代 hook 原样保留
    }

    /**
     * system_server 进程入口：boot classloader 下 hook framework.jar
     * （IntentStandardActionManager / ResolverActivity）与 services.jar
     * （XdfManagerService / ZeusManager / AppMetaDataChecker）中的家长管控逻辑。
     */
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        sApi = this;
        DebugProbe.log("onSystemServerStarting: begin");
        // 官方通道初始化必须先于第一条日志/配置读取：
        //   AppConfig.hookInit → remote prefs（配置真源，daemon 中转推送）
        //   失败会静默降级
        AppConfig.hookInit(this);
        DebugProbe.log("onSystemServerStarting: channel init done, master="
                + AppConfig.refresh().master);
        logi(TAG, "loaded into system_server");
        AppConfig cfg = AppConfig.refresh();
        if (!cfg.master) {
            logw(TAG, "master off, skip all system_server hooks");
            return;
        }
        if (cfg.modZeus) {
            try {
                ZeusUnlocker.hookSystemServer(param.getClassLoader());
            } catch (Throwable t) {
                loge(t, TAG, "ZeusUnlocker.systemServer");
            }
        }
        // HOME 解锁：PMS preferred 写入探针（激进拦截开关在探针内部生效）
        if (cfg.modHome) {
            try {
                HomeUnlocker.hookSystemServer(param.getClassLoader());
            } catch (Throwable t) {
                loge(t, TAG, "HomeUnlocker.systemServer");
            }
        } else {
            logi(TAG, "home module disabled, skip probes");
        }
        // 输入法拦截器
        if (cfg.modInputMethod) {
            try {
                InputMethodHooks.hookSystemServer(param.getClassLoader());
            } catch (Throwable t) {
                loge(t, TAG, "InputMethodHooks.systemServer");
            }
        } else {
            logi(TAG, "input method module disabled, skip");
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        sApi = this;
        // 同 system_server：官方通道初始化先于一切日志与配置读取
        AppConfig.hookInit(this);
        final String pkg = param.getPackageName();
        final ClassLoader cl = param.getClassLoader();
        try {
            DebugProbe.setAppDir(new java.io.File(
                    param.getApplicationInfo().dataDir, "files"));
        } catch (Throwable ignored) {
        }
        DebugProbe.log("onPackageReady: begin, pkg=" + pkg);
        logi(TAG, "loaded into " + pkg);

        final AppConfig cfg = AppConfig.refresh();
        if (!cfg.master) {
            logw(TAG, "master off, skip hooks for " + pkg);
            return;
        }

        switch (pkg) {
            case PKG_ZEUS:
                // zeus 进程：短路 zeus 检查链 / 云控 / 序列号（ZeusUnlocker B 组）
                if (!cfg.modZeus) {
                    logi(TAG, "zeus module disabled, skip");
                    break;
                }
                runGuarded("ZeusUnlocker.zeus", new HookInstall() {
                    @Override
                    public void run() throws Exception {
                        ZeusUnlocker.hookZeusProcess(cl);
                    }
                });
                break;
            case PKG_SETTINGS:
                if (!cfg.modSettings) {
                    logi(TAG, "settings module disabled, skip");
                    break;
                }
                // MtkSettings：家长控制属性放行 + 开发者选项 + 更多设置入口
                runGuarded("SettingsHooks", new HookInstall() {
                    @Override
                    public void run() throws Exception {
                        SettingsHooks.hookAll(cl);
                    }
                });
                // 显示页/声音页/系统页被裁条目注入
                runGuarded("UiRestorer", new HookInstall() {
                    @Override
                    public void run() throws Exception {
                        UiRestorer.hookAll(cl);
                    }
                });
                break;
            case PKG_GALLERY:
                if (!cfg.modGallery) {
                    logi(TAG, "gallery module disabled, skip");
                    break;
                }
                // 相册：恢复被裁剪的图片"编辑"按钮
                runGuarded("GalleryHooks", new HookInstall() {
                    @Override
                    public void run() throws Exception {
                        GalleryHooks.hookAll(cl);
                    }
                });
                break;
            case PKG_LAUNCHER:
                // Launcher3：解除最近任务的应用隐藏过滤
                if (cfg.modLauncher) {
                    runGuarded("LauncherHooks", new HookInstall() {
                        @Override
                        public void run() throws Exception {
                            LauncherHooks.hookAll(cl);
                        }
                    });
                }
                // Launcher3：block ROM 的 setXdfDefaultHomeLauncher（HOME 解锁）
                if (cfg.modHome) {
                    runGuarded("HomeUnlocker", new HookInstall() {
                        @Override
                        public void run() throws Exception {
                            HomeUnlocker.hookLauncher(cl);
                        }
                    });
                }
                break;
            default:
                break;
        }

        // ResolverActivity("仅此一次"按钮) / ChooserActivity(分享面板占位目标) 修复：
        // framework 类，scope 内所有进程都会加载到（分享对话框出现在哪个进程就修哪个）。
        if (cfg.modChooser) {
            runGuarded("SystemHooks", new HookInstall() {
                @Override
                public void run() throws Exception {
                    SystemHooks.hookAll(cl);
                }
            });
        }

        // 输入法拦截器：在所有进程中 hook Settings put（确保拦截所有调用）
        if (cfg.modInputMethod) {
            runGuarded("InputMethodHooks.otherProcess", new HookInstall() {
                @Override
                public void run() throws Exception {
                    InputMethodHooks.hookOtherProcess(cl);
                }
            });
        }

    }

    /** 运行一段 hook 安装逻辑，异常只记日志，绝不影响宿主 */
    private void runGuarded(String what, HookInstall action) {
        try {
            action.run();
            DebugProbe.log("installed ok: " + what);
        } catch (Throwable t) {
            DebugProbe.log("installed FAILED: " + what + " -> " + t);
            loge(t, TAG, what);
        }
    }

    private interface HookInstall {
        void run() throws Exception;
    }

    /* ==================== 日志工具（logcat + 落盘双写，见 FileLogger） ==================== */

    public static void logv(String tag, String msg) {
        FileLogger.log(Log.VERBOSE, tag, msg);
    }

    public static void logd(String tag, String msg) {
        FileLogger.log(Log.DEBUG, tag, msg);
    }

    public static void logi(String tag, String msg) {
        FileLogger.log(Log.INFO, tag, msg);
    }

    public static void logw(String tag, String msg) {
        FileLogger.log(Log.WARN, tag, msg);
    }

    public static void loge(Throwable t, String tag, String where) {
        FileLogger.log(Log.ERROR, tag, where + " failed: " + t);
    }

    /* ==================== hook 工具 ==================== */

    /** 注册一个 PROTECTIVE 模式的 hook（interceptor 内异常不外抛到宿主） */
    public static void hook(Executable target, XposedInterface.Hooker hooker) {
        XposedInterface api = sApi;
        if (api == null) {
            return;
        }
        api.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /** hook 类初始化器（clinit 之后执行一段逻辑，如改写静态字段） */
    public static void hookClassInit(Class<?> clazz, XposedInterface.Hooker hooker) {
        XposedInterface api = sApi;
        if (api == null) {
            return;
        }
        api.hookClassInitializer(clazz)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /** 通用 hook 包装：按类名+方法名+参数类型查找并注册 */
    public static void hookMethod(ClassLoader cl, String clsName, String method,
                                  Class<?>[] params, XposedInterface.Hooker hooker)
            throws Exception {
        Class<?> clazz = Class.forName(clsName, false, cl);
        Method m = Reflect.findDeclared(clazz, method, params);
        m.setAccessible(true);
        hook(m, hooker);
    }

    /** 通用 hook 包装：传入 Class 对象 */
    public static void hookMethod(Class<?> clazz, String method,
                                  Class<?>[] params, XposedInterface.Hooker hooker)
            throws Exception {
        Method m = Reflect.findDeclared(clazz, method, params);
        m.setAccessible(true);
        hook(m, hooker);
    }

    /** 容错 hook：类/方法不存在仅记录日志，绝不抛出影响宿主 */
    public static void safeHook(ClassLoader cl, String cls, String method, Class<?>[] params,
                                XposedInterface.Hooker hooker, String what) {
        try {
            hookMethod(cl, cls, method, params, hooker);
            DebugProbe.log("hooked: " + what);
            logi(TAG, "hooked: " + what);
        } catch (Throwable t) {
            DebugProbe.log("NOT FOUND: " + what + " -> " + t);
            logw(TAG, cls + "." + method + " skipped: " + t);
        }
    }

    /**
     * 按方法名 hook 目标类的全部同名重载（沿继承链逐层扫描 declared methods）。
     * 适用于参数签名未知/多重载的目标（如 PMS 的 preferred 写入、ROM 私有方法）。
     *
     * @return 实际成功注册的 hook 数量；为 0 时会明确打出 not found 日志
     *         （避免"静默失败"不可观测的问题）
     */
    public static int hookAllByName(ClassLoader cl, String clsName, String methodName,
                                    XposedInterface.Hooker hooker, String what) {
        int n = 0;
        StringBuilder found = new StringBuilder();
        try {
            Class<?> c = Class.forName(clsName, false, cl);
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (!methodName.equals(m.getName())) {
                        continue;
                    }
                    try {
                        m.setAccessible(true);
                        hook(m, hooker);
                        n++;
                        if (found.length() > 0) {
                            found.append(", ");
                        }
                        found.append(k.getSimpleName()).append('.').append(methodName)
                                .append('(').append(m.getParameterCount()).append(" args)");
                    } catch (Throwable t) {
                        logw(TAG, "hook failed: " + what + " @ " + k.getName() + ": " + t);
                    }
                }
            }
        } catch (Throwable t) {
            DebugProbe.log("NOT FOUND(class): " + what + " (class " + clsName + "): " + t);
            logw(TAG, "not loaded: " + what + " (class " + clsName + "): " + t);
            return 0;
        }
        if (n > 0) {
            DebugProbe.log("hooked x" + n + ": " + what + " [" + found + "]");
            logi(TAG, "hooked: " + what + " x" + n + " [" + found + "]");
        } else {
            DebugProbe.log("NOT FOUND: " + what + " (in " + clsName + " hierarchy)");
            logw(TAG, "not found: " + what + " (in " + clsName + " hierarchy)");
        }
        return n;
    }
}
