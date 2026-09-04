package cn.cf3012.xdf.ui;

import android.view.View;

/** 页面契约：惰性创建视图 + 每次展示时刷新 */
interface Page {
    View getView();

    void onShown();

    void onHidden();
}
