package cn.cf3012.xdf;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/**
 * SettingsHooks — XDF 定制版 Settings (MtkSettings, Android 10, com.android.settings) 进程的
 * 功能性 hook（原 XdfSettingsHook v1.8 主体迁移，LibXposed api 102）。
 *
 *   1. 全局放行 persist.sys.settings_control 家长控制属性（50+ controller 依赖它）
 *   2. 启用被 android:enabled="false" 禁用的开发者选项 Activity
 *   3. 手势页"系统导航"条目解除隐藏（getAvailabilityStatus -> AVAILABLE）
 *   4. 主页侧边栏 RadioGroup 注入"更多设置"选项卡 → 跳转 AOSP TopLevelSettings
 *
 * UI 层条目注入见 UiRestorer；家长管控解除见 ZeusUnlocker。
 */
final class SettingsHooks {

    private static final String TAG = "settings";
    private static final String PKG = XDFHook.PKG_SETTINGS;

    /** 家长控制属性：值为 "1" 时显示受控设置项，其余值隐藏 */
    private static final String PROP_CONTROL = "persist.sys.settings_control";
    private static final String PROP_FORCE_VALUE = "1";

    /** 开发者选项 Activity（Manifest 中被 android:enabled="false" 禁用） */
    private static final String DEV_OPTIONS_ACTIVITY =
            "com.android.settings.Settings$DevelopmentSettingsDashboardActivity";

    private SettingsHooks() {
    }

