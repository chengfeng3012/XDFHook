package cn.cf3012.xdf;

import android.content.ContentResolver;
import android.os.Binder;
import android.os.UserHandle;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * InputMethodHooks — 输入法拦截器，防止输入法被恶意或意外更改。
 *
 * 功能：
 *   1. Hook Settings.Secure.putString 和 Settings.Global.putString，拦截输入法相关设置。
 *   2. 支持两种模式：
 *      - 固化模式（默认）：阻止任何对输入法设置的更改（除模块自身外）。
 *      - 黑名单模式：阻止黑名单中的输入法被设置为默认。
 *   3. 可配置：通过 AppConfig 读取开关、模式、黑名单列表。
 *
 * 需要在 system_server 进程中 hook，因为输入法设置通常由系统服务处理。
 * 但为了安全，也在所有 scope 内进程中 hook（以防其他进程直接调用）。
 */
final class InputMethodHooks {

    private static final String TAG = "input_method";

    // 输入法相关的 Settings 键
    private static final String KEY_DEFAULT_INPUT_METHOD = "default_input_method";
    private static final String KEY_ENABLED_INPUT_METHODS = "enabled_input_methods";
    private static final String KEY_SELECTED_INPUT_METHOD = "selected_input_method";
    private static final String KEY_INPUT_METHOD_SUBTYPE_HISTORY = "input_method_subtype_history";

    // 所有需要拦截的键（小写）
    private static final Set<String> IME_KEYS = new HashSet<>(Arrays.asList(
            KEY_DEFAULT_INPUT_METHOD,
            KEY_ENABLED_INPUT_METHODS,
            KEY_SELECTED_INPUT_METHOD,
            KEY_INPUT_METHOD_SUBTYPE_HISTORY
    ));

    // AppConfig 键（直接引用 AppConfig 常量）
    private static final String K_MOD_INPUT_METHOD = AppConfig.K_MOD_INPUT_METHOD;
    private static final String K_INPUT_METHOD_MODE = AppConfig.K_INPUT_METHOD_MODE;
    private static final String K_INPUT_METHOD_LIST = AppConfig.K_INPUT_METHOD_LIST;

    private static final int MODE_LOCK = 0;
    private static final int MODE_BLACKLIST = 1;

    private InputMethodHooks() {
    }

