package cn.cf3012.xdf.ui;

import java.util.ArrayList;
import java.util.List;

import cn.cf3012.xdf.LogParser;
import cn.cf3012.xdf.Root;

/**
 * LogStore — 日志页数据源（重构后直接读 logcat）。
 *
 * 为什么不再用广播：旧实现靠 hook 进程 sendBroadcast 上行，那是 2026-08-28
 * system_server 濒死事故的根因（uid1000 发非 protected 自定义广播 → AMS
 * checkBroadcastFromSystem → Log.wtf → dropbox 高频写盘）。现已从 FileLogger
 * 彻底删除，模块侧不再做任何 IPC，也不再写任何文件。
 *
 * 现在的取数：root 读 logcat。所有 hook 进程的日志都由 FileLogger 用固定
 * tag=XDFHook 打进 logcat，一条命令即可捞全，无需枚举子标签。
 * 级别过滤与 tag 过滤都在日志页客户端做。
 *
 * load() 是阻塞调用（会 fork 一个 su 进程），必须在后台线程执行。
 */
public final class LogStore {

    /** 取数命令：最近 2000 行本模块日志 */
    private static final String CMD = "logcat -d -v threadtime -t 2000 -s XDFHook";

    private static volatile List<LogParser.Line> sLines = new ArrayList<>();
    private static volatile boolean sUnavailable;

    private LogStore() {
    }

    /** 阻塞取数（后台线程）；失败时返回上一次的结果 */
    public static List<LogParser.Line> load() {
        String out = null;
        try {
            // 2000 行 logcat 走 su 可能偏慢，给 8s（旧的 3s 会中途超时丢日志）
            out = Root.get(CMD, 8);
        } catch (Throwable ignored) {
        }
        if (out == null || out.isEmpty()) {
            sUnavailable = true;
            return sLines;
        }
        sUnavailable = false;
        List<LogParser.Line> parsed = LogParser.parseLogcat(out);
        sLines = parsed;
        return parsed;
    }

    /** 最近一次取数结果（不触发 IO） */
    public static List<LogParser.Line> snapshot() {
        return sLines;
    }

    /** root 不可用 / 取数失败（日志页据此提示） */
    public static boolean unavailable() {
        return sUnavailable;
    }
}
