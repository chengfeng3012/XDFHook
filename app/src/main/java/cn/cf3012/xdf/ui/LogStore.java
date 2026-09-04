package cn.cf3012.xdf.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import cn.cf3012.xdf.FileLogger;
import cn.cf3012.xdf.LogParser;

/**
 * LogStore — 管理端日志缓冲（IPC 广播接收侧）。
 *
 * hook 进程的 FileLogger 将日志行聚合后以广播上行
 * （setPackage 限定本模块），此处动态 receiver 接收并维护
 * 进程内环形缓冲；LogsPage 只读本缓冲，实时刷新，零文件依赖。
 *
 * 生命周期：MainActivity onResume 注册 / onPause 注销。
 */
public final class LogStore {

    /** 缓冲上限（行） */
    private static final int MAX_LINES = 2000;

    private static final ArrayDeque<LogParser.Line> LINES = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private LogStore() {
    }

    /** 动态 receiver（MainActivity onResume 注册） */
    public static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !FileLogger.ACTION_LOG.equals(intent.getAction())) {
                return;
            }
            String proc = intent.getStringExtra(FileLogger.EXTRA_PROC);
            String data = intent.getStringExtra(FileLogger.EXTRA_DATA);
            if (data == null || data.isEmpty()) {
                return;
            }
            List<LogParser.Line> parsed = LogParser.parse(data, proc);
            synchronized (LOCK) {
                for (LogParser.Line line : parsed) {
                    if (LINES.size() >= MAX_LINES) {
                        LINES.pollFirst();
                    }
                    LINES.addLast(line);
                }
            }
        }
    };

    public static IntentFilter filter() {
        return new IntentFilter(FileLogger.ACTION_LOG);
    }

    /** 缓冲快照（旧→新） */
    public static List<LogParser.Line> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }
}