    /**
     * 在 system_server 进程中安装 hook。
     */
    static void hookSystemServer(ClassLoader cl) throws Exception {
        AppConfig cfg = AppConfig.get();
        if (!cfg.enabled(K_MOD_INPUT_METHOD)) {
            XDFHook.logi(TAG, "input method hooks disabled");
            return;
        }

        int mode = cfg.getInt(K_INPUT_METHOD_MODE, MODE_LOCK);

        // Hook Settings.Secure.putString(ContentResolver, String, String)
        XDFHook.safeHook(cl, "android.provider.Settings$Secure", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Secure"), "ime.secure.putString");
        // Hook Settings.Secure.putStringForUser — 防止系统内部绕过 putString 直接写
        XDFHook.safeHook(cl, "android.provider.Settings$Secure", "putStringForUser",
                new Class<?>[]{ContentResolver.class, String.class, String.class,
                        String.class, boolean.class, boolean.class, int.class},
                chain -> interceptPutString(chain, "Secure"), "ime.secure.putStringForUser");

        // Hook Settings.Global.putString(ContentResolver, String, String)
        XDFHook.safeHook(cl, "android.provider.Settings$Global", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Global"), "ime.global.putString");
        // Hook Settings.Global.putStringForUser
        XDFHook.safeHook(cl, "android.provider.Settings$Global", "putStringForUser",
                new Class<?>[]{ContentResolver.class, String.class, String.class,
                        String.class, boolean.class, boolean.class, int.class},
                chain -> interceptPutString(chain, "Global"), "ime.global.putStringForUser");

        // IMMS 层拦截：zeus 直接 Binder 调 setInputMethod 时，IMMS 先改
        // mCurMethodId 再写 Settings，拦 Settings 拦不住内存状态，必须在 IMMS 层截断
        hookImmsBlock(cl);

        XDFHook.logi(TAG, "input method hooks installed (mode=" + mode
                + ", Settings 4 methods + IMMS setInputMethod/switchInputMethod)");
    }

    /**
     * 在其他进程中安装 hook（可选，用于拦截非 system_server 进程的调用）。
     */
    static void hookOtherProcess(ClassLoader cl) throws Exception {
        AppConfig cfg = AppConfig.get();
        if (!cfg.enabled(K_MOD_INPUT_METHOD)) {
            return;
        }
        // putString + putStringForUser，Secure + Global，共 4 条路径全覆盖。
        // 用 safeHook（独立 try-catch）：个别进程（如 MTK 定制 ROM 的某些
        // systemui 子进程）Settings 类不暴露 putStringForUser，找不到只记日志、
        // 不中断其余 hook（此前 hookMethod 直连，一个缺失导致整链 + IMMS 全废）。
        XDFHook.safeHook(cl, "android.provider.Settings$Secure", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Secure"), "ime.secure.putString");
        XDFHook.safeHook(cl, "android.provider.Settings$Secure", "putStringForUser",
                new Class<?>[]{ContentResolver.class, String.class, String.class,
                        String.class, boolean.class, boolean.class, int.class},
                chain -> interceptPutString(chain, "Secure"), "ime.secure.putStringForUser");
        XDFHook.safeHook(cl, "android.provider.Settings$Global", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Global"), "ime.global.putString");
        XDFHook.safeHook(cl, "android.provider.Settings$Global", "putStringForUser",
                new Class<?>[]{ContentResolver.class, String.class, String.class,
                        String.class, boolean.class, boolean.class, int.class},
                chain -> interceptPutString(chain, "Global"), "ime.global.putStringForUser");
    }

    /**
     * IMMS 层拦截：hook InputMethodManagerService.setInputMethod / switchInputMethod。
     *
     * 调用链：zeus Binder → IMMS.setInputMethod() → 内部先改 mCurMethodId → 再调
     * Settings.Secure.putStringForUser()。我们之前的 Settings hook 拦的是"写 Settings"
     * 这一步，但 IMMS 内存状态已经切了——下次任何 app 读 InputMethodManager 拿到的
     * 是 IMMS 的 mCurMethodId，不是 Settings 的值。必须在 IMMS 方法入口就截断。
     */
    private static void hookImmsBlock(ClassLoader cl) {
        // AOSP 标准 / MTK 定制两个候选类名
        String[] candidates = {
                "com.android.server.inputmethod.InputMethodManagerService",
                "com.mediatek.server.inputmethod.InputMethodManagerService"
        };
        for (String className : candidates) {
            try {
                Class<?> imms = Class.forName(className, false, cl);
                // 要拦截的方法名集合
                Set<String> targetMethods = new HashSet<>(Arrays.asList(
                        "setInputMethod",                 // (IBinder, String)
                        "switchInputMethod",              // (String)
                        "setInputMethodAndSubtype",       // (IBinder, String, Subtype)
                        "setInputMethodAndSubtypeForUser",// (IBinder, String, Subtype, int)
                        "setInputMethodEnabled",          // (String, boolean)
                        "setInputMethodEnabledForUser"    // (String, boolean, int)
                ));
                int hooked = 0;
                for (Class<?> k = imms; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (!targetMethods.contains(m.getName())) {
                            continue;
                        }
                        m.setAccessible(true);
                        final String methodName = m.getName();
                        XDFHook.hook(m, chain -> {
                            AppConfig cfg = AppConfig.get();
                            if (!cfg.enabled(K_MOD_INPUT_METHOD)) {
                                return chain.proceed();
                            }
                            int mode = cfg.getInt(K_INPUT_METHOD_MODE, MODE_LOCK);
                            String imeId = extractImeId(chain, methodName);
                            if (mode == MODE_LOCK) {
                                XDFHook.logw(TAG, "BLOCKED IMMS." + methodName
                                        + " id=" + imeId + " (lock mode)");
                                return null; // void 方法，不 proceed = 不执行
                            }
                            if (mode == MODE_BLACKLIST && imeId != null
                                    && isBlacklisted(imeId, cfg)) {
                                XDFHook.logw(TAG, "BLOCKED IMMS." + methodName
                                        + " id=" + imeId + " (blacklist)");
                                return null;
                            }
                            return chain.proceed();
                        });
                        hooked++;
                    }
                }
                XDFHook.logi(TAG, "IMMS hooks installed: " + className + " x" + hooked);
                return; // 找到类就停
            } catch (ClassNotFoundException ignored) {
            }
        }
        XDFHook.logw(TAG, "IMMS class not found in candidates, IMMS-level hooks skipped");
    }

    /** 从 chain 参数中提取 IME 组件名（id 参数位置因方法而异） */
    private static String extractImeId(XposedInterface.Chain chain, String methodName) {
        List<Object> args = chain.getArgs();
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            // switchInputMethod(String) / setInputMethodEnabled(String, boolean) → 第 0 个
            if ("switchInputMethod".equals(methodName)
                    || "setInputMethodEnabled".equals(methodName)
                    || "setInputMethodEnabledForUser".equals(methodName)) {
                return args.get(0) instanceof String ? (String) args.get(0) : null;
            }
            // setInputMethod(IBinder, String) / setInputMethodAndSubtype(..., String, ...) → 第 1 个
            return args.size() > 1 && args.get(1) instanceof String
                    ? (String) args.get(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 拦截 putString 调用。
     */
    private static Object interceptPutString(XposedInterface.Chain chain, String table) throws Throwable {
        AppConfig cfg = AppConfig.get();
        if (!cfg.enabled(K_MOD_INPUT_METHOD)) {
            return chain.proceed();
        }

        // 获取参数
        List<Object> args = chain.getArgs();
        if (args == null || args.size() < 3) {
            return chain.proceed();
        }

        // 第二个参数是 key（String）
        Object keyObj = args.get(1);
        if (!(keyObj instanceof String)) {
            return chain.proceed();
        }
        String key = (String) keyObj;

        // 检查是否是输入法相关键
        if (!IME_KEYS.contains(key.toLowerCase())) {
            return chain.proceed();
        }

        // 第三个参数是 value（String）
        Object valueObj = args.get(2);
        if (!(valueObj instanceof String)) {
            return chain.proceed();
        }
        String value = (String) valueObj;

        int mode = cfg.getInt(K_INPUT_METHOD_MODE, MODE_LOCK);
        String caller = callerProcessName();

        if (mode == MODE_LOCK) {
            // 固化模式：阻止任何更改（除模块自身外）
            // 我们可以通过调用者判断是否是模块自身（但模块不会调用 putString）
            // 简单策略：全部阻止
            XDFHook.logw(TAG, "BLOCKED (lock mode) " + table + ".putString key=" + key
                    + " value=" + value + " caller=" + caller);
            return Boolean.FALSE; // putString 返回 boolean
        } else if (mode == MODE_BLACKLIST) {
            // 黑名单模式：检查 value 是否在黑名单中
            // 对于 default_input_method 和 selected_input_method，value 是 component name
            // 对于 enabled_input_methods，value 是逗号分隔的列表
            if (isBlacklisted(value, cfg)) {
                XDFHook.logw(TAG, "BLOCKED (blacklist) " + table + ".putString key=" + key
                        + " value=" + value + " caller=" + caller);
                return Boolean.FALSE;
            }
        }

        // 放行
        return chain.proceed();
    }

    /**
     * 检查输入法标识是否在黑名单中。
     */
    private static boolean isBlacklisted(String value, AppConfig cfg) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        String blacklist = cfg.getString(K_INPUT_METHOD_LIST, "");
        if (blacklist.isEmpty()) {
            return false;
        }

        Set<String> blocked = new HashSet<>(Arrays.asList(blacklist.split(",")));

        // 对于 enabled_input_methods，可能是逗号分隔的多个组件
        if (value.contains(",")) {
            String[] parts = value.split(",");
            for (String part : parts) {
                if (blocked.contains(part.trim())) {
                    return true;
                }
            }
            return false;
        }

        // 单个组件
        return blocked.contains(value);
    }

    /**
     * 获取调用者进程名。
     */
    private static String callerProcessName() {
        int pid = Binder.getCallingPid();
        if (pid == android.os.Process.myPid()) {
            return "system_server";
        }
        String name = "unknown";
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("/proc/" + pid + "/cmdline"));
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            int nul = s.indexOf('\0');
            if (nul >= 0) {
                s = s.substring(0, nul);
            }
            s = s.trim();
            if (!s.isEmpty()) {
                name = s;
            }
        } catch (Throwable ignored) {
        }
        return name;
    }
}