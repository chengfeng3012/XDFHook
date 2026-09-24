package cn.cf3012.xdf;

import android.content.ComponentName;
import android.content.Intent;
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
    /** AOSP 开发者选项 fragment（XDF 侧仅允许 XDFDevelopDevActivity 承载） */
    private static final String FRAG_DEV =
            "com.android.settings.development.DevelopmentSettingsDashboardFragment";
    /** XDF 定制开发者选项入口 Activity（内部承载完整 AOSP dev fragment） */
    private static final String ACT_XDF_DEV = ".XDFDevelopDevActivity";

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
        try {
            hookDevFragmentActivityCreated(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookDevFragmentActivityCreated");
        }
    }

    /**
     * 开发者选项承载破解（XDF 强转锁移除）：
     * XDF 魔改 DevelopmentSettingsDashboardFragment.onActivityCreated 强转
     * getActivity() 为 XDFDevelopDevActivity，从任何其他 Activity（SubSettings/
     * SettingsActivity）承载该 fragment 必抛 ClassCastException。
     * 方案：hook onActivityCreated 跳过 XDF 实现，仅调用基类 Fragment 的空实现
     * （AOSP 原版该行只是注册 enabler 监听，跳过无功能损失）。
     * 效果：开发者选项像普通设置页一样在右侧容器内原生展开（不跳 Activity，
     * 体验与 AOSP 完全一致）；development_settings.xml 完整条目照常渲染。
     */
    /** 开发者选项解锁：双层保险
     *  ① DevelopmentSettingsDashboardFragment.onActivityCreated —— 跳过 XDF 强转
     *     (XDFDevelopDevActivity)getActivity()（任何其他宿主必 ClassCastException），
     *     同时恢复 XDF 原版 enabler+SwitchBar 初始化（保留顶部开发开关）；
     *  ② androidx Fragment.performActivityCreated —— FragmentManager 在此时检查
     *     mCalled（否则 SuperNotCalledException），此处兜底置位，防个别恢复路径
     *     绕过 ① 的 hook（实测发生过：崩溃片段未经过 ① 的直接调用）。
     */
    private static void hookDevFragmentActivityCreated(ClassLoader cl) throws Exception {
        final Class<?> baseFrag = Reflect.findClass("androidx.fragment.app.Fragment", cl);
        // ② 契约兜底层
        XDFHook.hookMethod(cl, "androidx.fragment.app.Fragment", "performActivityCreated",
                new Class<?>[]{Bundle.class},
                chain -> {
                    try {
                        Object self = chain.getThisObject();
                        if (DEV_FRAG.equals(self.getClass().getName())) {
                            setMCalled(self, cl);
                            ensureDevSwitch(self, cl);
                            XDFHook.logi(TAG, "dev performActivityCreated: contract ensured");
                        }
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "devPerfActivityCreated");
                    }
                    return chain.proceed();
                });
        // ① 强转跳过层
        XDFHook.hookMethod(cl, DEV_FRAG, "onActivityCreated",
                new Class<?>[]{Bundle.class},
                chain -> {
                    try {
                        setMCalled(chain.getThisObject(), cl);
                        ensureDevSwitch(chain.getThisObject(), cl);
                        XDFHook.logi(TAG, "dev fragment host unlock (skip XDF cast)");
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "devFragmentHostUnlock");
                    }
                    return null; // 跳过 XDF 原实现（强转崩溃点）
                });
        XDFHook.logi(TAG, "dev fragment host unlock armed");
    }

    /** androidx Fragment.mCalled 置位（生命周期契约） */
    private static void setMCalled(Object frag, ClassLoader cl) throws Exception {
        java.lang.reflect.Field f = Reflect.findField(baseFragOf(cl), "mCalled");
        f.setAccessible(true);
        f.setBoolean(frag, true);
    }

    private static Class<?> baseFragOf(ClassLoader cl) throws Exception {
        return Reflect.findClass("androidx.fragment.app.Fragment", cl);
    }

    /** 直接在右侧内容容器 replace 目标 fragment（绕开 XDF 对非 Dashboard 开新窗口的 fallback）
     *  容器 id 0x7f0a029f = XDF 主窗体右侧 fragment 容器（dev 崩溃栈实证） */
    private static void replaceRightFragment(Object act, String fragName,
            Object bundle, ClassLoader cl) throws Exception {
        Class<?> fragCls = Reflect.findClass("androidx.fragment.app.Fragment", cl);
        Object f = Reflect.callStatic(fragCls, "instantiate",
                new Class<?>[]{android.content.Context.class, String.class, Bundle.class},
                act, fragName, bundle);
        Object fm = Reflect.call(act, "getSupportFragmentManager", new Class<?>[0]);
        Object tx = Reflect.call(fm, "beginTransaction", new Class<?>[0]);
        Reflect.call(tx, "replace",
                new Class<?>[]{int.class, fragCls}, 0x7f0a029f, f);
        Reflect.call(tx, "addToBackStack", new Class<?>[]{String.class}, (String) null);
        Reflect.call(tx, "commit", new Class<?>[0]);
        // 同步 XDF 当前条目状态（避免切 tab 时重复判定）
        try {
            Reflect.setField(act, "mItemFragmentName", fragName);
        } catch (Throwable ignored) {
        }
    }

    /** 幂等恢复 XDF 原版初始化：开发开关 enabler + SwitchBar 绑定 */
    private static void ensureDevSwitch(Object frag, ClassLoader cl) throws Exception {
        Object enabler = Reflect.getField(frag, "mDevelopmentSettingsEnabler");
        if (enabler != null) {
            return;
        }
        Object act = Reflect.call(frag, "getActivity", new Class<?>[0]);
        if (act == null) {
            return;
        }
        Object sb = Reflect.call(act, "getSwitchBar", new Class<?>[0]);
        Class<?> listenerCls = Class.forName(DEV_FRAG + "$OnToggleChangeListener", false, cl);
        Object listener = Reflect.newInstance(listenerCls,
                new Class<?>[]{frag.getClass()}, frag);
        Class<?> enablerCls = Class.forName(
                "com.android.settings.development.DevelopmentSettingsEnabler", false, cl);
        Class<?> iface = Class.forName(
                "com.android.settings.widget.SwitchBar$OnSwitchChangeListener", false, cl);
        Object e = Reflect.newInstance(enablerCls,
                new Class<?>[]{android.content.Context.class, iface},
                Reflect.call(frag, "getContext", new Class<?>[0]), listener);
        Reflect.setField(frag, "mDevelopmentSettingsEnabler", e);
        Reflect.call(sb, "addOnSwitchChangeListener", new Class<?>[]{iface}, e);
        Reflect.call(sb, "show", new Class<?>[0]);
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
    /** 最后一项圆角背景（main_left_item_checked_bottom_selector）：
     *  更多设置位于列表末尾，与"手写笔和键盘"同款 */
    private static final int DRAWABLE_MAIN_BG_SELECTOR = 0x7f0802b3;
    private static final int COLOR_LEFT_TEXT = 0x7f060134;   // setting_menu_left_text_colors
    private static final int DRAWABLE_IC_MORE = 0x7f0f0007;  // ic_launcher_settings（设置齿轮）
    private static final String DEV_FRAG = "com.android.settings.development"
            + ".DevelopmentSettingsDashboardFragment";
    /** 进入 AOSP 首页的 fragment */
    private static final String FRAG_AOSP_HOME = "com.android.settings.homepage.TopLevelSettings";

    private static volatile int sMoreSettingsId = -1;
    /** 当前是否选中"更多设置"tab（TopLevelSettings 点击时用于路由拦截） */
    private static volatile boolean sMoreActive = false;

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
                        // 仅主窗体注入左侧选项卡；SubSettings 等 SettingsActivity 子类
                        // 也有 RadioGroup，注入会污染新开窗口
                        if (!"com.android.settings.Settings".equals(
                                activity.getClass().getName())) {
                            return activity;
                        }
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
        // 2) onCheckedChanged：命中"更多设置"时阻断式完全接管——
        //    自己执行原方法尾部逻辑（switchRadioButtonTypeface/popBackStack/
        //    switchToFragment），不回原方法，避免其内部 getCheckedRadioButtonId
        //    或递归行为把选中态/页面带回"我的设备"。
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

                            // 原方法尾部：字体随选中（我们的 button 已 checked → 粗体）
                            Reflect.call(activity, "switchRadioButtonTypeface",
                                    new Class<?>[]{RadioGroup.class}, chain.getArg(0));
                            // 清空返回栈（与原逻辑一致）
                            Object fm = Reflect.call(activity, "getSupportFragmentManager", null);
                            Reflect.call(fm, "popBackStack",
                                    new Class<?>[]{String.class, int.class}, null, 1);
                            // 右侧内容区动态替换为 AOSP 15 分类首页（同 Activity，无新窗口）
                            Reflect.call(activity, "switchToFragment",
                                    new Class<?>[]{String.class, Bundle.class, boolean.class,
                                            int.class, CharSequence.class},
                                    FRAG_AOSP_HOME, args, true, 0x7f1204d2,
                                    Reflect.getField(activity, "mInitialTitle"));
                            XDFHook.logi(TAG, "more-settings tab: take-over -> TopLevelSettings");
                            sMoreActive = true;
                            return null; // 阻断原方法
                        } else {
                            sMoreActive = false;
                        }
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "onCheckedChanged route");
                    }
                    return chain.proceed();
                });
        XDFHook.logi(TAG, "main tabs armed");
        try {
            hookTopLevelTreeClick(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookTopLevelTreeClick");
        }
    }

    /**
     * 更多设置会话内（左栏选中"更多设置"）：拦截一切 Preference 条目的
     * fragment 打开请求（XDF 两条出口都会开新 SubSettings：
     *  ① TopLevelSettings 分类卡 → SubSettingLauncher；
     *  ② MyDeviceInfoFragment 等非 Dashboard 页 → onPreferenceStartFragment 同路）。
     * 挂在最基类 PreferenceFragmentCompat.onPreferenceTreeClick 上，所有
     * Preference 页点击必经；命中后改走 XDF 主窗体的 switchToFragment(String,...)
     * 右侧替换，与原生 tab 一致，左栏保持"更多设置"高亮。
     */
    private static void hookTopLevelTreeClick(ClassLoader cl) throws Exception {
        final Class<?> prefCls = Reflect.findClass("androidx.preference.Preference", cl);
        XDFHook.hookMethod(cl, "com.android.settings.dashboard.DashboardFragment",
                "onPreferenceTreeClick", new Class<?>[]{prefCls},
                chain -> {
                    try {
                        if (!sMoreActive) {
                            return chain.proceed();
                        }
                        Object act = Reflect.call(chain.getThisObject(),
                                "getActivity", new Class<?>[0]);
                        if (act == null) {
                            return chain.proceed();
                        }
                        Object pref = chain.getArg(0);
                        String fragName = (String) Reflect.call(pref,
                                "getFragment", new Class<?>[0]);
                        if (fragName == null) {
                            return chain.proceed(); // 非 fragment 条目（如 intent）走原逻辑
                        }
                        Object bundle = Reflect.call(pref, "getExtras", new Class<?>[0]);
                        if (bundle == null) {
                            bundle = new Bundle();
                        }
                        CharSequence title = (CharSequence) Reflect.call(pref,
                                "getTitle", new Class<?>[0]);
                        if (title == null) {
                            title = "";
                        }
                        // XDF 的 switchToFragment(String...) 仅对 DashboardFragment 目标
                        // 做右侧替换；非 Dashboard（如 StorageSettings=SettingsPreferenceFragment）
                        // 会走"开新窗口"fallback。因此可按类分流：
                        //  Dashboard → 复刻 take-over 的原生方法（标题/状态同步）；
                        //  其他     → 自建 FragmentTransaction.replace 右栏。
                        Class<?> dashCls = Reflect.findClass(
                                "com.android.settings.dashboard.DashboardFragment", cl);
                        Class<?> targetCls = Reflect.findClass(fragName, cl);
                        if (dashCls.isAssignableFrom(targetCls)) {
                            Reflect.call(act, "switchToFragment",
                                    new Class<?>[]{String.class, Bundle.class,
                                            boolean.class, int.class, CharSequence.class},
                                    fragName, bundle, true, 0x7f1204d2, title);
                        } else {
                            replaceRightFragment(act, fragName, bundle, cl);
                        }
                        XDFHook.logi(TAG, "more-settings item -> in-place: " + fragName);
                        return true;
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "topLevelRoute");
                        return chain.proceed();
                    }
                });
        // 3) SubSettingLauncher.launch()：XDF 打开新 SubSettings 的统一出口。
        //    部分条目的点击不经过 DashboardFragment.onPreferenceTreeClick（如存储卡
        //    独有路径），直接到 launcher；更多设置会话内一律吞掉，防止新窗口叠加。
        XDFHook.hookMethod(cl, "com.android.settings.core.SubSettingLauncher",
                "launch", new Class<?>[0],
                chain -> {
                    try {
                        if (sMoreActive) {
                            // 转右侧替换：XDF 的 destination/arguments 在
                            // LaunchRequest（字段 destinationName/arguments）
                            Object launcher = chain.getThisObject();
                            Object req = Reflect.getField(launcher, "mLaunchRequest");
                            String dest = (String) Reflect.getField(req, "destinationName");
                            Object args = Reflect.getField(req, "arguments");
                            Object act = Reflect.getField(launcher, "mContext");
                            if (dest != null && act instanceof android.app.Activity) {
                                replaceRightFragment(act, dest, args, cl);
                                XDFHook.logi(TAG, "launcher -> in-place: " + dest);
                                return null;
                            }
                        }
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "launcherToInPlace");
                    }
                    return chain.proceed();
                });
        XDFHook.logi(TAG, "top-level tree click route armed");
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

            // 语义+尺寸双对齐：用"设置"齿轮图标 ic_launcher_settings
            // （5 档 mipmap，与其他 tab 图标尺寸规格一致）
            int icon = ctx.getResources().getIdentifier(
                    "ic_launcher_settings", "mipmap", ctx.getPackageName());
            if (icon == 0) {
                icon = DRAWABLE_IC_MORE;
            }
            if (icon != 0) {
                // 齿轮 png 为 90px(hdpi) 启动器规格，直接 intrinsic 会≈60dp 过大；
                // 强制缩放到与原生 tab 图标一致的 32dp（48px hdpi × 1.25 / density）
                android.graphics.drawable.Drawable d =
                        ctx.getResources().getDrawable(icon, ctx.getTheme());
                int s = dp(ctx, 32);
                d.setBounds(0, 0, s, s);
                rb.setCompoundDrawablesRelative(d, null, null, null);
            }

            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT, dp(ctx, 68));
            // 与原生 RadioButton 一致：上下均靠条目自身 marginBottom(20.8dp) 分隔
            lp.setMargins(dp(ctx, 26), 0, dp(ctx, 26), dp(ctx, 21));
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
