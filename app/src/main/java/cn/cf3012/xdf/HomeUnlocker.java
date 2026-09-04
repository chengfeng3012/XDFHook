package cn.cf3012.xdf;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Binder;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

/**
 * HomeUnlocker — 解除 XDF 对 HOME 键（默认桌面 cn.xdf.pad.launcher）的强制锁定。
 *
 * 与旧独立模块 XdfUnlock（cn.cf3012.xdf.launcherhook，PMS 一刀切拦写入）的区别：
 * 它连用户手动设置 XDF 桌面为默认的合法写入也拦掉了（实测踩坑）。
 *
 * 本类的设计原则：拦"ROM 的强制写入行为"，不拦"preferred 状态本身"——
 * 状态归用户控制：
 *
 *  【framework 源头】ResolverActivity.setDefaultLauncher -> 不执行
 *      （已有，见 ZeusUnlocker A3；拦掉每次 HOME intent 的强制重写主路径）
 *
 *  【launcher3 进程】Launcher.setXdfDefaultHomeLauncher() -> block
 *      ROM 在 Launcher3 里埋的"把默认桌面设为 XDF 桌面"私有逻辑，
 *      不是用户功能入口，block 无副作用。按名 hook 所有重载、沿继承链。
 *
 *  【system_server】PMS preferred 写入 -> 只读诊断，不拦截！
 *      只观察：凡是目标指向 cn.xdf.pad.launcher、或 IntentFilter 带
 *      CATEGORY_HOME 的 add/replace/persistent preferred 写入，记录
 *      调用方 uid/pid + 参数明细（落盘），为后续精细拦截提供事实依据。
 *      诊断清单里的 updatePreferredActivity /
 *      ActivityStartController.setTargetActivityAsPreferredActivity 在
 *      Android 10 上可能不存在 —— hookAllByName 找不到会明确打日志，
 *      不像旧模块那样静默失败。
 */
final class HomeUnlocker {

    private static final String TAG = "home";

    private static final String CLS_PMS = "com.android.server.pm.PackageManagerService";
    private static final String CLS_ASC = "com.android.server.wm.ActivityStartController";
    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";

    private static final String CATEGORY_HOME = "android.intent.category.HOME";
    private static final String XDF_LAUNCHER_PKG = "cn.xdf.pad.launcher";

    private HomeUnlocker() {
    }

    /* ==================== system_server：只读诊断 ==================== */

    static void hookSystemServer(ClassLoader cl) {
        Hooker probe = chain -> {
            boolean block = false;
            try {
                block = probePreferredWrite(chain);
            } catch (Throwable ignored) {
            }
            if (block) {
                return null;    // 激进拦截：完全接管（目标方法均为 void）
            }
            return chain.proceed();     // 默认只读观察，永远放行
        };

        XDFHook.hookAllByName(cl, CLS_PMS, "addPreferredActivity", probe,
                "PMS.addPreferredActivity (probe)");
        XDFHook.hookAllByName(cl, CLS_PMS, "replacePreferredActivity", probe,
                "PMS.replacePreferredActivity (probe)");
        XDFHook.hookAllByName(cl, CLS_PMS, "addPersistentPreferredActivity", probe,
                "PMS.addPersistentPreferredActivity (probe)");
        // Android 10 可能不存在（旧模块 XdfUnlock 曾 hook 过它，真伪待验证）
        XDFHook.hookAllByName(cl, CLS_PMS, "updatePreferredActivity", probe,
                "PMS.updatePreferredActivity (probe)");
        // ROM 定制候选点，同样诊断验证
        XDFHook.hookAllByName(cl, CLS_ASC, "setTargetActivityAsPreferredActivity", probe,
                "ActivityStartController.setTargetActivityAsPreferredActivity (probe)");
    }

    /**
     * 只读探针（可选拦截）：识别并记录 HOME/XDF 相关的 preferred 写入。
     * 返回 true 表示按"激进拦截"策略阻断本次写入（仅当选项开启且目标指向 XDF 桌面）。
     */
    private static boolean probePreferredWrite(Chain chain) {
        List<Object> args = chain.getArgs();

        StringBuilder targets = new StringBuilder();
        boolean hitXdf = mentionsXdf(args, targets);
        String filters = filterSummary(args);
        // filterSummary 含完整 category 名（android.intent.category.HOME），
        // SECONDARY_HOME 不含该子串，contains 判定精确
        boolean hitHome = !hitXdf && filters.contains(CATEGORY_HOME);
        if (!hitXdf && !hitHome) {
            return false;
        }

        Executable ex = chain.getExecutable();
        String name = (ex instanceof Method) ? ((Method) ex).getName() : "ctor";
        String caller = callerProcessName();
        String head = name + " uid=" + Binder.getCallingUid()
                + " pid=" + Binder.getCallingPid() + " caller=" + caller;

        if (hitXdf) {
            // 精确拦截（2026-08-28 修正）：只拦 XDF 生态进程发起的写入
            // （zeus 心跳/开机自动锁定），放行用户经 PermissionController/
            // 设置的主动设置——否则用户自己想设 XDF 桌面也会被误伤
            // （实测：uid=10048 PermissionController 的 replacePreferredActivity
            //  被一刀切 BLOCKED，cmd set-home-activity 表面 Success 实际写入失败）。
            //
            // 第二轮实测（重启后）：设微软 2 秒内被改回 XDF，写入者
            // caller=system_server（ROM 埋在 framework 的 HOME 锁定逻辑，
            // AOSP 不会自发写 XDF preferred）→ system_server 自身发起的
            // XDF 写入同样必须拦。用户路径（PermissionController uid 10048、
            // shell cmd uid 2000）不受影响。
            if (AppConfig.get().pmsAggressive
                    && (caller.startsWith("cn.xdf.") || caller.equals("system_server"))) {
                XDFHook.logw(TAG, "BLOCKED (aggressive, xdf caller): " + head
                        + (targets.length() > 0 ? " " + targets : "") + filters);
                return true;
            }
            XDFHook.logi(TAG, "XDF-TARGETED preferred write (allowed): " + head
                    + (targets.length() > 0 ? " " + targets : "") + filters);
        } else {
            // 对比观察：用户/系统写其他桌面为 HOME
            XDFHook.logd(TAG, "HOME preferred write: " + head + filters);
        }
        return false;
    }

