package cn.cf3012.xdf;

import android.util.Log;

/**
 * DebugProbe — 诊断探针（原先多路径落盘已废除）。
 *
 * 历史：项目初期日志方案尚未定型时，用最原始的 java.io 多路径
 * (xdfhook-debug.log @ /data/system、/data/local/tmp、/sdcard/Download)
 * 证明模块代码是否执行。现在日志已由 FileLogger 统一处理——
 * logcat + 广播上行 + 可配置式单文件落盘（默认 /sdcard/Android/XDFHook.log），
 * 本类不再做任何文件写操作，只保留 logcat 一行证据，API 兼容原位引用。
 */
public final class DebugProbe {

    public static final String TAG = "XDFHook.debug";

    private DebugProbe() {
    }

    /** 保留兼容（不再用于落盘选路） */
    public static void setAppDir(java.io.File dir) {
    }

    /** 保留兼容（仅做进程名记录，用于日志行标注） */
    public static void setProcessName(String name) {
        if (name != null && !name.isEmpty()) {
            sProcName = name;
        }
    }

    private static volatile String sProcName = "unknown";

    /** 记一行（当前仅 logcat）；绝不抛出 */
    public static void log(String msg) {
        try {
            Log.println(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** 兼容保留；原返回落盘路径，现恒为 null（无独立落盘） */
    public static String chosenPath() {
        return null;
    }
}