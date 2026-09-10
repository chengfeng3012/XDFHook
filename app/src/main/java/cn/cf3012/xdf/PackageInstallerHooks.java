package cn.cf3012.xdf;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * PackageInstallerHooks — 解除「软件包安装程序」(com.android.packageinstaller) 的安装限制。
 *
 * 逆向结论（XDF_N1 / Android 10 原始包）：
 *   1. Roco 签名白名单： PackageInstallerActivity.onCreate 的普通安装路径
 *      (VIEW/INSTALL_PACKAGE) 插入三连判定
 *        checkCallingAndInstallPackageSame || checkCallingSignRight || checkInstallPackageSign
 *      全 false → showRejectView() 直接拒绝。
 *      checkCallingSignRight / checkInstallPackageSign 都硬编码比对
 *      白名单签名 MD5「FF7CC6961980EC50752930878B0B80FE」。
 *   2. checkIfAllowedAndInitiateInstall 检查标准 AOSP 的
 *      no_install_apps / no_install_unknown_sources(_globally) UserRestriction，
 *      unknown source 时再走 AppOps OP_REQUEST_INSTALL_PACKAGES。
 *
 * 本模块 hook 全部位于 com.android.packageinstaller 进程（非 system_server），
 * 变更 kill 进程重开即可生效（无需重启系统）。注意：必须把
 * com.android.packageinstaller 加入 XDFHook 的 LSPosed scope，否则不加载。
 *
 * hook 清单：
 *   - checkCallingSignRight()Z   → 强制 true（绕 Roco 签名白名单）
 *   - checkInstallPackageSign()Z → 强制 true（同上，双保险）
 *   - checkIfAllowedAndInitiateInstall()V → 直接反射调 initiateInstall()，
 *       跳过 no_install_apps / unknown sources / AppOps 限制
 */
final class PackageInstallerHooks {

    private static final String TAG = "pkg_install";

    private static final String CLS_ACTIVITY =
            "com.android.packageinstaller.PackageInstallerActivity";

    private PackageInstallerHooks() {
    }

    /** 入口：在 com.android.packageinstaller 进程安装全部 hook（见 AppConfig switch） */
    static void hookAll(ClassLoader cl) {
        // 1) Roco 签名白名单 · 来源包签名 —— 强制 "签名可信"
        XDFHook.safeHook(cl, CLS_ACTIVITY, "checkCallingSignRight",
                new Class<?>[0],
                chain -> interceptBool(chain, "checkCallingSignRight"),
                "pkgInstall.bridgeSourceSign");
        // 2) Roco 签名白名单 · 待装 APK 签名 —— 强制 "安装物签名可信"
        XDFHook.safeHook(cl, CLS_ACTIVITY, "checkInstallPackageSign",
                new Class<?>[0],
                chain -> interceptBool(chain, "checkInstallPackageSign"),
                "pkgInstall.bridgeApkSign");
        // 3) no_install_apps / unknown sources / AppOps —— 直接越过限制触发安装确认
        XDFHook.safeHook(cl, CLS_ACTIVITY, "checkIfAllowedAndInitiateInstall",
                new Class<?>[0],
                chain -> interceptBypassRestrictions(chain),
                "pkgInstall.bypassRestrictions");
        XDFHook.logi(TAG, "package installer hooks installed (sign whitlist x2 + restrictions bypass)");
    }

    /** 拦截 boolean 返回值的方法：开关开 → 恒 true；关 → 原逻辑。 */
    private static Object interceptBool(XposedInterface.Chain chain, String what) {
        AppConfig cfg = AppConfig.get();
        if (!cfg.enabled(AppConfig.K_MOD_PACKAGE_INSTALL)) {
            try {
                return chain.proceed();
            } catch (Throwable t) {
                return Boolean.FALSE;
            }
        }
        XDFHook.logd(TAG, "allow: " + what + " (sign bypass)");
        return Boolean.TRUE;
    }

    /**
     * 拦截安装限制总入口：开关开 → 反射调用原对象自身的 initiateInstall()
     * （等价于正常放行后最终的动作），随后抑制原方法体执行；开关关 → 原逻辑。
     * 若反射失败（类已变化）则回退到原逻辑，保证不破坏安装流程。
     */
    private static Object interceptBypassRestrictions(XposedInterface.Chain chain) {
        AppConfig cfg = AppConfig.get();
        if (!cfg.enabled(AppConfig.K_MOD_PACKAGE_INSTALL) || chain.getThisObject() == null) {
            try {
                return chain.proceed();
            } catch (Throwable t) {
                return null;
            }
        }
        Object self = chain.getThisObject();
        try {
            Method m = Reflect.findDeclared(self.getClass(), "initiateInstall", new Class<?>[0]);
            m.setAccessible(true);
            m.invoke(self);
            XDFHook.logw(TAG, "restrictions bypassed -> initiateInstall()");
            return null; // 抑制原方法体（其内部所有限制判断均被跳过）
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "bypassRestrictions fallback to original");
            try {
                return chain.proceed();
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}