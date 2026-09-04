package cn.cf3012.xdf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Proxy;

/**
 * 在 UI 层还原被 XDF 定制 ROM 裁剪/隐藏的设置入口（LibXposed api 102 版本）。
 *
 * 注入策略（"具体类 + onCreate 延迟注入"）：
 *   hook 每个页面【具体类】的 onCreate(Bundle)，after 后用 Handler.post
 *   延迟到主线程下一条消息执行注入。此时 onViewCreated/onCreatePreferences
 *   已完成，PreferenceScreen 已就绪（onCreate 阶段直接注入会拿到 null）。
 *   具体类 hook 已被验证有效（Settings.onResume 生效），不依赖基类 hook。
 *
 * 系统页的"日期时间/备份/多用户"由 ROM 的 dashboard tile 机制自动恢复
 * （persist.sys.settings_control 放行后 SystemDashboardFragment.onCreate
 *  会动态添加 dashboard_tile_pref_* 条目），本类只打印诊断确认 + 兜底
 *  注入"开发者选项"（enabled=false 组件不会生成 tile）。
 */
final class UiRestorer {

    private static final String TAG = "settings.ui";

    private static final String PKG = "com.android.settings";

    // ---- 页面类 ----
    private static final String CLS_DISPLAY = PKG + ".DisplaySettings";
    private static final String CLS_SOUND = PKG + ".notification.SoundSettings";
    private static final String CLS_SYSTEM = PKG + ".system.SystemDashboardFragment";
    private static final String CLS_TOP_LEVEL = PKG + ".homepage.TopLevelSettings";

    // ---- 跳转目标 fragment ----
    private static final String FRAG_NIGHT_DISPLAY = PKG + ".display.NightDisplaySettings";
    private static final String FRAG_FONT_SIZE = PKG + ".display.ToggleFontSizePreferenceFragment";
    private static final String FRAG_SCREEN_ZOOM = PKG + ".display.ScreenZoomSettings";
    private static final String FRAG_ZEN_MODE = PKG + ".notification.ZenModeSettings";
    private static final String FRAG_SYSTEM_NAV = PKG + ".gestures.SystemNavigationGestureSettings";

    // ---- 跳转目标 Activity（均已在 Manifest 注册）----
    private static final String ACT_WALLPAPER = PKG + ".Settings$WallpaperSettingsActivity";
    private static final String ACT_DEV_OPTIONS =
            PKG + ".Settings$DevelopmentSettingsDashboardActivity";

    // ---- androidx 类（Settings 进程内存在）----
    private static final String CLS_PREFERENCE = "androidx.preference.Preference";
    private static final String CLS_PREF_SCREEN = "androidx.preference.PreferenceScreen";
    private static final String CLS_PREF_CATEGORY = "androidx.preference.PreferenceCategory";
    private static final String CLS_SWITCH_PREF = "androidx.preference.SwitchPreferenceCompat";
    private static final String IFACE_PREF_LISTENER =
            "androidx.preference.Preference$OnPreferenceChangeListener";
    private static final String IFACE_LIFECYCLE_OBSERVER = "androidx.lifecycle.LifecycleObserver";

    // ---- 音量条/控制器（MTK/XDF 定制与 AOSP 类均存在）----
    private static final String CLS_XDF_VOLUME_PREF =
            PKG + ".notification.XdfVolumeSeekBarPerference";
    private static final String CLS_ALARM_CTRL = PKG + ".notification.AlarmVolumePreferenceController";
    private static final String CLS_RING_CTRL = PKG + ".notification.RingVolumePreferenceController";
    private static final String CLS_CALL_CTRL = PKG + ".notification.CallVolumePreferenceController";

    private UiRestorer() {
    }

    /* ================================================================== */

    private interface PageHook {
        void apply(Object fragment, Context ctx);
    }

    static void hookAll(ClassLoader cl) throws Exception {
        hookConcretePost(CLS_TOP_LEVEL, cl, UiRestorer::diagnoseTopLevel);
        hookConcretePost(CLS_DISPLAY, cl, UiRestorer::restoreDisplayPage);
        hookConcretePost(CLS_SOUND, cl, UiRestorer::restoreSoundPage);
        hookConcretePost(CLS_SYSTEM, cl, UiRestorer::restoreSystemPage);
        XDFHook.logi(TAG, "UI restorer armed (concrete onCreate + post)");
    }

