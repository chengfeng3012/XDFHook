package cn.cf3012.xdf.ui;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import cn.cf3012.xdf.R;
import cn.cf3012.xdf.XposedServiceHolder;

/**
 * MainActivity — 模块配置界面（miuix 风格）。
 *
 * 布局自适应：竖屏底部导航栏（layout/activity_main.xml），
 * 横屏左侧导航栏（layout-land/activity_main.xml），两套布局的
 * tab 与容器 id 一致，代码无差别处理。
 */
public class MainActivity extends Activity implements XposedServiceHolder.Listener {

    private static final int[] TAB_IDS = {R.id.tab_0, R.id.tab_1, R.id.tab_2, R.id.tab_3};
    private static final int[] TAB_ICON_IDS = {R.id.tab_icon_0, R.id.tab_icon_1, R.id.tab_icon_2, R.id.tab_icon_3};
    private static final int[] TAB_TEXT_IDS = {R.id.tab_text_0, R.id.tab_text_1, R.id.tab_text_2, R.id.tab_text_3};

    private final Page[] pages = new Page[4];
    private int current = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 日志落盘/自定义路径即时校验需要外部存储写权限（Android 10 运行时权限）
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        }
        for (int i = 0; i < TAB_IDS.length; i++) {
            final int index = i;
            findViewById(TAB_IDS[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(index);
                }
            });
        }
        if (savedInstanceState != null && savedInstanceState.containsKey("tab")) {
            select(savedInstanceState.getInt("tab"));
        } else {
            select(0);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", Math.max(0, current));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // service 状态变化（绑定/失联）→ 刷新当前页数据源
        XposedServiceHolder.setListener(this);
        // hook 进程日志广播上行（IPC）：前台期间接收
        try {
            registerReceiver(LogStore.RECEIVER, LogStore.filter());
        } catch (Throwable ignored) {
        }
        // 对称恢复当前页（审查结论 G：否则退后台后 tick 泄漏）
        if (current >= 0 && pages[current] != null) {
            pages[current].onShown();
        }
    }

    @Override
    protected void onPause() {
        XposedServiceHolder.setListener(null);
        try {
            unregisterReceiver(LogStore.RECEIVER);
        } catch (Throwable ignored) {
        }
        if (current >= 0 && pages[current] != null) {
            pages[current].onHidden();
        }
        super.onPause();
    }

    /** XposedServiceHolder.Listener：LSPosed 服务连接状态变化 */
    @Override
    public void onServiceChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (current >= 0 && pages[current] != null) {
                    pages[current].onShown();
                }
            }
        });
    }

    private void select(int index) {
        if (index == current) {
            Page p = pages[index];
            if (p != null) {
                p.onShown();
            }
            return;
        }
        ViewGroup container = findViewById(R.id.page_container);
        if (pages[index] == null) {
            switch (index) {
                case 0:
                    pages[0] = new OverviewPage(this);
                    break;
                case 1:
                    pages[1] = new LogsPage(this);
                    break;
                case 2:
                    pages[2] = new OperationPage(this);
                    break;
                default:
                    pages[3] = new SettingsPage(this);
                    break;
            }
            View v = pages[index].getView();
            if (v.getParent() == null) {
                container.addView(v, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        if (current >= 0 && pages[current] != null) {
            pages[current].onHidden();
            pages[current].getView().setVisibility(View.GONE);
        }
        pages[index].getView().setVisibility(View.VISIBLE);
        current = index;
        tintNav();
        pages[index].onShown();
    }

    /** 选中 tab 高亮（miuix 蓝），其余灰色 */
    private void tintNav() {
        Resources res = getResources();
        int on = res.getColor(R.color.miuix_blue);
        int off = res.getColor(R.color.text_secondary);
        for (int i = 0; i < TAB_IDS.length; i++) {
            boolean sel = i == current;
            ImageView icon = findViewById(TAB_ICON_IDS[i]);
            TextView text = findViewById(TAB_TEXT_IDS[i]);
            icon.setColorFilter(sel ? on : off);
            text.setTextColor(sel ? on : off);
        }
    }
}
