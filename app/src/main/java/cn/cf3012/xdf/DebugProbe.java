package cn.cf3012.xdf;

import android.util.Log;

/**
 * DebugProbe — 诊断探针（无独立落盘）。
 *
 * 历史：项目初期日志方案未定型时用 java.io 多路径落盘
 * (xdfhook-debug.log @ /data/system、/data/local/tmp、/sdcard/Download)
 * 证明模块代码是否执行；后来一度改成「内存聚合 + 广播上行 + 单文件落盘」，
 * 该方案因 uid1000 发非 protected 自定义广播 → AMS.checkBroadcastFromSystem
 * → Slog.wtf → dropbox 高频写盘，把 system_server 拖到濒死，已被整体废除
 * （见 FileLogger 头注释）。
 *
 * 现状：本类只做两件事 ——
 *   ① 用固定 tag 直写 logcat（UI 进程唯一可靠出口，{@code adb logcat -s XDFHook.debug}）；
 *   ② 转发给 FileLogger（hook 进程的 api.log 聚合通道）。
 */
public final class DebugProbe {

    public static final String TAG = "XDFHook.debug";

    private DebugProbe() {
    }

    /** 兼容保留：已无调用方（原用于落盘选路），保留以免破坏历史引用 */
    public static void setAppDir(java.io.File dir) {
    }

    /** 保留兼容（仅做进程名记录，用于日志行标注） */
    public static void setProcessName(String name) {
        if (name != null && !name.isEmpty()) {
            sProcName = name;
        }
    }

    private static volatile String sProcName = "unknown";

    /**
     * 记一行：logcat tag=XDFHook.debug + framework api.log，绝不抛出。
     *
     * <p>★ 修复「静默失败」的关键（2026-10）：UI 进程里 {@code XDFHook.api()}
     * 恒为 null（模块 App 自己不被 hook，从不调 hookInit），所以 FileLogger
     * 的 api.log 通道在 UI 进程<b>完全失效</b>。此前所有写入失败/降级诊断
     * 都只走这一条路 → 一条都看不到，用户只看到「保存了但没生效」。
     * 现补 logcat 直写，UI 进程的诊断可被
     * {@code adb logcat -s XDFHook.debug} 直接捞到。</p>
     */
    public static void log(String msg) {
        try {
            // ① 直写 logcat：UI 进程唯一可靠的诊断出口（不经任何跨进程通道）
            android.util.Log.println(Log.INFO, TAG, "[" + sProcName + "] " + oneLine(msg));
        } catch (Throwable ignored) {
        }
        try {
            // ② framework 聚合通道（hook 进程有效；UI 进程 api 为 null 自动跳过）
            FileLogger.log(Log.INFO, "debug", msg);
        } catch (Throwable ignored) {
        }
    }

    /** 多行压成单行，保证「一条日志 = 一行」 */
    private static String oneLine(String msg) {
        if (msg == null) {
            return "null";
        }
        return msg.indexOf('\n') < 0 ? msg : msg.replace('\n', ' ');
    }

    /** 兼容保留；原返回落盘路径，现恒为 null（无独立落盘） */
    public static String chosenPath() {
        return null;
    }
}