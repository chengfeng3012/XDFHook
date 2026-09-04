package cn.cf3012.xdf;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FileLogger — hook 进程日志通道（IPC 化 + 安全加固版）。
 *
 * 通道：
 *   1) logcat：每条必写，不受级别过滤；
 *   2) 广播上行：内存聚合 → 专用单线程异步 flush → sendBroadcast
 *      (setPackage 本模块)。异步保证 log() 调用方（含 hook 回调线程）
 *      永不执行 binder 调用。定时兜底（5s）防止尾部日志滞留。
 *
 * 安全设计（2026-08-28 事故审查结论）：
 *   - 广播禁用条件 = isSystemServer || uid==SYSTEM_UID。wtf 由
 *     system_server 的 AMS.checkBroadcastFromSystem 判定"发送方 uid"，
 *     与发送代码在哪个进程无关——settings/launcher3 等共享
 *     android.uid.system 的进程必须一并禁掉；
 *   - 单批双上限（24 行 / 24000 字符）：putExtra 按 UTF-16 序列化，
 *     逼近 1MB binder 限制会 TransactionTooLarge 丢整批。
 */
public final class FileLogger {

    private static final String TAG = "XDFHook.file";

    public static final String ACTION_LOG = "cn.cf3012.xdf.ACTION_LOG";
    public static final String EXTRA_PROC = "proc";
    public static final String EXTRA_DATA = "data";

    /** flush 阈值：行数 / 字符数 / 时间间隔 */
    private static final int FLUSH_LINES = 24;
    private static final int FLUSH_CHARS = 24000;
    private static final long FLUSH_INTERVAL_MS = 5000;
    private static final int MAX_PENDING = 96;
    private static final int MAX_LINE_BYTES = 3800;

    private static volatile String sProcName = "unknown";
    /** false = 不发广播（system_server / 一切 uid 1000 进程） */
    private static volatile boolean sBroadcastAllowed;
    /** 广播缓冲的最低级别（logcat 不受影响）；由 AppConfig 推送刷新 */
    private static volatile int sMinLevel = Log.VERBOSE;
    private static volatile Context sContext;
    private static volatile boolean sContextTried;

    private static final ArrayDeque<String> sPending = new ArrayDeque<>();
    private static volatile long sOldestPendingAt;
    private static volatile long sLastFlushAt;

