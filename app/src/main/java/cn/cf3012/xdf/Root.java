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

    /** 以 root 执行并取回第一行输出（trimmed），供读取开关等当前状态。失败/超时返回空串。 */
    public static String get(String command) {
        try {
            Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroy();
                return "";
            }
            return line == null ? "" : line.trim();
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