    /** hook 具体类 onCreate(Bundle)，after 后 post 到主线程延迟注入 */
    private static void hookConcretePost(String cls, ClassLoader cl, PageHook pageHook)
            throws Exception {
        Class<?> clazz = Class.forName(cls, false, cl);
        XDFHook.hookMethod(clazz, "onCreate", new Class<?>[]{Bundle.class}, chain -> {
            try {
                Object fragment = chain.getThisObject();
                Context ctx = (Context) Reflect.call(fragment, "getContext", null);
                if (ctx == null) {
                    return chain.proceed();
                }
                // onCreate 阶段 PreferenceScreen 尚为 null，延迟到主线程下一条消息
                // （onViewCreated/onCreatePreferences 已执行完毕）
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        pageHook.apply(fragment, ctx);
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "post inject " + cls);
                    }
                });
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "onCreate " + cls);
            }
            return chain.proceed();
        });
    }

    /* ==================== 顶层页（诊断） ==================== */

    private static void diagnoseTopLevel(Object fragment, Context ctx) {
        Object screen = getScreen(fragment);
        if (screen == null) {
            XDFHook.logi(TAG, "[top] screen null");
            return;
        }
        logKeys("[top]", screen);
        XDFHook.logi(TAG, " [top] has apps_and_notifs="
                + hasPref(screen, "top_level_apps_and_notifs")
                + " has privacy=" + hasPref(screen, "top_level_privacy"));
    }

    /* ==================== 显示页 ==================== */

    private static void restoreDisplayPage(Object fragment, Context ctx) {
        Object screen = getScreen(fragment);
        if (screen == null) {
            XDFHook.logi(TAG, "display page: screen null, skip");
            return;
        }
        // 分组：注入项放入 "更多显示设置" 分类，视觉层次与原生一致
        Object cat;
        try {
            cat = newPref(ctx, CLS_PREF_CATEGORY);
            Reflect.call(cat, "setKey", new Class<?>[]{String.class}, "xdf_more_display");
            Reflect.call(cat, "setTitle", new Class<?>[]{CharSequence.class}, "更多显示设置");
            addToScreen(ctx, screen, cat);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "display category");
            cat = screen; // 降级：直接挂到屏幕
        }

        addNavPref(ctx, cat, "xdf_night_display", "夜间模式", FRAG_NIGHT_DISPLAY, null,
                "ic_settings_night_display", "ic_night_display");
        addSwitchPref(ctx, cat, "xdf_dark_ui", "深色主题", DarkModeSwitch.INSTANCE);
        addNavPref(ctx, cat, "xdf_wallpaper", "壁纸", null, intentTo(ACT_WALLPAPER),
                "ic_settings_wallpaper", "ic_wallpaper");
        addNavPref(ctx, cat, "xdf_font_size", "字体大小", FRAG_FONT_SIZE, null,
                "ic_settings_font_size", "ic_font_size");
        addNavPref(ctx, cat, "xdf_screen_zoom", "显示大小", FRAG_SCREEN_ZOOM, null,
                "ic_settings_display_size", "ic_display_size");
        addSwitchPref(ctx, cat, "xdf_auto_rotate", "自动旋转", AutoRotateSwitch.INSTANCE);
        addNavPref(ctx, cat, "xdf_system_navigation", "系统导航", FRAG_SYSTEM_NAV, null,
                "ic_settings_navigation", "ic_system_navigation", "ic_navigation");
        logKeys("[display]", screen);
        XDFHook.logi(TAG, "display page restored");
    }

    /* ==================== 声音页 ==================== */

    private static void restoreSoundPage(Object fragment, Context ctx) {
        Object screen = getScreen(fragment);
        if (screen == null) {
            XDFHook.logi(TAG, "sound page: screen null, skip");
            return;
        }
        Object cat;
        try {
            cat = newPref(ctx, CLS_PREF_CATEGORY);
            Reflect.call(cat, "setKey", new Class<?>[]{String.class}, "xdf_more_sound");
            Reflect.call(cat, "setTitle", new Class<?>[]{CharSequence.class}, "更多声音设置");
            addToScreen(ctx, screen, cat);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "sound category");
            cat = screen;
        }

        addVolumePref(ctx, fragment, screen, cat, CLS_ALARM_CTRL, "alarm_volume", "闹钟音量");
        addVolumePref(ctx, fragment, screen, cat, CLS_RING_CTRL, "ring_volume", "铃声音量");
        addVolumePref(ctx, fragment, screen, cat, CLS_CALL_CTRL, "call_volume", "通话音量");
        addNavPref(ctx, cat, "xdf_zen_mode", "勿扰模式", FRAG_ZEN_MODE, null,
                "ic_settings_notifications", "ic_notifications", "ic_zen_mode");
        logKeys("[sound]", screen);
        XDFHook.logi(TAG, "sound page restored");
    }

    /* ==================== 系统页 ==================== */

    private static void restoreSystemPage(Object fragment, Context ctx) {
        Object screen = getScreen(fragment);
        if (screen == null) {
            XDFHook.logi(TAG, "system page: screen null, skip");
            return;
        }
        // 诊断：确认 dashboard tile（日期时间/备份/多用户）是否已自动出现
        logKeys("[system]", screen);
        // 开发者选项 tile 不会由 enabled=false 组件生成，显式注入兜底
        if (!hasPref(screen, "xdf_dev_options")) {
            addNavPref(ctx, screen, "xdf_dev_options", "开发者选项", null,
                    intentTo(ACT_DEV_OPTIONS));
        }
        XDFHook.logi(TAG, "system page restored");
    }

    /* ==================== 通用工具（显式参数类型） ==================== */

    private static void logKeys(String tag, Object screen) {
        try {
            int count = (int) Reflect.call(screen, "getPreferenceCount", null);
            StringBuilder sb = new StringBuilder(tag + " keys(" + count + "): ");
            for (int i = 0; i < count; i++) {
                Object p = Reflect.call(screen, "getPreference", new Class<?>[]{int.class}, i);
                sb.append(Reflect.call(p, "getKey", null)).append(", ");
            }
            XDFHook.logi(TAG, sb.toString());
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, tag + " logKeys");
        }
    }

    private static Object getScreen(Object fragment) {
        try {
            return Reflect.call(fragment, "getPreferenceScreen", null);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "getPreferenceScreen");
            return null;
        }
    }

    private static boolean hasPref(Object screen, String key) {
        try {
            return Reflect.call(screen, "findPreference",
                    new Class<?>[]{CharSequence.class}, key) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 普通跳转条目：fragment 与 intent 二选一；iconCandidates 为 drawable 资源名候选 */
    private static void addNavPref(Context ctx, Object group, String key, String title,
                                   String fragment, Intent intent, String... iconCandidates) {
        try {
            if (hasPref(group, key)) {
                return;
            }
            Object pref = newPref(ctx, CLS_PREFERENCE);
            Reflect.call(pref, "setKey", new Class<?>[]{String.class}, key);
            Reflect.call(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            if (fragment != null) {
                Reflect.call(pref, "setFragment", new Class<?>[]{String.class}, fragment);
            }
            if (intent != null) {
                Reflect.call(pref, "setIntent", new Class<?>[]{Intent.class}, intent);
            }
            int icon = findIcon(ctx, iconCandidates);
            if (icon != 0) {
                Reflect.call(pref, "setIcon", new Class<?>[]{int.class}, icon);
            }
            addToScreen(ctx, group, pref);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addNavPref(" + key + ")");
        }
    }

    /** 开关条目：直接读写系统设置 */
    private static void addSwitchPref(Context ctx, Object group, String key, String title,
                                      SwitchLogic logic) {
        try {
            if (hasPref(group, key)) {
                return;
            }
            Object pref = Reflect.newInstance(Reflect.findClass(CLS_SWITCH_PREF, ctx.getClassLoader()),
                    new Class<?>[]{Context.class, android.util.AttributeSet.class}, ctx, null);
            Reflect.call(pref, "setKey", new Class<?>[]{String.class}, key);
            Reflect.call(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            Reflect.call(pref, "setChecked", new Class<?>[]{boolean.class}, logic.isOn(ctx));

            Class<?> iface = Reflect.findClass(IFACE_PREF_LISTENER, ctx.getClassLoader());
            Object listener = Proxy.newProxyInstance(iface.getClassLoader(),
                    new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        if ("onPreferenceChange".equals(method.getName())) {
                            boolean on = Boolean.TRUE.equals(args[1]);
                            logic.setOn(ctx, on);
                            return true;
                        }
                        return null;
                    });
            Reflect.call(pref, "setOnPreferenceChangeListener",
                    new Class<?>[]{iface}, listener);
            addToScreen(ctx, group, pref);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addSwitchPref(" + key + ")");
        }
    }

    /** 音量条条目：复用 XDF 定制控件 + 现成 controller，并绑定 fragment lifecycle */
    private static void addVolumePref(Context ctx, Object fragment, Object screen, Object group,
                                      String ctrlCls, String key, String title) {
        try {
            if (hasPref(group, key)) {
                return;
            }
            Object pref = newPref(ctx, CLS_XDF_VOLUME_PREF);
            Reflect.call(pref, "setKey", new Class<?>[]{String.class}, key);
            Reflect.call(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            addToScreen(ctx, group, pref);

            Object ctrl = newVolumeController(ctx, ctrlCls, key);
            Reflect.call(ctrl, "displayPreference",
                    new Class<?>[]{Reflect.findClass(CLS_PREF_SCREEN, ctx.getClassLoader())}, screen);

            // 绑定 fragment lifecycle，使音量条随页面 onResume/onPause 正确 init/释放
            Object lifecycle = Reflect.call(fragment, "getSettingsLifecycle", null);
            Reflect.call(lifecycle, "addObserver",
                    new Class<?>[]{Reflect.findClass(IFACE_LIFECYCLE_OBSERVER, ctx.getClassLoader())},
                    ctrl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addVolumePref(" + key + ")");
        }
    }

    /** 在 Settings 资源里按名字候选查找 drawable（找不到返回 0） */
    private static int findIcon(Context ctx, String... candidates) {
        if (candidates == null) {
            return 0;
        }
        for (String name : candidates) {
            int id = ctx.getResources().getIdentifier(name, "drawable", PKG);
            if (id != 0) {
                return id;
            }
        }
        return 0;
    }

    /** 音量 controller 构造：Alarm/Ring 为 (Context)，Call 为 (Context, String) */
    private static Object newVolumeController(Context ctx, String ctrlCls, String key)
            throws Exception {
        Class<?> clazz = Reflect.findClass(ctrlCls, ctx.getClassLoader());
        try {
            return Reflect.newInstance(clazz, new Class<?>[]{Context.class}, ctx);
        } catch (NoSuchMethodException e) {
            return Reflect.newInstance(clazz,
                    new Class<?>[]{Context.class, String.class}, ctx, key);
        }
    }

    private static Object newPref(Context ctx, String clsName) throws Exception {
        return Reflect.newInstance(Reflect.findClass(clsName, ctx.getClassLoader()),
                new Class<?>[]{Context.class}, ctx);
    }

    private static void addToScreen(Context ctx, Object screen, Object pref) throws Exception {
        Reflect.call(screen, "addPreference",
                new Class<?>[]{Reflect.findClass(CLS_PREFERENCE, ctx.getClassLoader())}, pref);
    }

    private static Intent intentTo(String activityCls) {
        return new Intent().setComponent(new ComponentName(PKG, activityCls));
    }

    /* ==================== 开关逻辑 ==================== */

    private interface SwitchLogic {
        boolean isOn(Context ctx);

        void setOn(Context ctx, boolean on);
    }

    /** 深色主题：Settings.Secure "ui_night_mode"，2=开启 1=关闭 */
    private static final class DarkModeSwitch implements SwitchLogic {
        private static final String UI_NIGHT_MODE = "ui_night_mode";
        static final DarkModeSwitch INSTANCE = new DarkModeSwitch();

        @Override
        public boolean isOn(Context ctx) {
            return Settings.Secure.getInt(ctx.getContentResolver(), UI_NIGHT_MODE, 1) == 2;
        }

        @Override
        public void setOn(Context ctx, boolean on) {
            Settings.Secure.putInt(ctx.getContentResolver(), UI_NIGHT_MODE, on ? 2 : 1);
        }
    }

    /** 自动旋转：Settings.System.ACCELEROMETER_ROTATION，1=开 0=关 */
    private static final class AutoRotateSwitch implements SwitchLogic {
        static final AutoRotateSwitch INSTANCE = new AutoRotateSwitch();

        @Override
        public boolean isOn(Context ctx) {
            return Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        }

        @Override
        public void setOn(Context ctx, boolean on) {
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
        }
    }
}
