package cn.cf3012.xdf;

import android.content.ContentResolver;
import android.os.Binder;

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

        // Hook Settings.Secure.putString(ContentResolver, String, String)
        XDFHook.hookMethod(cl, "android.provider.Settings$Secure", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Secure"));

        // Hook Settings.Global.putString(ContentResolver, String, String)
        XDFHook.hookMethod(cl, "android.provider.Settings$Global", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Global"));

        XDFHook.logi(TAG, "input method hooks installed (mode=" + cfg.getInt(K_INPUT_METHOD_MODE, MODE_LOCK) + ")");
    }

    /**
     * 在其他进程中安装 hook（可选，用于拦截非 system_server 进程的调用）。
     */
    static void hookOtherProcess(ClassLoader cl) throws Exception {
        AppConfig cfg = AppConfig.get();
        if (!cfg.enabled(K_MOD_INPUT_METHOD)) {
            return;
        }
        // 同样 hook Settings.Secure 和 Settings.Global
        XDFHook.hookMethod(cl, "android.provider.Settings$Secure", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Secure"));
        XDFHook.hookMethod(cl, "android.provider.Settings$Global", "putString",
                new Class<?>[]{ContentResolver.class, String.class, String.class},
                chain -> interceptPutString(chain, "Global"));
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