    /**
     * 调用方进程名（system_server 可读同 uid 进程 cmdline）。
     * 读取失败返回 "unknown" —— 判定规则为 startsWith("cn.xdf.")，
     * unknown 一律放行（宁放勿误伤），行为可从日志观察。
     */
    private static String callerProcessName() {
        int pid = Binder.getCallingPid();
        if (pid == android.os.Process.myPid()) {
            return "system_server";
        }
        synchronized (sCallerCache) {
            String cached = sCallerCache.get(pid);
            if (cached != null) {
                return cached;
            }
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
        synchronized (sCallerCache) {
            if (sCallerCache.size() > 64) {
                sCallerCache.clear(); // pid 会复用，简单防涨
            }
            sCallerCache.put(pid, name);
        }
        return name;
    }

    private static final java.util.HashMap<Integer, String> sCallerCache =
            new java.util.HashMap<>();

    /** 参数中是否出现指向 XDF 桌面的目标（含数组元素），命中则把明细写入 out */
    private static boolean mentionsXdf(List<Object> args, StringBuilder out) {
        boolean hit = false;
        for (Object a : args) {
            if (a instanceof ComponentName) {
                ComponentName cn = (ComponentName) a;
                if (XDF_LAUNCHER_PKG.equals(cn.getPackageName())) {
                    hit = true;
                    out.append("CN{").append(cn.flattenToShortString()).append("} ");
                }
            } else if (a instanceof ActivityInfo) {
                ActivityInfo ai = (ActivityInfo) a;
                if (XDF_LAUNCHER_PKG.equals(ai.packageName)) {
                    hit = true;
                    out.append("AI{").append(ai.packageName).append('/').append(ai.name).append("} ");
                }
            } else if (a instanceof ComponentName[]) {
                for (ComponentName cn : (ComponentName[]) a) {
                    if (cn != null && XDF_LAUNCHER_PKG.equals(cn.getPackageName())) {
                        hit = true;
                        out.append("CN[]{").append(cn.flattenToShortString()).append("} ");
                    }
                }
            } else if (a instanceof ActivityInfo[]) {
                for (ActivityInfo ai : (ActivityInfo[]) a) {
                    if (ai != null && XDF_LAUNCHER_PKG.equals(ai.packageName)) {
                        hit = true;
                        out.append("AI[]{").append(ai.packageName).append('/')
                                .append(ai.name).append("} ");
                    }
                }
            }
        }
        return hit;
    }

    /** 第一个 IntentFilter 的 actions/categories 摘要（无则空串） */
    private static String filterSummary(List<Object> args) {
        for (Object a : args) {
            if (a instanceof IntentFilter) {
                IntentFilter f = (IntentFilter) a;
                StringBuilder sb = new StringBuilder(" filter[actions=");
                try {
                    int n = f.countActions();
                    for (int i = 0; i < n && i < 6; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(f.getAction(i));
                    }
                    sb.append("; cats=");
                    int m = f.countCategories();
                    for (int i = 0; i < m && i < 6; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(f.getCategory(i));
                    }
                    sb.append(']');
                } catch (Throwable t) {
                    sb.append("<err:").append(t).append("]");
                }
                return sb.toString();
            }
        }
        return "";
    }

    /* ==================== launcher3：block ROM 锁定逻辑 ==================== */

    /**
     * block Launcher.setXdfDefaultHomeLauncher()（沿继承链、所有重载）。
     * 不 proceed = 方法体不执行；该方法是 ROM 私有锁定逻辑，
     * 用户设置默认桌面走系统设置/RESOLVER，不经过它，无副作用。
     */
    static void hookLauncher(ClassLoader cl) {
        XDFHook.hookAllByName(cl, CLS_LAUNCHER, "setXdfDefaultHomeLauncher",
                chain -> {
                    XDFHook.logi(TAG, "blocked Launcher.setXdfDefaultHomeLauncher (ROM home lock)");
                    return null;    // 完全接管（方法为 void；非 void 由 PROTECTIVE 兜底）
                },
                "Launcher.setXdfDefaultHomeLauncher (block ROM home lock)");
    }
}
