package cn.cf3012.xdf;

import android.content.Context;
import android.os.UserManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * UsbMountHooks — 解除「OTG U 盘插上不识别」的系统级挂载禁令。
 *
 * <p>由独立 PoC 模块 UsbMountFix（2026-10-08）整合而来。开关：
 * {@link AppConfig#H_USB_MOUNT_FIX}（scope: system）。system_server 单元，
 * 装完需重启 framework 才生效（按铁律不建议重启系统，由用户自行选择时机）。</p>
 *
 * <p><b>根因（MT2 双侧 smali diff 实锤，XDF_framework vc29）</b>：
 * U 盘枚举正常 → blkid/vfat/vold 全正常（DiskInfo USB,label=KINGSTON 已建），
 * 但 StorageManagerService 打出 {@code Ignoring mount public:8,49 due to policy}，
 * 两分区全部 UNMOUNTED —— 卡死在 H_VOLUME_MOUNT 分支的
 * {@code isMountDisallowed(vol)} 检查。</p>
 *
 * <pre>
 * // AOSP-Q 原版（isMountDisallowed 内）：
 * if (vol.disk != null && vol.disk.isUsb()) {
 *     isUsbRestricted = um.hasUserRestriction("no_usb_file_transfer", callingUser);
 * }
 * // XDF ROM（line 1612-1617，检查被删）：
 * if (vol.disk != null && vol.disk.isUsb()) {
 *     isUsbRestricted = true;      // ← const/4 v1, 0x1，硬编码，USB 盘无条件拒绝挂载（防拷资料）
 * }
 * </pre>
 *
 * 方法其余部分（no_physical_media 那段）与 AOSP 逐条一致，改动仅此一处。
 * 实机 /data/system/users/0.xml {@code <restrictions/>} 为空 → 没有任何用户限制参与，
 * 纯硬编码拦截。</p>
 *
 * <p><b>修复策略</b>：hook {@code StorageManagerService.isMountDisallowed(VolumeInfo)Z}，
 * 不直接恒 false，而是**恢复 AOSP 原版逻辑**：USB 盘只查 no_usb_file_transfer、
 * type∈{PRIVATE,PUBLIC,STUB} 只查 no_physical_media（均为当前实测为空的标准用户限制）。
 * 修掉硬编码的同时不吞掉原生策略 —— 日后真有 MDM 设置这些限制仍会被遵守。</p>
 *
 * <p><b>安全边界</b>：PROTECTIVE 模式；hooker 强引用保活；恢复逻辑任何反射失败都
 * fallback 放行（return false）+ 打一次日志；每次挂载打印 原判定 vs 修复后判定，
 * logcat 可验证（{@code vol=... orig=true -> aosp=false (allow)}）。
 * 不触碰 vold/分区/文件系统，零磁盘操作。</p>
 */
final class UsbMountHooks {

    private static final String TAG = "UsbMountFix";

    /** AOSP 原版参与判定的 VolumeInfo.type 取值：0=PRIVATE 1=PUBLIC 5=STUB */
    private static final int[] TYPES_CHECKING_NO_PHYSICAL_MEDIA = {0, 1, 5};

    /** 同一告警只打一次，避免刷屏 */
    private static final Set<String> sLoggedOnce =
            java.util.Collections.synchronizedSet(new LinkedHashSet<String>());

    private UsbMountHooks() {
    }

    /** system_server 入口（XDFHook.onSystemServerStarting 调用） */
    static void hookSystemServer(ClassLoader cl) {
        safeHook("isMountDisallowed", () -> hookIsMountDisallowed(cl));
        XDFHook.logi(TAG, "all hooks installed");
    }

    /**
     * 核心且唯一的 hook：恢复 isMountDisallowed 的 AOSP 原版逻辑。
     * 每次挂载请求都会经过这里，日志 {@code orig=... -> aosp=...} 即验证凭据。
     */
    private static void hookIsMountDisallowed(ClassLoader cl) throws Exception {
        Class<?> sms = cl.loadClass("com.android.server.StorageManagerService");
        Class<?> volCls = cl.loadClass("android.os.storage.VolumeInfo");
        Method m = findMethodByParamType(sms, "isMountDisallowed", volCls);
        if (m == null) {
            throw new NoSuchMethodException(
                    "isMountDisallowed(android.os.storage.VolumeInfo) not found");
        }
        m.setAccessible(true);

        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> {
            // 原判定（XDF 硬编码版）——只用于日志对照，无副作用
            Object orig = chain.proceed();
            boolean restored;
            try {
                restored = computeAospVerdict(chain.getThisObject(), chain.getArg(0));
            } catch (Throwable t) {
                // 恢复失败兜底：放行（比原版的无条件拦更符合模块意图）
                logOnce("WARN computeAospVerdict failed, allow anyway: " + t);
                restored = false;
            }
            try {
                XDFHook.logi(TAG, "vol=" + volId(chain.getArg(0))
                        + " orig=" + orig + " -> aosp=" + restored
                        + (restored ? " (deny)" : " (allow)"));
            } catch (Throwable ignored) {
            }
            return restored;
        });
        XDFHook.logi(TAG, "hook isMountDisallowed installed on " + m);
    }

    /**
     * AOSP-Q 原版 {@code isMountDisallowed} 的忠实重建：
     * <pre>
     * boolean usbRestricted = false;
     * if (vol.disk != null && vol.disk.isUsb())
     *     usbRestricted = um.hasUserRestriction("no_usb_file_transfer");
     * boolean typeRestricted = false;
     * if (vol.type ∈ {0,1,5})
     *     typeRestricted = um.hasUserRestriction("no_physical_media");
     * return usbRestricted || typeRestricted;
     * </pre>
     */
    private static boolean computeAospVerdict(Object service, Object vol) throws Exception {
        if (service == null || vol == null) {
            return false;
        }

        // mContext（private 字段，沿类链查）
        Object ctxObj = getFieldQuietly(service, "mContext");
        if (!(ctxObj instanceof Context)) {
            logOnce("WARN mContext not found");
            return false;
        }
        UserManager um = ((Context) ctxObj).getSystemService(UserManager.class);
        if (um == null) {
            logOnce("WARN UserManager unavailable");
            return false;
        }

        // --- isUsbRestricted：USB 盘只认 no_usb_file_transfer（原版被改成恒 true） ---
        boolean usbRestricted = false;
        Object disk = getFieldQuietly(vol, "disk");
        if (disk != null) {
            Method isUsb = findMethodByParamType(disk.getClass(), "isUsb", null);
            if (isUsb != null) {
                isUsb.setAccessible(true);
                if (Boolean.TRUE.equals(isUsb.invoke(disk))) {
                    usbRestricted = um.hasUserRestriction("no_usb_file_transfer");
                }
            } else {
                logOnce("WARN DiskInfo.isUsb() not found");
            }
        }

        // --- isTypeRestricted：AOSP 原生段，ROM 未动，照原样保留 ---
        boolean typeRestricted = false;
        Object typeObj = getFieldQuietly(vol, "type");
        if (typeObj instanceof Integer) {
            int type = (Integer) typeObj;
            for (int t : TYPES_CHECKING_NO_PHYSICAL_MEDIA) {
                if (type == t) {
                    typeRestricted = um.hasUserRestriction("no_physical_media");
                    break;
                }
            }
        }

        return usbRestricted || typeRestricted;
    }

    private static String volId(Object vol) {
        try {
            Method getId = findMethodByParamType(vol.getClass(), "getId", null);
            if (getId != null) {
                getId.setAccessible(true);
                return String.valueOf(getId.invoke(vol));
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /* ==================== 隔离器 ==================== */

    private interface Thrower {
        void run() throws Exception;
    }

    /** 单个 hook 失败只记日志，绝不影响 system_server 启动与其余 hook */
    private static void safeHook(String name, Thrower t) {
        try {
            t.run();
        } catch (Throwable e) {
            XDFHook.logw(TAG, "FAILED hook " + name + ": " + e);
        }
    }

    /* ==================== 反射工具 ==================== */

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

    /**
     * 按方法名 + 形参类型精确匹配（paramType==null 时匹配无参方法），沿继承链查找。
     *
     * <p>★ 必须精确比对形参类型（而非参数个数）：按个数匹配曾撞上重载导致
     * IllegalArgumentException、修复静默失效（UsbAuthFix 血泪教训）。</p>
     */
    private static Method findMethodByParamType(Class<?> cls, String name, Class<?> paramType) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    if (paramType == null) {
                        if (ps.length == 0) {
                            return m;
                        }
                    } else if (ps.length == 1 && paramType.equals(ps[0])) {
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /* ==================== 日志 ==================== */

    private static void logOnce(String msg) {
        try {
            if (sLoggedOnce.add(msg)) {
                XDFHook.logi(TAG, msg);
            }
        } catch (Throwable ignored) {
        }
    }
}
