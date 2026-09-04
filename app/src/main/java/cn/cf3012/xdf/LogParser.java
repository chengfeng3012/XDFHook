package cn.cf3012.xdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LogParser — 解析 FileLogger 的行格式（IPC 广播上行版）。
 *
 * 行格式：MM-dd HH:mm:ss.SSS L/TAG [procName] message
 * （进程名在广播 extra 中也有一份；批量文本内逐行重复携带，两处冗余容错）
 */
public final class LogParser {

    public static final class Line {
        public final String time;   // MM-dd HH:mm:ss.SSS
        public final char level;    // V/D/I/W/E
        public final String tag;
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
            "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([VDIWE])/([^ ]+) \\[([^\\]]+)\\] (.*)$",
            Pattern.MULTILINE);

    private LogParser() {
    }

    /** 解析广播批量文本；proc 为该批次的进程名（extra），行内 [proc] 优先 */
    public static List<Line> parse(String text, String batchProc) {
        List<Line> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = P.matcher(text);
        while (m.find()) {
            String proc = m.group(4);
            if (proc == null || proc.isEmpty()) {
                proc = batchProc != null ? batchProc : "unknown";
            }
            out.add(new Line(m.group(1), m.group(2).charAt(0), m.group(3), proc, m.group(5)));
        }
        return out;
    }
}
