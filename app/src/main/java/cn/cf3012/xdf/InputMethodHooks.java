package cn.cf3012.xdf;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.os.Binder;
import android.os.SystemClock;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
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

    /**
     * 固化模式。
     *
     * <p><b>★ 新机高危（2026-10 用户反馈后修正）</b>：原实现是「无条件拦住
     * 4 个 IME 键的一切写入」，包括系统自己在输入法页做 enable/disable
     * （写 {@code enabled_input_methods}）与切换默认输入法
     * （写 {@code default_input_method}）。新机出厂只有系统默认 3 个输入法时，
     * 用户进输入法页想开关任何一个都会被吞 —— 系统 UI 以为写成功、
     * 实际没写，界面卡在旧状态；更糟的是若被拦的正好是让当前 IME 重新
     * enabled 的那次写入，<b>输入法就再也弹不出来了</b>。</p>
     *
     * <p>现语义：固化 = 只拦「把默认输入法改成别的」与「关掉系统自带 IME」，
     * 但<b>放行</b>：① 系统/设置页对 {@code enabled_input_methods} 的正常增删；
     * ② 对当前默认输入法的重复设置（同值）；
     * ③ 任何来自模块自身 App 的写入（便于本模块恢复现场）。
     * 这样既防住了 zeus 之类偷偷改默认输入法，又不会出现「IME 弹不出来」。</p>
     */
    private static final int MODE_LOCK = 0;
    private static final int MODE_BLACKLIST = 1;

    /** 系统出厂自带的 IME 白名单前缀：这些组件永不进入黑名单判定 */
    private static final String[] SYSTEM_IME_PREFIXES = {
            "com.android.inputmethod.latin",
            "com.android.inputmethod.latin/.LatinIME",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "com.android.inputmethod.pinyin",
    };

    /** system_server 的 ClassLoader（读 IMMS 静态 getInstance 用） */
    private static volatile ClassLoader sSystemServerCl;

    private InputMethodHooks() {
    }

    /**
     * 在 system_server 进程中安装 hook。
     */
    static void hookSystemServer(ClassLoader cl) throws Exception {
        sSystemServerCl = cl;
        AppConfig cfg = AppConfig.get();
        if (!cfg.hookEnabled(AppConfig.SCOPE_SYSTEM, AppConfig.H_IME_GUARD)) {
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
        if (!cfg.hookEnabled(AppConfig.SCOPE_SYSTEM, AppConfig.H_IME_GUARD)) {
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
                // 要拦截的方法名集合。
                // 【MT2 实测 XDF_framework.apk 的 IMMS 真实方法表】
                //   setInputMethod(IBinder,String)V                     ✅ binder 入口
                //   setInputMethodAndSubtype(IBinder,String,Subtype)V  ✅ binder 入口
                //   setInputMethodLocked(String,int)V                   ★ 真正落地的收口
                //   setInputMethodWithSubtypeIdLocked(IBinder,String,int)V ★ locked 变体
                //   handleShellCommandSetInputMethod(ShellCommand)I     ← ime set 走这里
                // 而 switchInputMethod / setInputMethodAndSubtypeForUser /
                // setInputMethodEnabled / setInputMethodEnabledForUser 在 XDF 里
                // 【根本不存在】，旧列表因此只匹配到 2 个方法（=日志里的 "x2"），
                // ime set 的实际路径完全没被覆盖。
                Set<String> targetMethods = new HashSet<>(Arrays.asList(
                        "setInputMethod",                      // (IBinder, String)
                        "setInputMethodAndSubtype",            // (IBinder, String, Subtype)
                        "setInputMethodLocked",                // (String, int)  ★ 核心
                        "setInputMethodWithSubtypeIdLocked"    // (IBinder, String, int)
                ));
                int hooked = 0;
                StringBuilder hookedNames = new StringBuilder();
                for (Class<?> k = imms; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (!targetMethods.contains(m.getName())) {
                            continue;
                        }
                        m.setAccessible(true);
                        final String methodName = m.getName();
                        // system_server 跑在 boot image（AOT）里，短小方法可能被
                        // 内联进调用方，导致 hook 形同虚设（Settings$Secure.putString
                        // 就属于这类）。deoptimize 强制 ART 走真实方法体。
                        XDFHook.deopt(m);
                        XDFHook.hook(m, chain -> {
                            AppConfig cfg = AppConfig.get();
                            if (!cfg.hookEnabled(AppConfig.SCOPE_SYSTEM, AppConfig.H_IME_GUARD)) {
                                return chain.proceed();
                            }
                            int mode = cfg.getInt(K_INPUT_METHOD_MODE, MODE_LOCK);
                            String imeId = extractImeId(chain, methodName);
                            if (mode == MODE_LOCK) {
                                // ★ 安全固化：只拦「切到别的输入法」，
                                // 放行「切回自己」（否则 IME 弹不出来无法自愈）
                                if (imeId == null || imeId.equals(currentInputMethodId())) {
                                    return chain.proceed();
                                }
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
                        hookedNames.append(methodName).append('/')
                                .append(m.getParameterCount()).append(' ');
                    }
                }
                XDFHook.logi(TAG, "IMMS hooks installed: " + className + " x" + hooked
                        + " -> " + hookedNames);
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
            // 【通用取法】所有目标方法的 IME id 都是「第一个 String 参数」：
            //   setInputMethod(IBinder,String)              → args[1]
            //   setInputMethodAndSubtype(IBinder,String,..)  → args[1]
            //   setInputMethodLocked(String,int)            → args[0]  ★ 新增
            //   setInputMethodWithSubtypeIdLocked(IBinder,String,int) → args[1]
            // 与其按方法名硬编码下标（XDF 与 AOSP 签名不一致时必错），不如直接
            // 扫第一个 String —— 再用 '/' 特征做一次确认，避免抓到无关字符串。
            for (Object a : args) {
                if (a instanceof String) {
                    String s = (String) a;
                    if (s.indexOf('/') > 0) {
                        return s;
                    }
                }
            }
            for (Object a : args) {
                if (a instanceof String) {
                    return (String) a;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 拦截 putString 调用。
     */
    private static Object interceptPutString(XposedInterface.Chain chain, String table) throws Throwable {
        AppConfig cfg = AppConfig.get();
        if (!cfg.hookEnabled(AppConfig.SCOPE_SYSTEM, AppConfig.H_IME_GUARD)) {
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

        // 诊断：IME 相关键每次都经过这里，debug 级记一行（不进 api.log 聚合通道，
        // 只在 logcat），用来确认 Settings 这层兜底是否真的被触发到
        // （boot image 内联导致 hook 失效时，这里会完全没有输出）。
        XDFHook.logd(TAG, "putString " + table + " key=" + key + " value=" + value
                + " mode=" + mode + " caller=" + caller + " -> evaluating");

        if (mode == MODE_LOCK) {
            // ★ 安全固化（详见 MODE_LOCK 注释）：
            //   - enabled_input_methods：系统输入法页正常增删 IME 时会写这个键，
            //     全拦会让新机（仅 3 个系统输入法）无法开关输入法，
            //     拦到关键写入还会导致 IME 直接弹不出来 → 放行。
            //   - default/selected_input_method：值为「当前已生效的 IME」时放行
            //     （同值重写是无害的，且是 IME 自愈的必要路径）。
            //   - 其余（真正要改掉输入法）才拦。
            if (KEY_ENABLED_INPUT_METHODS.equalsIgnoreCase(key)) {
                XDFHook.logd(TAG, "allow (lock mode passthrough) enabled_input_methods"
                        + " caller=" + caller + " value=" + value);
                return chain.proceed();
            }
            if (value.equals(currentInputMethodId())) {
                XDFHook.logd(TAG, "allow (same value) " + table + ".putString key=" + key
                        + " caller=" + caller);
                return chain.proceed();
            }
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
     * 读当前生效的默认输入法（cached 1s，避免 Settings 读取放大）。
     *
     * <p>固化模式判定必需：只有知道「当前是什么」才能区分
     * 「换输入法（要拦）」和「重写同一个输入法（放行）」。</p>
     */
    private static volatile String sCurIme;
    private static volatile long sCurImeAt;

    /** IMMS 实例缓存（system_server 里读 mCurMethodId / Settings 都靠它） */
    private static volatile Object sImms;

    /**
     * 读当前生效的默认输入法组件名（1s 缓存）。
     *
     * <p>固化模式判定必需：只有知道「当前是什么」才能区分
     * 「换输入法（要拦）」与「重写同一个输入法（放行）」。</p>
     *
     * <p>取值顺序：① IMMS.mCurMethodId（内存真值，最快最准）
     * ② IMMS 的 Context 读 Secure.default_input_method；
     * 任一路径读不到就沿用上一次缓存 —— <b>读不到时必须放行</b>，
     * 宁可漏拦也不能把 IME 锁死。</p>
     */
    private static String currentInputMethodId() {
        long now = SystemClock.elapsedRealtime();
        String cached = sCurIme;
        if (cached != null && now - sCurImeAt < 1000L) {
            return cached;
        }
        String v = null;
        try {
            Object imms = sImms;
            if (imms == null) {
                for (String cn : new String[]{
                        "com.android.server.inputmethod.InputMethodManagerService",
                        "com.mediatek.server.inputmethod.InputMethodManagerService"}) {
                    try {
                        Class<?> k = Class.forName(cn, false, sSystemServerCl);
                        Object inst = k.getMethod("getInstance").invoke(null);
                        if (inst != null) {
                            imms = inst;
                            sImms = inst;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (imms != null) {
                // ① 内存真值
                for (String f : new String[]{"mCurMethodId", "mCurClient"}) {
                    try {
                        Field fd = findFieldQuietly(imms.getClass(), f);
                        if (fd == null) {
                            continue;
                        }
                        Object o = fd.get(imms);
                        if (o instanceof String && ((String) o).indexOf('/') > 0) {
                            v = (String) o;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // ② 兜底：Secure 键
                if (v == null) {
                    Object ctx = findFieldQuietly(imms.getClass(), "mContext") != null
                            ? findFieldQuietly(imms.getClass(), "mContext").get(imms) : null;
                    if (ctx instanceof Context) {
                        v = Settings.Secure.getString(((Context) ctx).getContentResolver(),
                            KEY_DEFAULT_INPUT_METHOD);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (v == null || v.isEmpty()) {
            return cached; // 读不到 → 沿用上次；仍为空则调用方按「放行」处理
        }
        sCurIme = v;
        sCurImeAt = now;
        return v;
    }

    private static Field findFieldQuietly(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
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
        if (blocked.contains(value)) {
            // ★ 系统自带 IME 不拦：若它是设备上唯一的输入法，拦掉等于
            //   让设备彻底没有输入法可用（新机出厂状态）。
            for (String p : SYSTEM_IME_PREFIXES) {
                if (value.startsWith(p)) {
                    return false;
                }
            }
            return true;
        }
        return false;
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