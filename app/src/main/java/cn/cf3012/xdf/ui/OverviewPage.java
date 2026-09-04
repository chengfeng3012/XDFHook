package cn.cf3012.xdf.ui;

import android.app.Activity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

import cn.cf3012.xdf.AppConfig;
import cn.cf3012.xdf.R;
import cn.cf3012.xdf.XposedServiceHolder;
import io.github.libxposed.service.XposedService;

/**
 * OverviewPage — 总览：模块总开关、子模块开关与工作状况。
 *
 * 数据来源（官方通道，不再解析日志）：
 *   开关  → AppConfig（remote prefs，LSPosed daemon 中转，hook 侧实时生效）
 *   状态  → XposedService.getRunningTargets()（实时 binder 查询当前被 hook 进程）
 *   服务  → XposedServiceHolder（LSPosed daemon 注入的官方服务 binder）
 */
final class OverviewPage extends BasePage {

    /** 子模块定义：cfg 键 / 文案 / 承载进程（匹配 stat.* 上报） */
    private static final class Def {
        final String key;
        final int titleRes;
        final int descRes;
        final int color;
        /** 哪些进程上报了就算"已加载"；含 "any" 时任意进程上报即可 */
        final String[] procs;

        Def(String key, int titleRes, int descRes, int color, String[] procs) {
            this.key = key;
            this.titleRes = titleRes;
            this.descRes = descRes;
            this.color = color;
            this.procs = procs;
        }

        boolean isEnabled(AppConfig cfg) {
            return cfg.enabled(key);
        }
    }

    private static final String ANY_PROC = "any";
    private static final String PROC_SYS_SERVER = "system_server";
    /** system_server 在部分框架实现里以 scope 包名 "android" 记录 */
    private static final String PROC_SYS_SERVER_ALT = "android";
    private static final String PROC_SETTINGS = "com.android.settings";
    private static final String PROC_ZEUS = "cn.xdf.zeus";
    private static final String PROC_LAUNCHER = "com.android.launcher3";
    private static final String PROC_GALLERY = "com.android.gallery3d";

    private static final Def[] DEFS = {
            new Def(AppConfig.K_ZEUS, R.string.mod_zeus, R.string.mod_zeus_desc, 0xFF7C5CFF,
                    new String[]{PROC_ZEUS}),
            new Def(AppConfig.K_SETTINGS, R.string.mod_settings, R.string.mod_settings_desc, 0xFF3482FF,
                    new String[]{PROC_SETTINGS}),
            new Def(AppConfig.K_CHOOSER, R.string.mod_chooser, R.string.mod_chooser_desc, 0xFF00B8D9,
                    new String[]{ANY_PROC}),
            new Def(AppConfig.K_LAUNCHER, R.string.mod_launcher, R.string.mod_launcher_desc, 0xFF34C759,
                    new String[]{PROC_LAUNCHER}),
            new Def(AppConfig.K_HOME, R.string.mod_home, R.string.mod_home_desc, 0xFFFFC42E,
                    new String[]{PROC_SYS_SERVER, PROC_SYS_SERVER_ALT, PROC_LAUNCHER}),
            new Def(AppConfig.K_GALLERY, R.string.mod_gallery, R.string.mod_gallery_desc, 0xFFFF6482,
                    new String[]{PROC_GALLERY}),
    };

    private static final class Row {
        Switch sw;
        TextView pill;
    }

    private Switch swMaster;
    private TextView envText;
    private LinearLayout moduleList;
    private final Row[] rows = new Row[DEFS.length];
    /** 防止程序化 setChecked 触发监听写配置 */
    private boolean binding;

