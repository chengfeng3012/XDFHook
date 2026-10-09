package cn.cf3012.xdf;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * GestureNavHooks — 解除「第三方桌面 → 全面屏手势被限制、启用后又被复位」。
 *
 * <p>由独立 PoC 模块 GestureNavFix（2026-10-08）整合而来，排查报告见
 * {@code /workspace/docs/XDF_手势导航限制_排查报告.md}。开关：
 * {@link AppConfig#H_GESTURE_NAV}（systemui 与 settings 两个 scope 各一个）。</p>
 *
 * <p><b>逆向结论</b>：限制不在 framework/services（两者 dex 搜 threebutton/
 * navigation_bar_mode 全 0 命中），而是厂商改 AOSP Q 的两道闸门，都在系统 app 内：</p>
 *
 * <ul>
 *   <li><b>L2 复位执行者 = XDF_MtkSystemUI</b>：{@code NavigationModeController}
 *       构造器与 {@code ACTION_PREFERRED_ACTIVITY_CHANGED} 广播都会跑
 *       {@code switchFromGestureNavModeIfNotSupportedByDefaultLauncher()}，
 *       判据 = 默认桌面 isSystemApp（flags & 0x81，非包名白名单）→ 微软桌面等
 *       第三方桌面必被切回 threebutton 并弹通知。★SystemUI 重启/开机同样复位。</li>
 *   <li><b>L1 设置页门禁 = XDF_MtkSettings</b>：
 *       {@code SystemNavigationPreferenceController.isGestureNavSupportedByDefaultLauncher}
 *       要求默认桌面包名 == config_recentsComponentName == "com.android.launcher3"，
 *       不满足则点「全面屏手势」弹不可用对话框、overlay 根本不切。</li>
 * </ul>
 *
 * <p><b>三层 hook</b>（B1/B3 同进程冗余，任何一层生效即不被复位）：</p>
 * <pre>
 *   [B1] SystemUI 判据层（首选）：isGestureNavSupportedByDefaultLauncher(Context)
 *        -> java.lang.Boolean 恒 TRUE（★包装类型，非 Z）。
 *        一处同时覆盖 switchFrom...（复位）与 showNotificationIf...（提示）两个调用方
 *   [B3] SystemUI 出口兜底：setModeOverlay(String,int) 拦掉目标含
 *        "navbar.threebutton" 的调用（非 threebutton 照常 proceed，
 *        用户在设置里改回三键走 Settings 自己的路径，不经此处，不受影响）
 *   [C1] Settings 门禁层：同名方法（static、返回 boolean/Z）恒 true，
 *        设置页可正常勾选、不再弹不可用对话框
 * </pre>
 *
 * <p><b>明确不 hook</b>：framework 的 config_recentsComponentName 资源（被 L1 与
 * OverviewProxyService.mRecentsComponentName 共用，改了 QuickStep setPackage 指错包
 * → 上滑彻底失效）；OverviewProxyService/TouchInteractionService（QuickStep 恒绑
 * com.android.launcher3 是功能面事实，微软桌面没有 QUICKSTEP_SERVICE）；
 * defer/restoreGesturalNavOverlayIfNecessary（未 provisioned 才生效）。不碰 system_server。</p>
 *
 * <p><b>生效</b>：systemui/settings 均非 system_server，kill 进程加载新 dex 即可。</p>
 */
final class GestureNavHooks {

    private static final String TAG = "GestureNavFix";

    private static final String CL_NMC =
            "com.android.systemui.statusbar.phone.NavigationModeController";
    private static final String CL_SNP =
            "com.android.settings.gestures.SystemNavigationPreferenceController";

    private static final String NAVBAR_THREEBUTTON =
            "com.android.internal.systemui.navbar.threebutton";

    private GestureNavHooks() {
    }

    /** SystemUI 侧：B1 判据层 + B3 出口兜底 */
    static void hookSystemUi(ClassLoader cl) {
        int ok = 0, fail = 0;

        // ---- [B1] 判据层：isGestureNavSupportedByDefaultLauncher -> TRUE ----
        try {
            Method m = resolve(cl, CL_NMC, "isGestureNavSupportedByDefaultLauncher",
                    new Class[]{Context.class});
            XposedInterface.Hooker hooker = chain -> {
                // 先跑原方法拿真实判定，仅用于日志（只在换桌面/进程启动时触发，
                // 一次 getHomeActivities binder 往返，可忽略）
                boolean orig = true;
                try {
                    Object o = chain.proceed();
                    orig = !(o instanceof Boolean) || ((Boolean) o).booleanValue();
                } catch (Throwable ignored) {
                    orig = true; // 原方法异常按放行处理，绝不因它而复位
                }
                if (!orig) {
                    XDFHook.logi(TAG, "B1: 原判据=拒绝(默认桌面非系统应用) -> 强制放行 TRUE，"
                            + "本次复位已被拦截");
                }
                // ★返回类型是 java.lang.Boolean（包装类型），不是 boolean/Z
                return Boolean.TRUE;
            };
            XDFHook.deopt(m);
            XDFHook.hook(m, hooker);
            ok++;
            XDFHook.logi(TAG, "B1 hooked: SystemUI 判据恒 TRUE（默认桌面不必是系统应用）");
        } catch (Throwable t) {
            fail++;
            XDFHook.logw(TAG, "FAILED B1 " + CL_NMC + "#isGestureNavSupportedByDefaultLauncher -> " + t);
        }

        // ---- [B3] 出口兜底：setModeOverlay 拦掉 threebutton ----
        try {
            Method m = resolve(cl, CL_NMC, "setModeOverlay",
                    new Class[]{String.class, int.class});
            XposedInterface.Hooker hooker = chain -> {
                try {
                    List<Object> args = chain.getArgs(); // 返回 List，不是 Object[]
                    if (args != null && !args.isEmpty()) {
                        Object a0 = args.get(0);
                        if (a0 instanceof String && ((String) a0).contains(NAVBAR_THREEBUTTON)) {
                            XDFHook.logi(TAG, "B3: 拦截 threebutton 复位出口 (userId="
                                    + (args.size() > 1 ? args.get(1) : "?")
                                    + ")，手势 overlay 保持不变");
                            return null; // void 方法：跳过原方法体
                        }
                    }
                } catch (Throwable t) {
                    XDFHook.logw(TAG, "B3: 参数检查异常 -> 放行原方法: " + t);
                }
                return chain.proceed(); // 非 threebutton（如 gestural 恢复）照常执行
            };
            XDFHook.deopt(m);
            XDFHook.hook(m, hooker);
            ok++;
            XDFHook.logi(TAG, "B3 hooked: setModeOverlay 拦截 threebutton（复位出口兜底）");
        } catch (Throwable t) {
            fail++;
            XDFHook.logw(TAG, "FAILED B3 " + CL_NMC + "#setModeOverlay -> " + t);
        }

        XDFHook.logi(TAG, "systemui hooks installed ok=" + ok + ", failed=" + fail);
    }

    /** Settings 侧：C1 门禁层 */
    static void hookSettings(ClassLoader cl) {
        try {
            // 注意：与 SystemUI 同名方法**签名不同** —— Settings 这个是 static
            // 且返回 boolean(Z)，SystemUI 那个是实例方法返回 Boolean
            Method m = resolve(cl, CL_SNP, "isGestureNavSupportedByDefaultLauncher",
                    new Class[]{Context.class});
            final Object ret = Boolean.TRUE;
            XposedInterface.Hooker hooker = chain -> ret;
            XDFHook.deopt(m);
            XDFHook.hook(m, hooker);
            XDFHook.logi(TAG, "C1 hooked: Settings 门禁放开"
                    + "（默认桌面不再要求是 com.android.launcher3）");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "FAILED C1 " + CL_SNP
                    + "#isGestureNavSupportedByDefaultLauncher -> " + t);
        }
    }

    /**
     * 用**宿主 ClassLoader** 解析方法，initialize=false 不触发 &lt;clinit&gt;。
     * getDeclaredMethod + setAccessible：目标方法是 private/package-private，
     * 不能用 Class.getMethod（那只找 public）。
     */
    private static Method resolve(ClassLoader cl, String cls, String method,
                                  Class<?>[] params) throws Exception {
        Class<?> c = Class.forName(cls, false, cl);
        Method m = c.getDeclaredMethod(method, params);
        m.setAccessible(true);
        return m;
    }
}
