package cn.cf3012.xdf;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * FileLogger — hook 进程日志通道（2026-09-25 重构：去广播、去落盘）。
 *
 * 【为什么重构】旧实现是「内存聚合 → 异步 flush → sendBroadcast + 共享文件落盘」。
 * 那个自写广播正是 2026-08-28 system_server 濒死事故的根因：
 * uid1000（system_server / settings / launcher3 / systemui / zeus 共享
 * android.uid.system）发非 protected 自定义广播，会逐条触发 AMS 的
 * checkBroadcastFromSystem → Slog.wtf → dropbox 高频写盘。
 * 当时靠 `!isSystemServer && uid != SYSTEM_UID` 的双重 guard 止血，但那是
 * 「绕开症状」而不是「消除病根」：任何新增代码路径（新进程 / 新 uid /
 * 漏调 hookInit）都可能把雷重新武装起来。
 *
 * 共享文件落盘同样问题缠身：N 个进程并发 append 同一个文件、跨进程无锁、
 * 无轮转协调、创建者 uid 决定权限（实测 uid1000 写不进 root:everybody 0660
 * 的文件，日志静默丢失），外加每个被 hook 进程常驻一个 5 秒定时器。
 *
 * 【现在的通道】全部交给框架/系统，模块自己不再做任何 IPC 与文件 IO：
 *   1) logcat：Log.println，固定 tag=XDFHook，`logcat -s XDFHook` 一把捞全。
 *             消息体带 [进程名][子标签] 前缀，便于日志页归类。
 *   2) api.log()：LibXposed 官方日志接口，由 LSPosed daemon 跨进程聚合并落
 *             /data/adb/lspd/log/modules_*.log（写盘、轮转、权限全由框架管）。
 *
 * 日志等级（sMinLevel）只过滤 api.log 这条聚合通道；logcat 保持全量，
 * 与旧语义一致（日志页在客户端自行按级别过滤）。
 */
public final class FileLogger {

    /** logcat 固定 tag：所有子标签统一挂在这下面，便于按 tag 过滤 */
    public static final String LOGCAT_TAG = "XDFHook";

    private static volatile int sMinLevel = Log.INFO;
    private static volatile String sProcName = "unknown";

    private FileLogger() {
    }

    /**
     * @param isSystemServer 仅为兼容旧调用签名保留（广播已移除，不再需要它做门禁）
     */
    public static void hookInit(String procName, boolean isSystemServer) {
        if (procName != null && !procName.isEmpty()) {
            sProcName = procName;
        }
    }

    /** api.log 通道的最低级别（logcat 不受影响）；AppConfig 推送变更时调用 */
    public static void setMinLevel(int level) {
        sMinLevel = level;
    }

    public static void log(int priority, String tag, String msg) {
        String m = oneLine(msg);
        // ① logcat：默认通道，root/adb 随时可读，不经过 AMS 广播路径
        try {
            Log.println(priority, LOGCAT_TAG,
                    "[" + sProcName + "][" + tag + "] " + m);
        } catch (Throwable ignored) {
        }
        if (priority < sMinLevel) {
            return; // 低于配置级别：不进 framework 聚合通道（logcat 已写）
        }
        // ② framework 官方聚合通道（跨进程落盘/轮转由 LSPosed 负责）
        XposedInterface api = XDFHook.api();
        if (api == null) {
            return;
        }
        try {
            api.log(priority, tag, m);
        } catch (Throwable ignored) {
        }
    }

    /** 多行压成单行：保持「一条日志 = 一行」，日志页按行解析才对得上 */
    private static String oneLine(String msg) {
        if (msg == null) {
            return "null";
        }
        if (msg.indexOf('\n') < 0) {
            return msg;
        }
        return msg.replace('\n', ' ');
    }
}
