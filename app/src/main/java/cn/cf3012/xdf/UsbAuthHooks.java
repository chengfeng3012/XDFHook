package cn.cf3012.xdf;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * UsbAuthHooks — 修 Android 10 上「USB 设备授权弹窗不出现 / 一调没反应」的缺陷。
 *
 * <p>整合自独立模块 {@code cn.cf3012.usbauthfix}（UsbAuthFixModule），
 * 改写为 XDFHook 子模块形式：hook 走 {@link XDFHook#hook}、日志走
 * {@link XDFHook#logi}、反射自持，配置走 {@link AppConfig} 的 per-scope 开关。</p>
 *
 * <p><b>逆向结论（MT2 实测，2026-10-01）</b>
 * USB 授权链路 <b>没有被厂商阉割</b>（{@code config_disableUsbPermissionDialogs}=false；
 * {@code UsbPermissionManager.requestPermissionDialog} 与 AOSP-G 的内容哈希
 * {@code df0ce38657cb6776} 逐字节一致；SystemUI {@code UsbPermissionActivity}
 * onCreate/onDestroy 指令数一致；{@code shouldAbortBackgroundActivityStart}
 * 两侧均 297 条指令）。真正的缺陷在 A10 上游：</p>
 *
 * <p><b>★ 核心（实机已验证修复成功）</b><br>
 * {@code UsbUserSettingsManager.isCameraPermissionGranted(pkg, uid)} 对
 * UVC 摄像头（USB_CLASS_VIDEO=14）用 framework 包自己的 Context
 * （{@code mUserContext = createPackageContextAsUser("android", ...)}）
 * 调 {@code checkCallingPermission(CAMERA)}。该判定依赖
 * {@code Binder.getCallingUid()}，在 binder 链路上取到的并非调用方 app，
 * 于是<b>即使 app 真的持有 CAMERA（dumpsys: granted=true）也恒返回 DENIED</b>，
 * 进而走
 * {@code if (isCameraDevicePresent && !isCameraPermissionGranted) { pi.send(permission=0); return; }}
 * 分支 —— 根本不会调 {@code requestPermissionDialog}，弹窗不出现且无任何异常。<br>
 * A11 的官方修法是改成按包名查 PMS，本 hook 采用同一思路，但<b>只修 bug 不放宽安全</b>：
 * 原判定为 false 时用 {@code mPackageManager.checkPermission(CAMERA, packageName)}
 * 复核，<b>复核 GRANTED 才修正为 true，复核 DENIED 保持 false</b>
 * —— 真没摄像头权限的 app 照样被拒。</p>
 *
 * <p><b>次因（A10 的 BAL 静默拦截）</b><br>
 * {@code requestPermissionDialog} 发起弹窗时带 {@code FLAG_ACTIVITY_NEW_TASK}
 * 且无任何豁免，必然进 {@code setTaskFromReuseOrCreateNewTask}，而该方法开头
 * {@code if (mRestrictedBgActivity) return START_ABORTED;}。且失败<b>完全静默</b>
 * （全方法只 catch ActivityNotFoundException），调用方 {@code UsbManager.requestPermission()}
 * 是 void，PendingIntent 永不回调 → 表现就是「调了没反应」。<br>
 * 另一处：XDF 基线早于上游修复，缺 {@code mAvoidMoveToFront} 的 toTop 计算
 * （instructionCount XDF=109 / AOSP-G=96，是缺一段业务逻辑而非被改成常量），
 * 新 task 创建后可能不置顶。</p>
 *
 * <p><b>四个 hook（全在 system_server）</b></p>
 * <ol>
 *   <li>{@code ActivityStarter.setTaskFromReuseOrCreateNewTask(TaskRecord)I}
 *       —— 组件白名单命中时，before 把 {@code mRestrictedBgActivity} 置 false
 *       跳过 BAL abort；after 按返回码分流，START_SUCCESS(0)/DELIVERED_TO_TOP(-1)
 *       才补 {@code moveToFront}。
 *       <b>★ LibXposed 同一 Executable 只能挂一次 hook</b>（后注册者静默失效
 *       且不报错），所以放行与置顶必须合并在这一个 hook 体内。</li>
 *   <li>{@code ActivityStarter.handleBackgroundActivityAbort(ActivityRecord)Z}
 *       —— 对 USB 弹窗直接返 false。独立 Executive，作 BAL 的兜底，
 *       不依赖任何字段名，比 ① 更抗 ROM 差异。</li>
 *   <li>{@code UsbUserSettingsManager.isCameraPermissionGranted(String,int)Z}
 *       —— 核心修复，见上。</li>
 *   <li>诊断组（开关 {@link AppConfig#hSystemUsbDiag}，默认关）：
 *       {@code requestPermission(UsbDevice,...)} 与
 *       {@code requestPermissionDialog(...)} 的到达性/来源日志，限流 1.5s。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：① ② 全部带组件白名单门控（只认
 * {@code com.android.systemui.usb.UsbPermissionActivity}），不会放行其它应用
 * 从后台拉起任意 Activity；③ 只在框架「查错了对象」时修正，不放宽 UVC 隐私保护。
 * 全部 PROTECTIVE + safeHook 隔离 + hooker 强引用保活，单 hook 失败只记日志。</p>
 *
 * <p><b>未处理</b>：{@code UsbPermissionActivity} 没有 setShowWhenLocked，
 * 服务端也无「解锁后再弹」的排队逻辑 → 锁屏时调用同样不弹（属上游设计）。</p>
 */
final class UsbAuthHooks {

    private static final String TAG = "UsbAuth";

    /** 唯一目标组件：SystemUI 的 USB 授权弹窗 */
    private static final String TARGET_PKG = "com.android.systemui";
    private static final String TARGET_CLS = "com.android.systemui.usb.UsbPermissionActivity";

    /* ==================== 分层修复开关（内部结构，UI 只暴露总开关+诊断） ==================== */

    /** ① 放行 BAL（mRestrictedBgActivity=false）+ 补 moveToFront */
    private static final boolean FIX_BAL_AND_FOREGROUND = true;
    /** ② 兜底放行 BAL（handleBackgroundActivityAbort 对 USB 弹窗返 false） */
    private static final boolean FIX_BG_ABORT = true;
    /** ③ 核心：修正 A10 的 UVC/CAMERA 误判 */
    private static final boolean FIX_UVC_CAMERA_MISJUDGE = true;

    /** 日志限流，避免热路径刷屏拖慢 system_server */
    private static volatile long sLastDiagAt = 0L;
    private static final long DIAG_INTERVAL_MS = 1500L;

    /** 同一告警只打一次（热路径轮询 hasPermission 会反复触发） */
    private static final Set<String> sLoggedOnce =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private UsbAuthHooks() {
    }

    private interface HookStep {
        void run() throws Exception;
    }

    /**
     * 安装全部 USB 授权修复 hook。
     *
     * @param cl   system_server 的 ClassLoader
     * @param diag 是否启用诊断日志（默认关，见 {@link AppConfig#hSystemUsbDiag}）
     */
    static void hookAll(ClassLoader cl, boolean diag) {
        if (FIX_BAL_AND_FOREGROUND) {
            guard("usbDialogStarter", () -> hookUsbDialogStarter(cl));
        }
        if (FIX_BG_ABORT) {
            guard("bgActivityAbort", () -> hookBackgroundActivityAbort(cl));
        }
        if (FIX_UVC_CAMERA_MISJUDGE) {
            guard("uvcCameraMisjudge", () -> hookUvcCameraMisjudge(cl));
        }
        if (diag) {
            guard("diagUsb", () -> hookDiagnostics(cl));
        }
        XDFHook.logi(TAG, "all hooks installed (bal=" + FIX_BAL_AND_FOREGROUND
                + ", abort=" + FIX_BG_ABORT + ", uvc=" + FIX_UVC_CAMERA_MISJUDGE
                + ", diag=" + diag + ")");
    }

    /* ==================== ① 放行 BAL + 强制置顶 ==================== */

    /**
     * <b>★ LibXposed 同一 Executable 只能挂一次 hook</b>，所以「放行」与「置顶」
     * 必须合并在这一个 hook 体内，分别用 before / after 实现。
     *
     * <p>before：白名单命中 → {@code mRestrictedBgActivity = false}，
     * 使方法开头整段 BAL abort 分支跳过（原方法一进来就读这个字段）。
     * after：proceed 返回 START_SUCCESS(0) / DELIVERED_TO_TOP(-1) 时补 moveToFront，
     * 弥补 XDF 基线缺失的 toTop 计算。</p>
     */
    private static void hookUsbDialogStarter(ClassLoader cl) throws Exception {
        Class<?> starter = cl.loadClass("com.android.server.wm.ActivityStarter");
        Method m = findMethod(starter, "setTaskFromReuseOrCreateNewTask", 1);
        if (m == null) {
            throw new NoSuchMethodException("setTaskFromReuseOrCreateNewTask(1) not found");
        }
        XDFHook.hook(m, chain -> {
            Object self = chain.getThisObject();
            boolean hit = isUsbPermissionActivity(self);

            if (hit && FIX_BAL_AND_FOREGROUND) {
                // 必须在 proceed 之前改：原方法开头就读这个字段
                if (setBooleanFieldQuietly(self, "mRestrictedBgActivity", false)) {
                    logDiag(true, "BAL released: mRestrictedBgActivity -> false");
                } else {
                    XDFHook.logw(TAG, "mRestrictedBgActivity not found (ROM variant?)");
                }
            }

            Object r = chain.proceed();

            if (hit && r instanceof Integer) {
                int code = (Integer) r;
                if (code == 0 || code == -1) {
                    boolean moved = forceMoveToFront(self);
                    logDiag(true, "start ok code=" + code + ", moveToFront=" + moved);
                } else {
                    // 0x66 = START_ABORTED(102)
                    logDiag(true, "start code=" + code
                            + (code == 0x66 ? " (START_ABORTED - BAL still blocking)" : ""));
                }
            }
            return r;
        });
        XDFHook.logi(TAG, "hookUsbDialogStarter on " + m);
    }

    /* ==================== ② 兜底放行 BAL ==================== */

    /**
     * {@code handleBackgroundActivityAbort} 是 BAL 的最终裁决点，返 true 即 abort。
     * 对 USB 弹窗直接返 false —— 不依赖任何字段名，比 ① 更抗 ROM 差异。
     * 独立 Executive，与 ① 无冲突。
     */
    private static void hookBackgroundActivityAbort(ClassLoader cl) throws Exception {
        Class<?> starter = cl.loadClass("com.android.server.wm.ActivityStarter");
        Method m = findMethod(starter, "handleBackgroundActivityAbort", 1);
        if (m == null) {
            throw new NoSuchMethodException("handleBackgroundActivityAbort(1) not found");
        }
        XDFHook.hook(m, chain -> {
            Object rec = chain.getArg(0);
            if (isUsbPermissionRecord(rec)) {
                logDiag(true, "handleBackgroundActivityAbort -> allow USB dialog");
                return Boolean.FALSE;
            }
            return chain.proceed();
        });
        XDFHook.logi(TAG, "hookBackgroundActivityAbort on " + m);
    }

    /* ==================== ③ 核心：UVC/CAMERA 误判修正 ==================== */

    /**
     * A10 的 {@code UsbUserSettingsManager.requestPermission()} 里：
     * <pre>{@code
     * if (!hasPermission(device, pkg, uid)) {
     *     if (isCameraDevicePresent(device)          // class 14 = USB_CLASS_VIDEO
     *             && !isCameraPermissionGranted(pkg, uid)) {
     *         pi.send(..., putExtra(EXTRA_PERMISSION_GRANTED, false));
     *         return;                                // ★ 根本不走弹窗
     *     }
     *     requestPermissionDialog(...);
     * }
     * }</pre>
     *
     * <p>本 hook 只在<b>框架查错了对象</b>时修正：用
     * {@code mPackageManager.checkPermission(CAMERA, packageName)} 复核
     * （按包名查 PMS，不依赖 binder 身份 —— 这也是 A11 的官方修法）。
     * 复核 DENIED 时维持原判，app 真没摄像头权限照样被拒。</p>
     *
     * <p>★踩坑（记忆：按参数个数匹配会撞重载）：早先用「按个数」匹配 checkPermission
     * 撞上 {@code checkPermission(String,String)}（第二参是 packageName 而非 uid），
     * 运行时抛 {@code IllegalArgumentException: argument 2 has type
     * java.lang.String, got java.lang.Integer} → 复核恒 null → 修复静默失效。
     * <b>所以必须比对真实形参类型</b>。另：误以为 A10 有 uid 版
     * {@code checkPermission(String,int)} 也会失效 —— 那个 int 是 pid 语义。</p>
     */
    private static void hookUvcCameraMisjudge(ClassLoader cl) throws Exception {
        Class<?> usm = cl.loadClass("com.android.server.usb.UsbUserSettingsManager");
        Method icp = findMethodByName(usm, "isCameraPermissionGranted");
        if (icp == null) {
            throw new NoSuchMethodException("isCameraPermissionGranted not found");
        }
        XDFHook.hook(icp, chain -> {
            Object pkgArg = icp.getParameterCount() > 0 ? chain.getArg(0) : null;
            Object uidArg = icp.getParameterCount() > 1 ? chain.getArg(1) : null;
            Object selfObj = chain.getThisObject();

            Object r = chain.proceed();
            boolean granted = Boolean.TRUE.equals(r);

            if (!granted && FIX_UVC_CAMERA_MISJUDGE && pkgArg instanceof String) {
                Boolean real = recheckCameraPermission(selfObj, (String) pkgArg);
                if (Boolean.TRUE.equals(real)) {
                    // logOnce：高危热路径（App 反复轮询 hasPermission），同包只打一次
                    logOnce("[uvc] FIXED: pkg=" + pkgArg
                            + " 框架 checkCallingPermission 误判，但该包实际持有 CAMERA"
                            + " → 修正为 true（修 A10 bug，未放宽安全）");
                    return Boolean.TRUE;
                }
                logDiag(true, "[uvc] recheck pkg=" + pkgArg + " CAMERA=" + real + " → 维持 false");
            }

            try {
                logDiag(true, "[uvc] isCameraPermissionGranted pkg=" + pkgArg
                        + " uid=" + uidArg + " -> " + granted
                        + (granted ? ""
                        : "  ==> BLOCKED: A10 会直接回 permission=false，不弹窗"));
            } catch (Throwable ignored) {
            }
            return r;
        });
        XDFHook.logi(TAG, "hookUvcCameraMisjudge on " + icp);
    }

    /**
     * 用 {@code mPackageManager} 以包名复核 CAMERA 权限的真实状态。
     *
     * <p>这是本模块唯一「放行」入口，但<b>不降低安全性</b>：只有框架查错了对象
     * 而 app 确实持有 CAMERA 时才修正为 true。</p>
     *
     * @return TRUE=复核持有, FALSE=复核不持有, null=复核失败
     */
    private static Boolean recheckCameraPermission(Object self, String packageName) {
        try {
            Object pm = getFieldQuietly(self, "mPackageManager");
            if (!(pm instanceof PackageManager)) {
                return null;
            }
            Method m = findMethod(pm.getClass(), "checkPermission",
                    String.class, String.class);
            if (m == null) {
                logOnce("WARN checkPermission(String,String) not found");
                return null;
            }
            Object res = m.invoke(pm, "android.permission.CAMERA", packageName);
            if (!(res instanceof Number)) {
                return null;
            }
            return ((Number) res).intValue() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            logOnce("WARN recheckCameraPermission failed: " + t);
            return null;
        }
    }

    /* ==================== 诊断组（默认关） ==================== */

    /**
     * 4a. {@code requestPermission(UsbDevice,...)} —— 入口，打印设备是不是 UVC。<br>
     * 4b. {@code requestPermissionDialog(...)} —— 若这行日志<b>根本不出现</b>，
     * 说明请求根本没到达 system_server（调用方没走到 requestPermission
     * 或 PendingIntent 已失效），与本模块无关。
     *
     * <p>两者分属不同 Executable，同一 Executable 只能挂一次 hook，故分开注册。</p>
     */
    private static void hookDiagnostics(ClassLoader cl) throws Exception {
        Class<?> usm = cl.loadClass("com.android.server.usb.UsbUserSettingsManager");

        Method rp = findMethod(usm, "requestPermission", 4);
        if (rp != null) {
            XDFHook.hook(rp, chain -> {
                try {
                    Object dev = chain.getArg(0);
                    Object pkg = chain.getArg(1);
                    boolean isCam = isUsbVideoClass(dev);
                    logDiag(true, "[usb] requestPermission pkg=" + pkg
                            + " device=" + dev
                            + (isCam ? "  <-- USB_CLASS_VIDEO(UVC)!" : ""));
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            XDFHook.logi(TAG, "diag hookUvcRequestPermission on " + rp);
        }

        Class<?> upm = cl.loadClass("com.android.server.usb.UsbPermissionManager");
        Method rpd = findMethodByName(upm, "requestPermissionDialog");
        if (rpd != null) {
            XDFHook.hook(rpd, chain -> {
                try {
                    Object device = chain.getArg(0);
                    Object pkg = rpd.getParameterCount() > 3 ? chain.getArg(3) : "?";
                    Object uid = rpd.getParameterCount() > 4 ? chain.getArg(4) : "?";
                    logDiag(true, "[usb] requestPermissionDialog from pkg=" + pkg
                            + " uid=" + uid + " device=" + device);
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            XDFHook.logi(TAG, "diag hookRequestPermissionDialog on " + rpd);
        }
    }

    /* ==================== 组件白名单判定 ==================== */

    /**
     * ActivityStarter 的 mStartActivity（ActivityRecord）是否就是 USB 授权弹窗。
     * mStartActivity 声明在 ActivityStarter，info 声明在 ActivityRecord，
     * 所以两处都要沿类链查字段。
     */
    private static boolean isUsbPermissionActivity(Object starter) {
        if (starter == null) {
            return false;
        }
        return isUsbPermissionRecord(getFieldQuietly(starter, "mStartActivity"));
    }

    /** ActivityRecord.info 是否指向 UsbPermissionActivity */
    private static boolean isUsbPermissionRecord(Object record) {
        if (record == null) {
            return false;
        }
        Object info = getFieldQuietly(record, "info");
        if (!(info instanceof ActivityInfo)) {
            return false;
        }
        ActivityInfo ai = (ActivityInfo) info;
        return TARGET_PKG.equals(ai.packageName) && TARGET_CLS.equals(ai.name);
    }

    /* ==================== UVC 判定 ==================== */

    /** 该 UsbDevice 是否含 USB_CLASS_VIDEO(14) 接口（= UVC 摄像头） */
    private static boolean isUsbVideoClass(Object device) {
        try {
            if (device == null) {
                return false;
            }
            Method getDeviceClass = findMethodByName(device.getClass(), "getDeviceClass");
            if (getDeviceClass != null) {
                getDeviceClass.setAccessible(true);
                if (((Number) getDeviceClass.invoke(device)).intValue() == 0x0e) {
                    return true;
                }
            }
            Method getInterfaceCount = findMethodByName(device.getClass(), "getInterfaceCount");
            Method getInterface = findMethodByName(device.getClass(), "getInterface");
            if (getInterfaceCount == null || getInterface == null) {
                return false;
            }
            int n = ((Number) getInterfaceCount.invoke(device)).intValue();
            for (int i = 0; i < n; i++) {
                Object itf = getInterface.invoke(device, i);
                if (itf == null) {
                    continue;
                }
                Method getInterfaceClass = findMethodByName(itf.getClass(), "getInterfaceClass");
                if (getInterfaceClass != null
                        && ((Number) getInterfaceClass.invoke(itf)).intValue() == 0x0e) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ==================== 置顶 ==================== */

    /**
     * 补上 XDF 基线缺失的置顶效果：把 mDoResume 置 true 后调
     * {@code mTargetStack.moveToFront(String)}（原方法末尾同样走这条路径，
     * 只是 XDF 少了 mAvoidMoveToFront 分支导致 toTop 可能为 false）。
     *
     * <p>必须在 proceed 之后调用：此时 mTargetStack 已由 computeStackFocus 赋值。</p>
     */
    private static boolean forceMoveToFront(Object starter) {
        try {
            setBooleanFieldQuietly(starter, "mDoResume", true);
            Object stack = getFieldQuietly(starter, "mTargetStack");
            if (stack == null) {
                return false;
            }
            Method mtf = findMethod(stack.getClass(), "moveToFront", String.class);
            if (mtf == null) {
                return false;
            }
            mtf.setAccessible(true);
            mtf.invoke(stack, "XDFHook");
            return true;
        } catch (Throwable t) {
            XDFHook.logw(TAG, "forceMoveToFront failed: " + t);
            return false;
        }
    }

    /* ==================== 隔离器 ==================== */

    private static void guard(String name, HookStep step) {
        try {
            step.run();
        } catch (Throwable e) {
            XDFHook.logw(TAG, "hook FAILED: " + name + " -> " + e);
        }
    }

    /* ==================== 反射工具（沿类链） ==================== */

    private static Field resolveField(String name, Class<?> cls) {
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

    private static Object getFieldQuietly(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            Field f = resolveField(name, obj.getClass());
            return f == null ? null : f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setBooleanFieldQuietly(Object obj, String name, boolean v) {
        try {
            Field f = resolveField(name, obj.getClass());
            if (f == null) {
                return false;
            }
            f.setBoolean(obj, v);
            return true;
        } catch (Throwable t) {
            XDFHook.logw(TAG, "setBoolean(" + name + ") failed: " + t);
            return false;
        }
    }

    /** 按名字找方法（取首个匹配重载） */
    private static Method findMethodByName(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 按名字 + 形参类型找方法（比对真实类型，不只比个数） */
    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) {
                        continue;
                    }
                    Class<?>[] pt = m.getParameterTypes();
                    if (params != null && params.length > 0) {
                        if (pt.length != params.length) {
                            continue;
                        }
                        boolean same = true;
                        for (int i = 0; i < pt.length; i++) {
                            if (pt[i] != params[i]) {
                                same = false;
                                break;
                            }
                        }
                        if (!same) {
                            continue;
                        }
                    }
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 按参数个数找方法（private 方法签名在不同 ROM 上可能微调） */
    private static Method findMethod(Class<?> cls, String name, int arity) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == arity) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /* ==================== 日志 ==================== */

    /** 限流日志（1.5s 一条），enabled=false 时完全静默 */
    private static void logDiag(boolean enabled, String msg) {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sLastDiagAt < DIAG_INTERVAL_MS) {
            return;
        }
        sLastDiagAt = now;
        XDFHook.logi(TAG, msg);
    }

    /** 同一告警只打一次，避免热路径刷屏 */
    private static void logOnce(String msg) {
        try {
            if (sLoggedOnce.add(msg)) {
                XDFHook.logi(TAG, msg);
            }
        } catch (Throwable ignored) {
        }
    }
}