package cn.cf3012.xdf;

import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * BuildSpoofHooks — 让 XDF 业务 App 以为自己运行在「生产环境」而非 debug 环境。
 *
 * <p><b>整合自独立模块 {@code cn.cf3012.buildspoof}（BuildSpoofModule）</b>。
 * 原模块 scope 是 35 个 XDF 业务 app（{@code cn.xdf.*} 全家），源码目录已于 2026-10-08
 * 整理时删除，本类依据 MT2 内的 {@code CustomModule_BuildSpoof.apk} 反编译 smali
 * <b>逐方法还原</b>（27 个方法全部核对，核心链路 apply / setStatic / exemptHiddenApi /
 * unsafe* 均为逐行对照，非语义猜测）。</p>
 *
 * <p><b>★ 为什么必须有这个 hook</b><br>
 * XDF 各业务 app 用 {@code !Build.IS_USER}（或其封装 {@code DeviceUtils.isDebugOs()}）
 * 判定「是否生产环境」，进而决定打哪个域名。设备是 userdebug 版 →
 * {@code Build.TYPE=userdebug}、{@code Build.IS_USER=false} → 这些 app 会去连
 * {@code *.test.xdf.cn} 测试域。</p>
 *
 * <p><b>★ 为什么只针对 3 个 app</b>（2026-10-08 横向比对结论，勿重复挖）：</p>
 * <ol>
 *   <li>{@code cn.xdf.appstore} —— {@code BaseUrlKt.BASE_URL = lazy{ if(isDebugOs()) test else prod }}，
 *       <b>只有 isDebugOs 一个条件</b> → 会切测试域</li>
 *   <li>{@code cn.xdf.updater} —— {@code Device.debug = !Build.IS_USER}（static final，
 *       &lt;clinit&gt; 固化），Host.kt 两个 host 硬编码 → 会切测试域</li>
 *   <li>{@code cn.xdf.zeus} —— {@code BaseUrlHelper} 三个 host 全由 {@code isDebugOs()}
 *       二选一且 &lt;clinit&gt; 固化 → 会切测试域</li>
 * </ol>
 * <p>其余 32 个 XDF app（如 {@code cn.xdf.pad.exercise}、{@code cn.xdf.ai.camera}）
 * 用的是 <b>{@code isDebugOs() && isDebugApk()}</b> 双条件，其中
 * {@code isDebugApk = BuildConfig.DEBUG = false}（编译期常量），无论如何都是正式域 →
 * <b>不受影响，故不加入 scope</b>。hook 面从 35 收敛到 3，风险可控。</p>
 *
 * <p><b>★ 机制：这不是 hook，是「时机触发的静态字段改写」</b><br>
 * 没有任何方法被 hook —— 只在 {@link XDFHook#onPackageReady} 拿到包就绪时机时，
 * 把 {@code android.os.Build} 的静态字段直接改掉。所以本类不需要 hooker 保活、
 * 不需要 PROTECTIVE 异常模式；它的失败模式只是「改不动 → app 继续走测试域」。</p>
 *
 * <p><b>★ 两个实现要点（都有踩坑史）</b></p>
 * <ol>
 *   <li><b>Android 10 必须先豁免 hidden API</b>：{@code Build.IS_USER} 是
 *       {@code @hide}，SDK stub 里没有，且反射访问受 hidden API 限制
 *       → 先调 {@code VMRuntime.setHiddenApiExemptions(new String[]{""})}
 *       放开全部限制（原模块 {@code exemptHiddenApi()}，整段 catchall 吞异常）。</li>
 *   <li><b>绝不能用「Field.modifiers 去 final」写法</b>：JDK 12 移除
 *       {@code Field.modifiers}，Android libcore 同步移除，API 29+ 直接
 *       {@code NoSuchFieldException: No field modifiers}（原模块独立开发时实测踩过）。<br>
 *       正确做法 = <b>双路径链式回退</b>：
 *       <b>路径 A</b> {@code Field.setAccessible + set/setBoolean} 直写
 *       （libcore 对 static final 的写入比 JDK 宽松，本机实测可成功）；
 *       <b>路径 B</b> {@code sun.misc.Unsafe} 的 {@code staticFieldBase} +
 *       {@code staticFieldOffset} + {@code putBoolean/putObject} 直写内存兜底。
 *       两条都失败才抛异常，文案带上 A/B 两侧原因便于诊断。</li>
 * </ol>
 *
 * <p><b>与 zeus 设备信息冒充的关系</b>：本类是「环境伪装」（让 app 认为自己在生产环境），
 * 与 {@link ZeusUnlocker} B4 单元的「设备信息伪装」（改 serial/model 上报值）
 * 是<b>两件独立的事</b>，互不替代。原 BuildSpoof 同时承担两块，整合时拆开了：
 * serial/model 归 ZeusUnlocker，环境判定归本类。</p>
 *
 * <p><b>日志</b>：logcat tag {@code BuildSpoof}，成功一行
 * {@code spoofed <pkg>, fields=N, now TYPE=..., IS_USER=..., TAGS=..., FINGERPRINT=...}，
 * 失败一行 {@code spoof failed in <pkg>}（带堆栈）。</p>
 *
 * <p><b>风险声明</b>：只改目标 app 自己进程内的静态字段，不跨进程、不改系统属性、
 * 不改 framework，作用面严格限于本类 scope 的 3 个 app 进程。</p>
 */
public final class BuildSpoofHooks {

    private static final String TAG = "BuildSpoof";

    /* ===== 原模块的编译期开关（默认值即原 APK 的 constantValue）===== */
    /** 改 {@code Build.TYPE}: "userdebug" -> "user" */
    private static final boolean SPOOF_TYPE = true;
    /** 改 {@code Build.IS_USER}: false -> true（{@code Device.debug = !IS_USER} 依赖它） */
    private static final boolean SPOOF_IS_USER = true;
    /** 原默认关：改 {@code Build.TAGS}（test-keys -> release-keys） */
    private static final boolean SPOOF_TAGS = false;
    /** 原默认关：改 {@code Build.FINGERPRINT}（/userdebug/ -> /user/） */
    private static final boolean SPOOF_FINGERPRINT = false;

    /** {@code sun.misc.Unsafe} 单例，{@code null} 表示不可用（路径 B 自动失效） */
    private static final Object UNSAFE = obtainUnsafe();

    private BuildSpoofHooks() {
    }

    /* ==================== 对外入口 ==================== */

    /**
     * 包就绪时改写 {@code Build} 静态字段。任何异常只记日志，绝不影响宿主启动
     * （原模块 {@code onPackageReady} 用 try/catchall 包住整体，此处同语义）。
     *
     * @param pkg 当前就绪的包名（仅用于日志）
     * @param cl  宿主 ClassLoader（供 {@link #logResolvedHost} 反射读域名用）
     */
    public static void hookAll(String pkg, ClassLoader cl) {
        try {
            int n = apply();
            XDFHook.logi(TAG, "spoofed " + pkg + ", fields=" + n
                    + ", now TYPE=" + Build.TYPE
                    + ", IS_USER=" + readField("IS_USER")
                    + ", TAGS=" + Build.TAGS
                    + ", FINGERPRINT=" + Build.FINGERPRINT);
            logResolvedHost(pkg, cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "spoof failed in " + pkg);
        }
    }

    /* ==================== 核心：按开关逐个字段改写 ==================== */

    /**
     * 逐项写入并返回成功数。顺序与原模块 {@code apply()} 完全一致：
     * 先豁免 hidden API，再 TYPE、IS_USER；TAGS/FINGERPRINT 两个开关在原 APK 的
     * {@code apply()} 内并未被调用（开关恒 false），此处保留同款结构以便后续打开。
     */
    private static int apply() throws Exception {
        exemptHiddenApi();
        int n = 0;
        if (SPOOF_TYPE) {
            n += trySet("TYPE", "user") ? 1 : 0;
        }
        if (SPOOF_IS_USER) {
            n += trySet("IS_USER", Boolean.TRUE) ? 1 : 0;
        }
        if (SPOOF_TAGS) {
            n += trySet("TAGS", "release-keys") ? 1 : 0;
        }
        if (SPOOF_FINGERPRINT) {
            n += spoofFingerprint() ? 1 : 0;
        }
        return n;
    }

    /**
     * 豁免 hidden API 限制（{@code Build.IS_USER} 是 @hide）。
     * <p>{@code VMRuntime.setHiddenApiExemptions(new String[]{""})} —— 空串前缀
     * 等于豁免全部。整段 catchall 吞异常：豁免失败时后续仍会尝试写入，
     * 是否成功交给路径 A/B 自己判定。</p>
     */
    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            setExemptions.invoke(runtime, new Object[]{new String[]{""}});
        } catch (Throwable ignored) {
        }
    }

    /** {@code Build.FINGERPRINT} 里的 {@code /userdebug/} -> {@code /user/}（原模块备用开关） */
    private static boolean spoofFingerprint() {
        String fp = Build.FINGERPRINT;
        if (fp == null || !fp.contains("/userdebug/")) {
            return false;
        }
        try {
            return setStatic("FINGERPRINT", fp.replace("/userdebug/", "/user/"));
        } catch (Throwable t) {
            XDFHook.logw(TAG, "FINGERPRINT spoof failed");
            return false;
        }
    }

    /* ==================== 双路径静态字段写入 ==================== */

    /** 改写单个字段：路径 A 反射直写 -> 失败转路径 B Unsafe 直写 -> 都失败才抛 */
    private static boolean setStatic(String name, Object value) throws Exception {
        Field f = Build.class.getDeclaredField(name);
        f.setAccessible(true);

        Throwable pathA = null;
        try {
            writeByReflection(f, value);
            XDFHook.logd(TAG, name + ": written by reflection (path A)");
            return true;
        } catch (Throwable t) {
            pathA = t;
        }

        try {
            writeByUnsafe(f, value);
            XDFHook.logd(TAG, name + ": written by Unsafe (path B), path A was: " + pathA);
            return true;
        } catch (Throwable t) {
            throw new Exception("both paths failed for Build." + name
                    + "; A=" + pathA + "; B=" + t, t);
        }
    }

    /**
     * 路径 A：直接 {@code set}/{@code setBoolean}。
     * <p><b>故意不碰 {@code Field.modifiers}</b> —— Android 10 (API 29) 的 libcore
     * 已移除该字段，任何「先去 final 再写」的套路在这里都会
     * {@code NoSuchFieldException}。libcore 对 static final 的 {@code Field.set}
     * 检查比 JDK 宽松，实测可直接写成功。</p>
     */
    private static void writeByReflection(Field f, Object value) throws Exception {
        if (value instanceof Boolean) {
            f.setBoolean(null, ((Boolean) value).booleanValue());
        } else {
            f.set(null, value);
        }
    }

    /** 路径 B：Unsafe 按「基址 + 偏移」直写内存，绕过 Field 的 final/权限检查 */
    private static void writeByUnsafe(Field f, Object value) throws Exception {
        Object base = unsafeStaticFieldBase(f);
        long offset = unsafeStaticFieldOffset(f);
        if (value instanceof Boolean) {
            unsafePutBoolean(base, offset, ((Boolean) value).booleanValue());
        } else {
            unsafePutObject(base, offset, value);
        }
    }

    /* ==================== Unsafe 反射封装（参数签名与原 APK 逐条一致） ==================== */

    /** 读 {@code sun.misc.Unsafe.theUnsafe} 单例；失败只记日志并返回 null */
    private static Object obtainUnsafe() {
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "sun.misc.Unsafe unavailable");
            return null;
        }
    }

    private static void requireUnsafe() {
        if (UNSAFE == null) {
            throw new IllegalStateException("sun.misc.Unsafe not available");
        }
    }

    /**
     * 单参 Unsafe 方法调用：
     * {@code UNSAFE.getClass().getMethod(name, argType).invoke(UNSAFE, arg)}。
     * 对应原 APK 的 {@code unsafeCall(String, Class, Object)}，签名与语义一致。
     */
    private static Object unsafeCall(String name, Class<?> argType, Object arg) throws Exception {
        Method m = UNSAFE.getClass().getMethod(name, new Class<?>[]{argType});
        return m.invoke(UNSAFE, new Object[]{arg});
    }

    private static Object unsafeStaticFieldBase(Field f) throws Exception {
        requireUnsafe();
        return unsafeCall("staticFieldBase", Field.class, f);
    }

    private static long unsafeStaticFieldOffset(Field f) throws Exception {
        requireUnsafe();
        Object r = unsafeCall("staticFieldOffset", Field.class, f);
        return ((Long) r).longValue();
    }

    /** {@code Unsafe.putBoolean(Object base, long offset, boolean value)} */
    private static void unsafePutBoolean(Object base, long offset, boolean value) throws Exception {
        requireUnsafe();
        Class<?>[] sig = new Class<?>[]{Object.class, long.class, boolean.class};
        Method m = UNSAFE.getClass().getMethod("putBoolean", sig);
        m.invoke(UNSAFE, new Object[]{base, Long.valueOf(offset), Boolean.valueOf(value)});
    }

    /** {@code Unsafe.putObject(Object base, long offset, Object value)} */
    private static void unsafePutObject(Object base, long offset, Object value) throws Exception {
        requireUnsafe();
        Class<?>[] sig = new Class<?>[]{Object.class, long.class, Object.class};
        Method m = UNSAFE.getClass().getMethod("putObject", sig);
        m.invoke(UNSAFE, new Object[]{base, Long.valueOf(offset), value});
    }

    /* ==================== 诊断 ==================== */

    /** 单字段写入 + 吞异常（返回是否成功），日志文案与原模块一致 */
    private static boolean trySet(String name, Object value) {
        try {
            return setStatic(name, value);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "field " + name + " spoof failed");
            return false;
        }
    }

    /** 读回字段用于日志；读不到返回 {@code <absent:name>}（原模块同款文案） */
    private static String readField(String name) {
        try {
            Field f = Build.class.getDeclaredField(name);
            f.setAccessible(true);
            return String.valueOf(f.get(null));
        } catch (Throwable t) {
            return "<absent:" + name + ">";
        }
    }

    /**
     * 改完之后把「该 app 实际会连的域名」打出来做硬证据验证
     * （对应原模块 {@code logResolvedHost} 的意图，原方法 105 指令 / 40 次 invoke）。
     *
     * <p>原 APK 里明确硬编码了 {@code cn.xdf.appstore.base.BaseUrlKt} +
     * {@code getBASE_URL} + {@code " -> BaseUrlKt.BASE_URL = "}；
     * 此处按同一思路覆盖 3 个目标 app —— <b>逐 app 的反射字段名是按各自逆向结论补的
     * （见类头注释），并非逐行来自 APK</b>。读不到只打一行提示，不影响主流程。</p>
     */
    private static void logResolvedHost(String pkg, ClassLoader cl) {
        try {
            String host;
            if ("cn.xdf.appstore".equals(pkg)) {
                // BaseUrlKt.BASE_URL 是 lazy<String>，读 getBASE_URL() 展开后的值
                host = "BaseUrlKt.BASE_URL = "
                        + readLazyString(cl, "cn.xdf.appstore.base.BaseUrlKt", "getBASE_URL");
            } else if ("cn.xdf.updater".equals(pkg)) {
                // Device 是 Kotlin object：getDebug() 是实例方法，receiver 取 INSTANCE
                host = "Device.debug = "
                        + readKotlinObjectMember(cl, "cn.xdf.updater.common.Device", "getDebug");
            } else if ("cn.xdf.zeus".equals(pkg)) {
                host = "BaseUrlHelper = "
                        + readStaticString(cl, "cn.xdf.zeus.baseUrl.BaseUrlHelper", "managerHost");
            } else {
                host = "(host not probed for " + pkg + ")";
            }
            XDFHook.logi(TAG, pkg + " -> " + host);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "logResolvedHost failed for " + pkg + ": " + t);
        }
    }

    /** 反射取 {@code lazy<String>}（属性或 getter），再展开 {@code getValue()} */
    private static String readLazyString(ClassLoader cl, String cls, String getter) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Object lazy;
            try {
                Method g = c.getDeclaredMethod(getter);
                g.setAccessible(true);
                lazy = g.invoke(null);
            } catch (NoSuchMethodException e) {
                Field f = c.getDeclaredField("BASE_URL");
                f.setAccessible(true);
                lazy = f.get(null);
            }
            if (lazy == null) {
                return "(null)";
            }
            try {
                Method gv = lazy.getClass().getMethod("getValue");
                gv.setAccessible(true);
                return String.valueOf(gv.invoke(lazy));
            } catch (NoSuchMethodException e) {
                return String.valueOf(lazy);
            }
        } catch (Throwable t) {
            return "(unresolved: " + t + ")";
        }
    }

    /** 反射读 Kotlin object（{@code INSTANCE} 单例）上的无参 getter */
    private static String readKotlinObjectMember(ClassLoader cl, String cls, String getter) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Field inst = c.getDeclaredField("INSTANCE");
            inst.setAccessible(true);
            Object instance = inst.get(null);
            Method m = c.getDeclaredMethod(getter);
            m.setAccessible(true);
            return String.valueOf(m.invoke(instance));
        } catch (Throwable t) {
            return "(unresolved: " + t + ")";
        }
    }

    /** 反射读静态字段 */
    private static String readStaticString(ClassLoader cl, String cls, String field) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Field f = c.getDeclaredField(field);
            f.setAccessible(true);
            return String.valueOf(f.get(null));
        } catch (Throwable t) {
            return "(unresolved: " + t + ")";
        }
    }
}