    OverviewPage(Activity activity) {
        super(activity, R.layout.page_overview);
        swMaster = find(R.id.sw_master);
        envText = find(R.id.env_text);
        moduleList = find(R.id.module_list);

        swMaster.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (binding) {
                    return;
                }
                AppConfig.setBoolean(AppConfig.K_MASTER, isChecked);
            }
        });

        for (int i = 0; i < DEFS.length; i++) {
            final int idx = i;
            View item = activity.getLayoutInflater().inflate(R.layout.item_module, moduleList, false);
            moduleList.addView(item);
            Row row = new Row();
            row.sw = item.findViewById(R.id.mod_switch);
            row.pill = item.findViewById(R.id.mod_pill);
            View dot = item.findViewById(R.id.mod_dot);
            dot.getBackground().mutate().setTint(DEFS[i].color);
            ((TextView) item.findViewById(R.id.mod_title)).setText(DEFS[i].titleRes);
            ((TextView) item.findViewById(R.id.mod_subtitle)).setText(DEFS[i].descRes);
            row.sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (binding) {
                        return;
                    }
                    AppConfig.setBoolean(DEFS[idx].key, isChecked);
                    applyStatus(idx);
                }
            });
            rows[i] = row;
        }
    }

    @Override
    public void onShown() {
        refresh();
    }

    private void refresh() {
        binding = true;
        AppConfig cfg = AppConfig.refresh();
        XposedService svc = XposedServiceHolder.get();
        boolean remote = AppConfig.isRemote();

        // 可写性：remote 通道在 → 开关可操作；否则降级只读
        boolean writable = remote;
        swMaster.setEnabled(writable);

        // 服务状态卡
        if (svc != null) {
            String fw;
            try {
                fw = svc.getFrameworkName() + " " + svc.getFrameworkVersion()
                        + " · API " + svc.getApiVersion();
            } catch (Throwable t) {
                fw = "connected";
            }
            envText.setText(activity.getString(R.string.env_service_ok, fw));
            envText.setTextColor(activity.getResources().getColor(R.color.pill_ok_text));
        } else {
            envText.setText(R.string.env_service_off);
            envText.setTextColor(activity.getResources().getColor(R.color.pill_warn_text));
        }

        swMaster.setChecked(cfg.master);
        for (int i = 0; i < DEFS.length; i++) {
            rows[i].sw.setEnabled(writable);
            rows[i].sw.setChecked(DEFS[i].isEnabled(cfg));
            applyStatus(i);
        }
        binding = false;
    }

    /**
     * 更新某个子模块的状态 pill：
     *   开关关            → 已停用
     *   开关开 + 上报新鲜 → 运行中
     *   开关开 + 无上报   → 未加载（进程可能尚未启动）
     */
    private void applyStatus(int idx) {
        Def def = DEFS[idx];
        AppConfig cfg = AppConfig.get();
        Row row = rows[idx];
        if (!def.isEnabled(cfg)) {
            setPill(row.pill, activity.getString(R.string.state_off),
                    R.color.pill_off_bg, R.color.pill_off_text);
            return;
        }
        boolean fresh = anyFreshReport(def.procs);
        if (fresh) {
            setPill(row.pill, activity.getString(R.string.state_ok),
                    R.color.pill_ok_bg, R.color.pill_ok_text);
        } else {
            setPill(row.pill, activity.getString(R.string.state_na),
                    R.color.pill_warn_bg, R.color.pill_warn_text);
        }
    }

    private boolean anyFreshReport(String[] procs) {
        // 状态源：getRunningTargets() —— 每次 refresh() 实时 binder 查询，
        // 返回当前已注入模块代码的进程列表（UI 的 remote prefs 是绑定时刻
        // 快照，看不到 hook 进程后续写入，故不能用上报时间戳）
        java.util.List<String> targets = AppConfig.runningTargets();
        if (targets.isEmpty()) {
            return false;
        }
        for (String proc : procs) {
            if (ANY_PROC.equals(proc)) {
                return true;
            }
            if (targets.contains(proc)) {
                return true;
            }
        }
        return false;
    }

    private void setPill(TextView pill, String text, int bgRes, int textRes) {
        pill.setText(text);
        int bg = activity.getResources().getColor(bgRes);
        int fg = activity.getResources().getColor(textRes);
        pill.getBackground().mutate().setTint(bg);
        pill.setTextColor(fg);
    }
}
