package cn.cf3012.xdf;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PowerKeyHooks — 解除「电源键 / input 命令 / 自动息屏全部失效」的厂商硬伤。
 *
 * <p>开关：{@link AppConfig#H_POWER_KEY_FIX}（scope: system）。system_server 单元，
 * 装完需重启 framework 才生效（按铁律不建议重启系统，由用户自行选择时机）。</p>
 *
 * <h3>根因（XDF_framework / services 反编译实锤，2026-10-09）</h3>
 *
 * <p>XDF 在 services.jar 里自加了一个类
 * {@code com.android.server.power.xdf.ScreenBrightnessController}（全部只有 3 字段 3 方法），
 * 并在 {@code PowerManagerService} 的两个入口调用它：</p>
 *
 * <pre>
 * // ① goToSleepInternal(JIII)V —— 方法第一句
 * if (ScreenBrightnessController.isKeepScreenOnWhenUpgradeTouchDriver()) {
 *     Slog.d("PowerManagerService",
 *            "goToSleepInternal return because keepScreenOn when upgrade touch driver");
 *     return;                                      // ← 息屏请求被直接吞掉
 * }
 *
 * // ② updateUserActivitySummaryLocked(J)V —— dim 阶段分支
 * if (ScreenBrightnessController.isKeepScreenOnWhenUpgradeTouchDriver()) {
 *     userActivityInternal(now, 0, 0, 1000);       // ← 把用户活动时间往后推
 * }                                                //    自动 dim / 自动息屏一并失效
 * </pre>
 *
 * <p>判据函数本体（反编译还原，字段名/常量值均按 smali 逐条比对）：</p>
 *
 * <pre>
 * private static final int  UPGRADE_TOUCH_DRIVER_TIMEOUT = 0xC350;   // 50_000 ms
 * private static final long sTimingUpgradeTouchDriverTime;           // <clinit> = uptimeMillis()
 *
 * public static boolean isKeepScreenOnWhenUpgradeTouchDriver() {
 *     if (uptimeMillis() - sTimingUpgradeTouchDriverTime &gt; 50000) {
 *         return false;                                  // 超时保护
 *     }
 *     String flag = FileUtils.readOneLineFile("/proc/android_touch/fw_load_comp_flag");
 *     return !"1".equals(flag);                          // ← 读不到即 true
 * }
 * </pre>
 *
 * <p><b>本机实测</b>：{@code /proc/android_touch/} 下只有
 * {@code debug / diag / flash_dump / self_test / vendor}，
 * <b>根本没有 fw_load_comp_flag 这个节点</b> → 读不到 → 恒 true。</p>
 *
 * <p><b>为什么「每一次重启都会这样」</b>：50 秒窗口的起点是
 * {@code sTimingUpgradeTouchDriverTime}，而它在 {@code <clinit>} 中取
 * {@code uptimeMillis()} —— 即<b>该类首次被初始化</b>的时刻，<b>不是开机时刻</b>。
 * 该类只被上面两处调用，因此开机后<b>首次按电源键（或屏幕首次进入 dim）才触发类加载</b>，
 * 从那一刻起 50 秒内所有息屏请求全部被吞。用户感受就是「开机后电源键怎么按都没反应」，
 * 50 秒后自行恢复。</p>
 *
 * <p><b>修复策略</b>：hook {@code isKeepScreenOnWhenUpgradeTouchDriver()Z} 恒返
 * {@code Boolean.FALSE}。一处覆盖上述两个调用点；因为直接给出返回值、不再走原方法体，
 * 顺带省掉了热路径上每次调用的 /proc 文件读取。</p>
 *
 * <h3>为什么恒 false 是安全的</h3>
 * <ul>
 *   <li>该函数语义是「触摸固件正在加载，先别息屏」。本机不存在
 *       {@code fw_load_comp_flag} 节点，这个窗口在本机<b>永远不会正常结束</b>；
 *       恒 false 只是把它恢复成 AOSP 行为（AOSP 的 {@code goToSleepInternal}
 *       根本没有这段判断）。</li>
 *   <li>窗口本身只有 50 秒，且与触摸功能无关（触摸由内核驱动负责，和息屏开关互不干涉）。</li>
 * </ul>
 *
 * <h3>安全边界</h3>
 * <p>PROTECTIVE 模式；hooker 强引用保活（见 {@link XDFHook#hook}）；
 * 类/方法不存在只记日志（兼容日后 ROM 变更，不影响 system_server 启动）。
 * ★ 目标方法是<b>热路径</b>（{@code updateUserActivitySummaryLocked} 每次电源状态更新
 * 都会调用），因此 <b>hook 体内禁止逐次打日志</b>，仅第一次拦截时记一条。</p>
 */
final class PowerKeyHooks {

    private static final String TAG = "PowerKeyFix";

    /** XDF 自加类（services.jar，包 com.android.server.power.xdf） */
    private static final String CL_SCREEN_BRIGHTNESS =
            "com.android.server.power.xdf.ScreenBrightnessController";

    /** 判据方法：public static boolean isKeepScreenOnWhenUpgradeTouchDriver()，无参 */
    private static final String M_KEEP_SCREEN_ON =
            "isKeepScreenOnWhenUpgradeTouchDriver";

    /** 热路径：只打一次拦截日志，避免刷屏（理由见类注释） */
    private static final AtomicBoolean sLoggedFirstHit = new AtomicBoolean(false);

    private PowerKeyHooks() {
    }

    /** system_server 入口（XDFHook.onSystemServerStarting 调用） */
    static void hookSystemServer(ClassLoader cl) {
        try {
            // initialize=false：只加载类，不触发 <clinit>
            // （<clinit> 里那行 uptimeMillis() 是原逻辑的计时起点，不存在副作用，不去动它）
            Class<?> c = Class.forName(CL_SCREEN_BRIGHTNESS, false, cl);
            Method m = c.getDeclaredMethod(M_KEEP_SCREEN_ON);
            m.setAccessible(true);
            XDFHook.deopt(m);
            XDFHook.hook(m, chain -> {
                // 刻意不调 chain.proceed()：原方法体只是读 /proc 文件并算一个布尔值，
                // 无副作用；直接给判定结果，同时省掉热路径上的文件 IO。
                if (sLoggedFirstHit.compareAndSet(false, true)) {
                    XDFHook.logi(TAG, "首次拦截：keepScreenOn 判定强制 false，"
                            + "息屏请求已放行（此后不再重复记录）");
                }
                // 目标方法返回原始 boolean(Z)；LibXposed 会按返回类型拆箱
                return Boolean.FALSE;
            });
            XDFHook.logi(TAG, "hooked: " + CL_SCREEN_BRIGHTNESS + "#"
                    + M_KEEP_SCREEN_ON + "() -> false");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "FAILED hook " + CL_SCREEN_BRIGHTNESS + "#"
                    + M_KEEP_SCREEN_ON + ": " + t);
        }
    }
}