    static void hookAll(ClassLoader cl) {
        try {
            hookPropertyBypass(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookPropertyBypass");
        }
        try {
            hookDevOptionsEnable(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookDevOptionsEnable");
        }
        try {
            hookSystemNavigation(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookSystemNavigation");
        }
        try {
            hookMainTabs(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookMainTabs");
        }
    }

    /* ==================== hooks ==================== */

    /** 全局放行：persist.sys.settings_control 的读取恒返回 "1" */
    private static void hookPropertyBypass(ClassLoader cl) throws Exception {
        XDFHook.hookMethod(cl, "android.os.SystemProperties", "get",
                new Class<?>[]{String.class, String.class},
                chain -> {
                    try {
                        Object key = chain.getArg(0);
                        if (PROP_CONTROL.equals(key)) {
                            return PROP_FORCE_VALUE;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
        XDFHook.logi(TAG, "property bypass armed: " + PROP_CONTROL + " -> " + PROP_FORCE_VALUE);
    }

    /** 每次打开 Settings 主页(onResume)时启用开发者选项组件 */
    private static void hookDevOptionsEnable(ClassLoader cl) throws Exception {
        XDFHook.hookMethod(cl, "com.android.settings.Settings", "onResume",
                new Class<?>[0],
                chain -> {
                    enableDevOptions((Context) chain.getThisObject());
                    return chain.proceed();
                });
    }

    /**
     * 放行手势页的"系统导航"条目：
     * SystemNavigationPreferenceController.getAvailabilityStatus() 内部
     * isGestureAvailable() 恒返回 false，导致 gestures.xml 中已有的
     * gesture_system_navigation_input_summary 条目被移除。
     * hook 该方法直接返回 AVAILABLE(0)。
     */
    private static void hookSystemNavigation(ClassLoader cl) throws Exception {
        XDFHook.hookMethod(cl,
                "com.android.settings.gestures.SystemNavigationPreferenceController",
                "getAvailabilityStatus", new Class<?>[0],
                chain -> 0 /* BasePreferenceController.AVAILABLE */);
        XDFHook.logi(TAG, "system navigation entry unhidden (getAvailabilityStatus -> AVAILABLE)");
    }

    /* ==================== 主页侧边栏：添加 AOSP 选项卡 ====================
     *
     * 定制主页(settings_main_prefs.xml)左侧是 RadioGroup(0x7f0a0417)，
     * 内含 12 个 com.android.settings.custom.SettingsTitle 选项卡。
     * 本模块在 onCreate 之后往 RadioGroup 末尾动态添加一个同款样式的
     * "更多设置"选项卡，点击后把 SettingsActivity.mItemFragmentName 设为
     * AOSP 的 TopLevelSettings（原生 15 分类首页），原 onCheckedChanged
     * 的 switch 不匹配新 id 会保留该值并调用 switchToFragment -> 原生页面
     * 渲染在右侧内容区，视觉风格与原生 AOSP 完全一致。
     */

    /** 定制布局里的 RadioGroup id */
    private static final int ID_RADIO_GROUP = 0x7f0a0417;
    /** SettingsTitle 的样式资源（见 SettingsItem style + main_left_item_checked_selector） */
    private static final int DRAWABLE_MAIN_BG_SELECTOR = 0x7f0802b4;
    private static final int COLOR_LEFT_TEXT = 0x7f060134;   // setting_menu_left_text_colors
    private static final int DRAWABLE_IC_MORE = 0x7f0f0018;  // 尝试：更多图标（找不到则用系统图标）
    /** 进入 AOSP 首页的 fragment */
    private static final String FRAG_AOSP_HOME = "com.android.settings.homepage.TopLevelSettings";

    private static volatile int sMoreSettingsId = -1;

    private static void hookMainTabs(ClassLoader cl) throws Exception {
        // 1) onCreate 之后：往 RadioGroup 添加选项卡
        //    （after 注入：proceed 返回后 setContentView 已完成，RadioGroup 才可 findViewById；
        //      再 post 到主线程下一帧，与 UiRestorer 的延迟注入策略一致）
        XDFHook.hookMethod(cl, "com.android.settings.SettingsActivity", "onCreate",
                new Class<?>[]{Bundle.class},
                chain -> {
                    chain.proceed();
                    Object activity = chain.getThisObject(); // onCreate 是 void，proceed() 返回 null
                    try {
                        Context ctx = (Context) activity;
                        if (ctx instanceof android.app.Activity) {
                            final Context activityCtx = ctx;
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                try {
                                    View group = ((android.app.Activity) activityCtx)
                                            .findViewById(ID_RADIO_GROUP);
                                    if (group instanceof RadioGroup) {
                                        addMoreSettingsTab((RadioGroup) group, activityCtx);
                                    }
                                } catch (Throwable t) {
                                    XDFHook.loge(t, TAG, "addMoreSettingsTab post");
                                }
                            });
                        }
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "addMoreSettingsTab");
                    }
                    return activity;
                });
        // 2) onCheckedChanged 之前：把我们选项卡的 id 路由到 AOSP 首页
        XDFHook.hookMethod(cl, "com.android.settings.SettingsActivity", "onCheckedChanged",
                new Class<?>[]{RadioGroup.class, int.class},
                chain -> {
                    try {
                        int checkedId = (Integer) chain.getArg(1);
                        if (sMoreSettingsId > 0 && checkedId == sMoreSettingsId) {
                            Object activity = chain.getThisObject();
                            Reflect.setField(activity, "mItemFragmentName", FRAG_AOSP_HOME);
                            Object args = Reflect.getField(activity, "cateArgs");
                            if (args == null) {
                                args = new Bundle();
                                Reflect.setField(activity, "cateArgs", args);
                            }
                            Reflect.call(args, "putInt",
                                    new Class<?>[]{String.class, int.class}, "category", 0);
                            XDFHook.logi(TAG, "more-settings tab -> TopLevelSettings");
                        }
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "onCheckedChanged route");
                    }
                    return chain.proceed();
                });
        XDFHook.logi(TAG, "main tabs armed");
    }

    /** 往 RadioGroup 添加一个与现有选项卡完全同款的 "更多设置"（幂等） */
    private static void addMoreSettingsTab(RadioGroup group, Context ctx) {
        if (sMoreSettingsId <= 0) {
            sMoreSettingsId = View.generateViewId();
        }
        // 防重：已添加过则跳过
        if (group.findViewWithTag("xdf_more_settings") != null) {
            return;
        }
        try {
            Class<?> titleCls = Class.forName(
                    "com.android.settings.custom.SettingsTitle", false, ctx.getClassLoader());
            RadioButton rb = (RadioButton) Reflect.newInstance(
                    titleCls, new Class<?>[]{Context.class}, ctx);

            rb.setId(sMoreSettingsId);
            rb.setTag("xdf_more_settings");
            rb.setText("更多设置");
            rb.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f);
            try {
                rb.setTextColor(ctx.getColorStateList(COLOR_LEFT_TEXT));
            } catch (Throwable ignored) {
            }
            rb.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            rb.setButtonDrawable(null);      // 隐藏 RadioButton 圆圈
            rb.setSingleLine(true);
            rb.setCompoundDrawablePadding(dp(ctx, 17));
            rb.setPadding(dp(ctx, 26), 0, dp(ctx, 17), 0);
            rb.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            rb.setBackgroundResource(DRAWABLE_MAIN_BG_SELECTOR);  // 选中态高亮

            int icon = ctx.getResources().getIdentifier(
                    "ic_more_settings", "drawable", ctx.getPackageName());
            if (icon == 0) {
                icon = ctx.getResources().getIdentifier(
                        "ic_settings_more", "drawable", ctx.getPackageName());
            }
            if (icon == 0) {
                icon = DRAWABLE_IC_MORE;
            }
            if (icon != 0) {
                rb.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }

            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT, dp(ctx, 68));
            lp.setMargins(dp(ctx, 26), dp(ctx, 21), dp(ctx, 26), 0);
            group.addView(rb, lp);
            XDFHook.logi(TAG, "more-settings tab added");
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addMoreSettingsTab build");
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 启用开发者选项组件（幂等，DONT_KILL_APP=1） */
    public static void enableDevOptions(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ComponentName cn = new ComponentName(PKG, DEV_OPTIONS_ACTIVITY);
            ctx.getPackageManager().setComponentEnabledSetting(
                    cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 1 /* DONT_KILL_APP */);
            XDFHook.logi(TAG, "dev options component enabled: " + DEV_OPTIONS_ACTIVITY);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "enableDevOptions");
        }
    }
}
