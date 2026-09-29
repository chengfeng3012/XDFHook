package cn.cf3012.xdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LogParser — 解析 logcat 行（重构后的默认日志通道）。
 *
 * 取数命令：logcat -d -v threadtime -t 2000 -s XDFHook
 * 行格式：MM-dd HH:mm:ss.SSS  PID TID L XDFHook: [进程名][子标签] message
 *
 * FileLogger 固定用 LOGCAT_TAG="XDFHook" 打 tag，进程名与子标签放在消息体前缀里，
 * 于是「按 tag 过滤全部模块日志」退化成一条 logcat -s XDFHook，
 * 不再需要枚举各子模块的 tag（ime / cfg / resolver / chooser / system ...）。
 */
public final class LogParser {

    public static final class Line {
        public final String time;   // MM-dd HH:mm:ss.SSS
        public final char level;    // V/D/I/W/E
        public final String tag;    // 子标签，如 ime / cfg / XDFHook
        public final String proc;   // 产生日志的 hook 进程名
        public final String msg;

        Line(String time, char level, String tag, String proc, String msg) {
            this.time = time;
            this.level = level;
            this.tag = tag;
            this.proc = proc;
            this.msg = msg;
        }

        public String head() {
            return time + "  " + level + "/" + tag + " [" + proc + "]";
        }
    }

    private static final Pattern P = Pattern.compile(
            "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+"
                    + "([VDIWE])\\s+\\S+:\\s+\\[([^\\]]*)\\]\\[([^\\]]*)\\]\\s?(.*)$");

    private LogParser() {
    }

    /** 解析 logcat 输出；无法匹配的行走默认分支（tag 记 XDFHook，proc 记 unknown） */
    public static List<Line> parseLogcat(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String raw : text.split("\\r?\\n")) {
            if (raw.isEmpty()) {
                continue;
            }
            Matcher m = P.matcher(raw);
            if (m.matches()) {
                out.add(new Line(m.group(1), m.group(2).charAt(0),
                        emptyTo(m.group(4), FileLogger.LOGCAT_TAG),
                        emptyTo(m.group(3), "unknown"),
                        m.group(5)));
            } else {
                out.add(new Line("", 'I', FileLogger.LOGCAT_TAG, "unknown", raw));
            }
        }
        return out;
    }

    private static String emptyTo(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }
}
