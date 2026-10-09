package cn.cf3012.xdf;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * UpdaterGuardHooks — 禁止 XDF 升级中心（cn.xdf.updater v1.0.11）自动/静默/强制更新，
 * 只保留「打开升级中心手动点击升级」这一条路径。尤其针对**夜间强制刷机**。
 *
 * <p>由独立 PoC 模块 UpdaterGuard（2026-10-08）整合而来，逆向分析见
 * {@code /workspace/docs/xdf_updater_分析报告.md}。开关：
 * {@link AppConfig#H_UPDATER_GUARD}（scope: cn.xdf.updater）。</p>
 *
 * <p><b>逆向依据</b>：三条升级链（system OTA / app / plugin）都由服务端策略对象驱动：</p>
 * <pre>
 *   每小时 CheckUpdateScheduler + 开机2min → 检查更新（保留，不拦）
 *     → SchedulerKt.scheduleXxxWork 按 silent/autoReboot 时间窗排 WorkManager OneTimeWork
 *       → SystemUpdateWorker/AppUpdateWorker/PluginUpdateWorker.doWork
 *           校验 getSilent()+窗口 → task.resume()      ← 夜间静默下载+安装
 *       → RebootWorker.doWork 校验 getAutoReboot()+窗口
 *           → finalize → 写 persist.sys.xdf.fota.reboot.upgrade.silent=1
 *           → RecoverySystem.installPackage            ← 夜间自动重启刷机 ★
 *     → force=true → ForceRebootHint 悬浮窗强推重启 + 对外强更清单
 * </pre>
 *
 * <p><b>手动路径</b> MainActivity/MainViewModel → task.resume() **不经过 silent 判断、
 * 不经过 Worker** —— 拦自动链路对手动升级零影响，这是本方案可行的架构基础。</p>
 *
 * <p><b>三层 hook（互相独立冗余）</b>：</p>
 * <ul>
 *   <li><b>[C] 策略语义层</b>：三个 Strategy 的 getSilent/getAutoReboot/getForce → 恒 false。
 *       排程方读到 false 直接不排程；已排程的 Worker 读到 false 走 noSilentConfig 退出；
 *       force=false → 不弹强推重启窗、强更清单为空</li>
 *   <li><b>[A] 排程层</b>：SchedulerKt.scheduleSystemUpdateWork/scheduleReboot/
 *       scheduleAppUpdateWork/schedulePluginUpdateWork → 不执行（WorkManager 里不再出现
 *       任何自动升级任务）</li>
 *   <li><b>[B] Worker 兜底层</b>：四个 Worker.doWork → 直接返回 Result.success()
 *       （Worker 只被自动排程触发，手动升级不经过，success 不改变任务真实状态）</li>
 * </ul>
 *
 * <p><b>明确不 hook</b>：TaskHelperNonAbKt.finalize / OtaEngineImpl29.apply（手动重启也走）、
 * ActionReceiver.onReceive（通知栏重启按钮也走）、UpdateChecker/CheckUpdateScheduler
 * （保留检查否则升级列表没数据）、AppUpdaterExt/PluginUpdaterService（外部 App 请求入口）、
 * Device.reboot（逆向确认零调用方，死代码）。</p>
 *
 * <p>安全性：普通 App 进程，不碰 system_server；PROTECTIVE + 单点 try/catch；
 * 无广播、无 IO、无磁盘写入；该 App dex 无 xposed/magisk 检测（已全文检索）。</p>
 */
final class UpdaterGuardHooks {

    private static final String TAG = "UpdaterGuard";

    private UpdaterGuardHooks() {
    }

    /**
     * 安装全部三层 hook（XDFHook.onPackageReady 的 PKG_UPDATER 分支调用）。
     *
     * @return 成功注册的 hook 数
     */
    static int hookAll(ClassLoader cl) {
        int[] cnt = new int[]{0, 0}; // [ok, fail]
        installStrategyHooks(cl, cnt);
        installSchedulerHooks(cl, cnt);
        installWorkerHooks(cl, cnt);
        XDFHook.logi(TAG, "onPackageReady -> hooks installed ok=" + cnt[0]
                + ", failed=" + cnt[1] + " | C层(策略)+A层(排程)+B层(Worker) 三层全开");
        return cnt[0];
    }

    /* ==================== [C] 策略语义层 ==================== */

    /**
     * 把三个升级策略对象的关键开关全部钉死为 false：silent（夜间静默下载安装）、
     * autoReboot（夜间自动重启刷机）、force（强推重启悬浮窗+强更清单）。
     *
     * <p>这些 getter 同时被「排程方」和「执行方」读取（实测均为 invoke-virtual
     * 方法调用，未被内联），单这一层就同时覆盖排程与执行两个环节。</p>
     */
    private static void installStrategyHooks(ClassLoader cl, int[] cnt) {
        final String ent = "cn.xdf.updater.common.entity.";

        // SystemUpdateStrategy：三个开关
        hookReturnFalse(cl, ent + "SystemUpdateStrategy", "getSilent",
                "C: SystemUpdateStrategy.getSilent=false (夜间静默升级关闭)", cnt);
        hookReturnFalse(cl, ent + "SystemUpdateStrategy", "getAutoReboot",
                "C: SystemUpdateStrategy.getAutoReboot=false (夜间自动重启关闭)", cnt);
        hookReturnFalse(cl, ent + "SystemUpdateStrategy", "getForce",
                "C: SystemUpdateStrategy.getForce=false (系统强推重启弹窗关闭)", cnt);

        // AppUpdateStrategy：静默 + 强制
        hookReturnFalse(cl, ent + "AppUpdateStrategy", "getSilent",
                "C: AppUpdateStrategy.getSilent=false (应用静默升级关闭)", cnt);
        hookReturnFalse(cl, ent + "AppUpdateStrategy", "getForce",
                "C: AppUpdateStrategy.getForce=false (应用强制升级关闭)", cnt);

        // PluginUpdateStrategy：静默 + 强制
        hookReturnFalse(cl, ent + "PluginUpdateStrategy", "getSilent",
                "C: PluginUpdateStrategy.getSilent=false (插件静默升级关闭)", cnt);
        hookReturnFalse(cl, ent + "PluginUpdateStrategy", "getForce",
                "C: PluginUpdateStrategy.getForce=false (插件强制升级关闭)", cnt);
    }

    /* ==================== [A] 排程层 ==================== */

    /**
     * 掐断四条自动排程：WorkManager 里不再出现任何自动升级任务。
     * （C 生效时 scheduleXxxWork 自身先读 getSilent() 就提前 return 了 —— 双保险。）
     */
    private static void installSchedulerHooks(ClassLoader cl, int[] cnt) {
        final String sched = "cn.xdf.updater.worker.util.SchedulerKt";
        try {
            Class<?> ctx = Context.class;
            Class<?> sysInfo = Class.forName(
                    "cn.xdf.updater.common.entity.SystemUpdateInfo", false, cl);
            Class<?> appInfo = Class.forName(
                    "cn.xdf.updater.common.entity.UpdatableAppInfo", false, cl);
            Class<?> plgInfo = Class.forName(
                    "cn.xdf.updater.common.entity.PluginUpdateInfo", false, cl);

            hookSkip(cl, sched, "scheduleSystemUpdateWork",
                    new Class[]{ctx, sysInfo},
                    "A: scheduleSystemUpdateWork skipped (系统OTA夜间静默排程掐断)", cnt);
            hookSkip(cl, sched, "scheduleReboot",
                    new Class[]{ctx, sysInfo},
                    "A: scheduleReboot skipped (夜间自动重启排程掐断)", cnt);
            hookSkip(cl, sched, "scheduleAppUpdateWork",
                    new Class[]{ctx, appInfo},
                    "A: scheduleAppUpdateWork skipped (应用静默排程掐断)", cnt);
            hookSkip(cl, sched, "schedulePluginUpdateWork",
                    new Class[]{ctx, plgInfo},
                    "A: schedulePluginUpdateWork skipped (插件静默排程掐断)", cnt);
        } catch (Throwable t) {
            cnt[1]++;
            XDFHook.logw(TAG, "A: SchedulerKt class resolve failed: " + t);
        }
    }

    /* ==================== [B] Worker 兜底层 ==================== */

    /**
     * 四个自动升级 Worker 的 doWork 直接返回 Result.success()。
     * Result 是宿主 dex 里的类（androidx.work），必须用方法声明所在 ClassLoader
     * 反射构造（模块编译期没有 androidx.work 依赖）。
     */
    private static void installWorkerHooks(ClassLoader cl, int[] cnt) {
        try {
            Class<?> cont = Class.forName("kotlin.coroutines.Continuation", false, cl);

            hookWorkerSuccess(cl, "cn.xdf.updater.worker.system.SystemUpdateWorker",
                    "doWork", new Class[]{cont},
                    "B: SystemUpdateWorker.doWork neutralized", cnt);
            hookWorkerSuccess(cl, "cn.xdf.updater.worker.app.AppUpdateWorker",
                    "doWork", new Class[]{cont},
                    "B: AppUpdateWorker.doWork neutralized", cnt);
            hookWorkerSuccess(cl, "cn.xdf.updater.worker.plugin.PluginUpdateWorker",
                    "doWork", new Class[]{cont},
                    "B: PluginUpdateWorker.doWork neutralized", cnt);
            hookWorkerSuccess(cl, "cn.xdf.updater.worker.system.RebootWorker",
                    "doWork", new Class[0],
                    "B: RebootWorker.doWork neutralized (夜间重启最后兜底)", cnt);
        } catch (Throwable t) {
            cnt[1]++;
            XDFHook.logw(TAG, "B: worker class resolve failed: " + t);
        }
    }

    /* ==================== 通用 hook 工具 ==================== */

    /** 无参 boolean 方法 → 恒返回 false（替代执行原方法） */
    private static void hookReturnFalse(ClassLoader cl, String cls, String method,
                                        String what, int[] cnt) {
        hookMethod(cl, cls, method, new Class[0], Boolean.FALSE, what, cnt);
    }

    /** void 方法 → 直接跳过（不执行原方法体） */
    private static void hookSkip(ClassLoader cl, String cls, String method,
                                 Class<?>[] params, String what, int[] cnt) {
        hookMethod(cl, cls, method, params, null, what, cnt);
    }

    /** Worker.doWork → 返回 ListenableWorker.Result.success()（反射构造） */
    private static void hookWorkerSuccess(ClassLoader cl, String cls, String method,
                                          Class<?>[] params, String what, int[] cnt) {
        try {
            Method m = resolve(cl, cls, method, params);
            XposedInterface.Hooker hooker = chain -> {
                // 用方法声明所在 ClassLoader 加载 Result —— 它属于宿主 dex
                ClassLoader hostCl = chain.getExecutable().getDeclaringClass().getClassLoader();
                Class<?> result = Class.forName(
                        "androidx.work.ListenableWorker$Result", false, hostCl);
                return result.getMethod("success").invoke(null);
            };
            register(m, hooker, what, cnt);
        } catch (Throwable t) {
            cnt[1]++;
            XDFHook.logw(TAG, "FAILED " + what + " -> " + t);
        }
    }

    /** 核心注册：解析方法 → deoptimize → PROTECTIVE hook → 计数 */
    private static void hookMethod(ClassLoader cl, String cls, String method,
                                   Class<?>[] params, Object returnValue,
                                   String what, int[] cnt) {
        try {
            Method m = resolve(cl, cls, method, params);
            final Object ret = returnValue;
            XposedInterface.Hooker hooker = chain -> ret;
            register(m, hooker, what, cnt);
        } catch (Throwable t) {
            cnt[1]++;
            XDFHook.logw(TAG, "FAILED " + what + " -> " + t);
        }
    }

    private static Method resolve(ClassLoader cl, String cls, String method,
                                  Class<?>[] params) throws Exception {
        Class<?> c = Class.forName(cls, false, cl); // false = 不触发 <clinit>
        Method m = c.getDeclaredMethod(method, params);
        m.setAccessible(true);
        return m;
    }

    /**
     * 注册：deoptimize（防宿主 speed-profile AOT 内联导致 hook 入口拦不住）
     * → 强引用保活 + PROTECTIVE（XDFHook.hook 内置）→ 计数。
     */
    private static void register(Method m, XposedInterface.Hooker hooker,
                                 String what, int[] cnt) {
        XDFHook.deopt(m);
        XDFHook.hook(m, hooker); // 内部已加入 sHookKeepAlive + PROTECTIVE 模式
        cnt[0]++;
        XDFHook.logi(TAG, "hooked " + what);
    }
}