    /** 单线程异步 flush + 5s 定时兜底（尾部日志不再滞留） */
    private static final ScheduledExecutorService sFlusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "xdf-log-flush");
                t.setDaemon(true);
                return t;
            });

    static {
        try {
            sFlusher.scheduleWithFixedDelay(FileLogger::requestFlush,
                    FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }

    private static final SimpleDateFormat sFmt =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private FileLogger() {
    }

    /* ==================== 初始化 ==================== */

    /**
     * @param isSystemServer true 时永久关闭广播通道（只 logcat + DebugProbe 文件）
     */
    public static void hookInit(String procName, boolean isSystemServer) {
        if (procName != null && !procName.isEmpty()) {
            sProcName = procName;
        }
        sBroadcastAllowed = !isSystemServer
                && android.os.Process.myUid() != android.os.Process.SYSTEM_UID;
    }

    /** 广播/展示的最低级别（logcat 永远全量）；AppConfig 推送变更时调用 */
    public static void setMinLevel(int level) {
        sMinLevel = level;
    }

    /* ==================== Context 获取（反射，flusher 线程执行） ==================== */

    private static void ensureContext() {
        if (sContext != null || sContextTried) {
            return;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return; // 进程早期，下次再试
            }
            Context c = null;
            try {
                c = (Context) at.getMethod("getSystemContext").invoke(thread);
            } catch (Throwable ignored) {
            }
            if (c == null) {
                try {
                    c = (Context) at.getMethod("currentApplication").invoke(null);
                } catch (Throwable ignored) {
                }
            }
            if (c == null) {
                try {
                    c = (Context) at.getDeclaredField("mInitialApplication").get(thread);
                } catch (Throwable ignored) {
                }
            }
            if (c != null) {
                sContext = c;
            }
        } catch (Throwable t) {
            sContextTried = true;
        }
    }

    /* ==================== 写入口（永不阻塞） ==================== */

    public static void log(int priority, String tag, String msg) {
        try {
            Log.println(priority, tag, msg == null ? "null" : msg);
        } catch (Throwable ignored) {
        }
        if (priority < sMinLevel) {
            return; // 低于配置级别：不进广播缓冲（logcat 已写）
        }
        String line = buildLine(priority, tag, msg);
        boolean needFlush;
        synchronized (FileLogger.class) {
            if (sPending.isEmpty()) {
                sOldestPendingAt = android.os.SystemClock.elapsedRealtime();
            }
            if (sPending.size() >= MAX_PENDING) {
                sPending.pollFirst();
            }
            sPending.addLast(line);
            long now = android.os.SystemClock.elapsedRealtime();
            needFlush = sPending.size() >= FLUSH_LINES
                    || (now - sOldestPendingAt) >= FLUSH_INTERVAL_MS;
        }
        if (needFlush) {
            requestFlush();
        }
    }

    /** 提交异步 flush；调用方零阻塞（定时器也走这里）。
     *  无论广播是否允许都执行 doFlush：落盘不依赖广播通道
     *  （system_server 广播禁用但磁盘照写）。 */
    public static void requestFlush() {
        try {
            sFlusher.execute(FileLogger::doFlush);
        } catch (Throwable ignored) {
        }
    }

    /**
     * flusher 线程执行：聚合缓冲 → 落盘 + 广播。
     * 单批双上限（行数/字符数），超出部分留下批；drain 后仍有积压则自续。
     */
    private static void doFlush() {
        String batch;
        boolean more;
        synchronized (FileLogger.class) {
            if (sPending.isEmpty()) {
                sOldestPendingAt = 0;
                return;
            }
            StringBuilder sb = new StringBuilder();
            int lines = 0;
            while (!sPending.isEmpty()) {
                String next = sPending.peekFirst();
                int extra = next.length() + (lines > 0 ? 1 : 0);
                if (lines > 0 && (lines >= FLUSH_LINES
                        || sb.length() + extra > FLUSH_CHARS)) {
                    break;
                }
                sPending.pollFirst();
                if (lines > 0) {
                    sb.append('\n');
                }
                sb.append(next);
                lines++;
            }
            batch = sb.toString();
            more = !sPending.isEmpty();
            if (!more) {
                sOldestPendingAt = 0;
            }
            sLastFlushAt = android.os.SystemClock.elapsedRealtime();
        }

        // 落盘（独立于广播通道，system_server / uid1000 也照常写）
        diskAppend(batch);

        // 广播（仅允许时；system_server / uid1000 永久禁用）
        if (sBroadcastAllowed) {
            ensureContext();
            Context c = sContext;
            if (c != null && !batch.isEmpty()) {
                try {
                    Intent i = new Intent(ACTION_LOG);
                    i.setPackage("cn.cf3012.xdf");
                    i.putExtra(EXTRA_PROC, sProcName);
                    i.putExtra(EXTRA_DATA, batch);
                    c.sendBroadcast(i);
                } catch (Throwable ignored) {
                }
            }
        }
        if (more) {
            try {
                sFlusher.execute(FileLogger::doFlush);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把一批日志追加到配置的落盘文件（默认 /sdcard/Android/XDFHook.log）。
     * 支持开关 / 路径 / 上限(KB)。全部 silent：写失败不影响宿主。
     * 参数即时生效——每次写都从 AppConfig 现读，改路径/开关立即生效。
     */
    private static void diskAppend(String batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        AppConfig cfg;
        try {
            cfg = AppConfig.get();
        } catch (Throwable t) {
            return;
        }
        if (cfg == null || !cfg.logFileEnabled) {
            return;
        }
        String path = cfg.logFilePath;
        if (path == null || path.isEmpty()) {
            return;
        }
        long capBytes = ((long) cfg.logFileCapKb) * 1024;
        FileOutputStream fos = null;
        try {
            File f = new File(path);
            // 上限：写入前文件已超限则重头写
            if (capBytes > 0 && f.exists() && f.length() > capBytes) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                if (!parent.mkdirs()) {
                    return;
                }
            }
            fos = new FileOutputStream(f, true);
            fos.write(batch.getBytes("UTF-8"));
            if (!batch.endsWith("\n")) {
                fos.write('\n');
            }
            fos.flush();
            try {
                android.system.Os.chmod(path, 0666);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 校验给定路径能否写入（配置落盘路径的即时生效试探）。
     * 成功写入探测行 + 建目录 + chmod，返回 true；否则 false。
     */
    public static boolean probeWrite(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        FileOutputStream fos = null;
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                if (!parent.mkdirs()) {
                    return false;
                }
            }
            fos = new FileOutputStream(f, true);
            fos.write("xdfhook-path-probe\n".getBytes("UTF-8"));
            fos.flush();
            try {
                android.system.Os.chmod(path, 0666);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* ==================== 行格式 ==================== */

    private static String buildLine(int priority, String tag, String msg) {
        StringBuilder sb = new StringBuilder(96);
        try {
            synchronized (sFmt) {
                sb.append(sFmt.format(new Date()));
            }
            sb.append(' ')
              .append(priorityChar(priority))
              .append('/')
              .append(tag)
              .append(" [").append(sProcName).append("] ")
              .append(sanitize(msg));
        } catch (Throwable t) {
            return "log-fmt-error: " + sanitize(msg);
        }
        if (sb.length() > MAX_LINE_BYTES) {
            sb.setLength(MAX_LINE_BYTES);
        }
        return sb.toString();
    }

    /** 多行 msg 展平为单行（解析器逐行匹配，内嵌换行会丢内容） */
    private static String sanitize(String msg) {
        if (msg == null) {
            return "null";
        }
        if (msg.indexOf('\n') < 0) {
            return msg;
        }
        return msg.replace("\n", "\\n");
    }

    private static char priorityChar(int p) {
        switch (p) {
            case Log.VERBOSE: return 'V';
            case Log.DEBUG: return 'D';
            case Log.INFO: return 'I';
            case Log.WARN: return 'W';
            case Log.ERROR: return 'E';
            default: return '?';
        }
    }
}
