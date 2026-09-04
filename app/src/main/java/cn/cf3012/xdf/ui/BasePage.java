package cn.cf3012.xdf.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/** 页面基类：惰性 inflate + findViewById 快捷方式 */
abstract class BasePage implements Page {

    protected final Activity activity;
    protected final View view;

    BasePage(Activity activity, int layoutRes) {
        this.activity = activity;
        view = LayoutInflater.from(activity).inflate(layoutRes, (ViewGroup) null);
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void onHidden() {
    }

    protected <T extends View> T find(int id) {
        return view.findViewById(id);
    }
}
