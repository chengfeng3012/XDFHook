package cn.cf3012.xdf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Root —— 极简 su 封装，供「操作」页以 root 执行系统命令。
 *
 * 注意：available()/exec() 均为阻塞方法，必须放在后台线程调用，
 * 不能在 UI 主线程运行（Magisk 首次授权弹窗或超时都会卡 UI）。
 */
public final class Root {

    private Root() {
    }

    /** 探测当前是否具备 root（su 可用且 id 返回 uid=0）。超时视为无权限。 */
    public static boolean available() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroy();
                return false;
            }
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 以 root 执行并取回<b>全部输出</b>（trimmed）。失败/超时返回空串。
     *
     * <p>【历史 Bug 修正】本方法原来只 {@code readLine()} 一次（返回第一行），
     * 于是 LogStore 用它取 {@code logcat -d -t 2000} 的输出时，2000 行日志
     * 只剩 1 行能进日志页 —— 表现就是「日志文件永远读不到实际内容」。
     * 现改为完整 drain（并发读，避免管道缓冲写满互锁）。</p>
     */
    public static String get(String command) {
        return get(command, 5);
    }

    /** 同 {@link #get(String)}，但自定义超时（秒） */
    public static String get(String command, int timeoutSec) {
        try {
            Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            // 必须在 waitFor 之前 drain：否则管道缓冲写满后子进程阻塞，
            // 双方互等直到超时。
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return "";
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 以 root 执行任意 shell 命令；exit code 为 0 返回 true，超时(6s)返回 false。 */
    public static boolean exec(String command) {
        try {
            Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            // 读取输出，避免命令输出撑满管道缓冲而阻塞
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (br.readLine() != null) {
                // drain
            }
            if (!p.waitFor(6, TimeUnit.SECONDS)) {
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}