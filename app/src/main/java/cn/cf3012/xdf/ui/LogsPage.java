package cn.cf3012.xdf.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import cn.cf3012.xdf.LogParser;
import cn.cf3012.xdf.R;

/**
 * LogsPage — 日志：按模块 tag 与级别过滤查看 XDFHook.log 尾部。
 *
 * 过滤器为 miuix 风格 chip：
 *   tag chips   动态（全部 + 日志中出现过的 tag）
 *   level chips 全部 / V / D / I / W / E（选中级 = 显示该级及以上）
 * 页面可见时每 2s 自动刷新。
 */
final class LogsPage extends BasePage {

    private static final char[] LEVELS = {'V', 'D', 'I', 'W', 'E'};
    private static final long REFRESH_MS = 2000;

    private LinearLayout chipBar;
    private ListView list;
    private TextView empty;
    private final Adapter adapter = new Adapter();

    private String selTag = null;          // null = 全部
    private char selLevel = 0;             // 0 = 全部
    private List<LogParser.Line> data = new ArrayList<>();
    private final TreeSet<String> knownTags = new TreeSet<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    LogsPage(Activity activity) {
        super(activity, R.layout.page_logs);
        chipBar = find(R.id.chip_bar);
        list = find(R.id.log_list);
        empty = find(R.id.log_empty);
        list.setAdapter(adapter);
        rebuildChips();
        refresh();
    }

    @Override
    public void onShown() {
        // 幂等：onResume/onSelect 可能连续调用，先移除旧 tick 防止双倍刷新
        handler.removeCallbacks(tick);
        refresh();
        handler.postDelayed(tick, REFRESH_MS);
    }

    @Override
    public void onHidden() {
        handler.removeCallbacks(tick);
    }

    /** 级别严重度序号（ASCII 字符序 D<E<I<V<W 与严重度无关，不能直接比较） */
    private static int levelRank(char level) {
        switch (level) {
            case 'V': return 0;
            case 'D': return 1;
            case 'I': return 2;
            case 'W': return 3;
            case 'E': return 4;
            default: return 2;
        }
    }

    private void refresh() {
        // 数据源：LogStore（IPC 广播上行缓冲），不再读任何文件
        List<LogParser.Line> lines = LogStore.snapshot();
        data = new ArrayList<>();
        TreeSet<String> tags = new TreeSet<>();

        for (LogParser.Line line : lines) {
            tags.add(line.tag);
            if (selTag != null && !selTag.equals(line.tag)) {
                continue;
            }
            if (selLevel != 0 && levelRank(line.level) < levelRank(selLevel)) {
                continue;
            }
            data.add(line);
        }
        Collections.reverse(data);   // 最新在上

        if (!tags.equals(knownTags)) {
            knownTags.clear();
            knownTags.addAll(tags);
            rebuildChips();
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /* ==================== chips ==================== */

    private void rebuildChips() {
        chipBar.removeAllViews();
        chipBar.addView(chip(activity.getString(R.string.chip_all), selTag == null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selTag = null;
                        rebuildChips();
                        refresh();
                    }
                }));
        for (String tag : knownTags) {
            final String t = tag;
            chipBar.addView(chip(t, t.equals(selTag), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selTag = t;
                    rebuildChips();
                    refresh();
                }
            }));
        }
        chipBar.addView(separator());
        chipBar.addView(chip(activity.getString(R.string.chip_all), selLevel == 0,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selLevel = 0;
                        rebuildChips();
                        refresh();
                    }
                }));
        for (final char lv : LEVELS) {
            chipBar.addView(chip(String.valueOf(lv), selLevel == lv, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selLevel = lv;
                    rebuildChips();
                    refresh();
                }
            }));
        }
    }

    private TextView chip(String text, boolean selected, View.OnClickListener onClick) {
        TextView tv = new TextView(activity);
        tv.setLayoutParams(lp());
        tv.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), 0, dp(14), 0);
        tv.setTextColor(activity.getResources().getColor(
                selected ? android.R.color.white : R.color.text_secondary));
        tv.setOnClickListener(onClick);
        return tv;
    }

    private View separator() {
        View v = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(22));
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private ViewGroup.LayoutParams lp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.rightMargin = dp(8);
        return lp;
    }

    private int dp(float v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ==================== 列表 ==================== */

    private int levelColor(char level) {
        switch (level) {
            case 'V':
                return R.color.log_v;
            case 'D':
                return R.color.log_d;
            case 'W':
                return R.color.log_w;
            case 'E':
                return R.color.log_e;
            default:
                return R.color.log_i;
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public LogParser.Line getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = activity.getLayoutInflater().inflate(R.layout.item_log, parent, false);
            }
            LogParser.Line line = getItem(position);
            TextView head = v.findViewById(R.id.log_head);
            TextView msg = v.findViewById(R.id.log_msg);
            head.setText(line.head());
            head.setTextColor(activity.getResources().getColor(levelColor(line.level)));
            msg.setText(line.msg);
            return v;
        }
    }
}
