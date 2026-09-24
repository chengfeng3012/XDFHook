package cn.cf3012.xdf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在 UI 层还原被 XDF 定制 ROM 裁剪/隐藏的设置入口（LibXposed api 102 版本）。
 *
 * 注入策略：
 *   hook 每个页面【具体类】的 onCreate(Bundle)，after 后用 Handler.post 延迟到主线程
 *   下一条消息执行注入（onViewCreated/onCreatePreferences 已完成，Screen 已就绪）。
 *
 * 【风格约束（与设备原生完全一致）】
 *   XDF 显示/声音页原生条目使用硬编码布局 xdf_auto_brightness_toggle_preference
 *   （18sp 粗体标题、#1E1E1E、24dp 起始边距、50dp 行高+12dp 上边距、大开关、无图标区）。
 *     - 开关条目 → 直接实例化 XdfAutoBrightnessTogglePreference（通用视觉壳：
 *       构造只 setLayoutResource，onBindViewHolder 只隐藏图标区+显示开关区，无业务逻辑）
 *     - 导航/对话框条目 → 复用同一布局 + sHideSwitchKeys 集合 + 双 onBindViewHolder
 *       hook 把布局里的 Switch 与右侧容器隐藏（导航条目载体也用 XdfToggle）
 *     - 音量条目 → XdfVolumeSeekBarPerference（原生同款）
 *     - 分类标题 → setIconSpaceReserved(false) 去掉图标占位缩进（主题 CategoryTitle 样式）
 *   本模块 compileOnly 无 androidx.preference 依赖，对宿主类型一律反射操作。
 *
 * 【分级结构严格按 AOSP 原版】
 *   - 显示页：AOSP 平面列表（无分类），条目按 display_settings.xml 相对顺序注入；
 *     跳过：自适应睡眠/相机手势/抬起唤醒/轻触唤醒/运营商显示/主题/VR（硬件不适配）；
 *     系统导航不注入本页（AOSP 显示页无此条目）
 *   - 声音页：音量平铺 + 勿扰 + 防铃声手势 + 三个铃声 + "其他声音和振动"分类
 *     （dial_pad/screen_lock/charging/touch/vibrate_on_touch）；
 *     跳过：底座音/开机音/工作资料声音/HFP 输出/紧急提示音
 *   - 系统页：dashboard tile 由 settings_control 放行后自动恢复，本类只兜底开发者选项
 *   - 通知渠道入口（ChannelList）XDF 中类已删，放弃（95% 目标内损耗）
 */
final class UiRestorer {

    private static final String TAG = "settings.ui";

    private static final String PKG = "com.android.settings";

    // ---- 页面类 ----
    private static final String CLS_DISPLAY = PKG + ".DisplaySettings";
    private static final String CLS_SOUND = PKG + ".notification.SoundSettings";
    private static final String CLS_SYSTEM = PKG + ".system.SystemDashboardFragment";
    private static final String CLS_TOP_LEVEL = PKG + ".homepage.TopLevelSettings";

    // ---- 跳转目标 fragment（AOSP 原版条目指向） ----
    private static final String FRAG_NIGHT_DISPLAY = PKG + ".display.NightDisplaySettings";
    private static final String FRAG_FONT_SIZE = PKG + ".display.ToggleFontSizePreferenceFragment";
    private static final String FRAG_SCREEN_ZOOM = PKG + ".display.ScreenZoomSettings";
    private static final String FRAG_COLOR_MODE = PKG + ".display.ColorModePreferenceFragment";
    private static final String FRAG_DREAM = PKG + ".dream.DreamSettings";
    private static final String FRAG_LOCKSCREEN = PKG + ".security.LockscreenDashboardFragment";
    private static final String FRAG_ZEN_MODE = PKG + ".notification.ZenModeSettings";
    private static final String FRAG_PREVENT_RINGING =
            PKG + ".gestures.PreventRingingGestureSettings";
    private static final String FRAG_DEV = PKG
            + ".development.DevelopmentSettingsDashboardFragment";

    // ---- 跳转目标 Activity（均已在 Manifest 注册） ----
    private static final String ACT_WALLPAPER = PKG + ".Settings$WallpaperSettingsActivity";
    private static final String ACT_DEV_OPTIONS =
            PKG + ".Settings$DevelopmentSettingsDashboardActivity";

    // ---- 宿主类名（全部反射引用） ----
    private static final String CLS_PREF = "androidx.preference.Preference";
    private static final String CLS_PREF_HOLDER = "androidx.preference.PreferenceViewHolder";
    private static final String CLS_PREF_SCREEN = "androidx.preference.PreferenceScreen";
    private static final String CLS_PREF_CATEGORY = "androidx.preference.PreferenceCategory";
    private static final String CLS_LIST_PREF = "androidx.preference.ListPreference";
    private static final String IFACE_PREF_LISTENER =
            "androidx.preference.Preference$OnPreferenceChangeListener";

    // ---- XDF 原生控件（风格一致的根基） ----
    private static final String CLS_XDF_TOGGLE =
            PKG + ".display.XdfAutoBrightnessTogglePreference";
    private static final String CLS_XDF_VOLUME_PREF =
            PKG + ".notification.XdfVolumeSeekBarPerference";
    private static final String CLS_XDF_RINGTONE = PKG + ".DefaultRingtonePreference";
    private static final String CLS_ALARM_CTRL = PKG
            + ".notification.AlarmVolumePreferenceController";
    private static final String CLS_RING_CTRL = PKG
            + ".notification.RingVolumePreferenceController";
    private static final String CLS_CALL_CTRL = PKG
            + ".notification.CallVolumePreferenceController";
    private static final String CLS_MEDIA_OUTPUT_CTRL =
            PKG + ".sound.MediaOutputPreferenceController";
    private static final String CLS_WHITE_BALANCE_CTRL =
            PKG + ".display.DisplayWhiteBalancePreferenceController";

    /** XDF 原生条目布局（18sp 粗体/24dp margin/50dp 行高/大开关） */
    private static final String LAYOUT_XDF_PREF = "xdf_auto_brightness_toggle_preference";
    private static final int LAYOUT_XDF_PREF_FALLBACK = 0x7f0d0258;
    /** 布局内：右侧 widget 容器 与 Switch 控件（android.R.id 常量） */
    private static final int ID_WIDGET_FRAME = 0x01020018;
    private static final int ID_SWITCH = 0x01020040;

    /**
     * 用 XDF 布局但"不该出现开关"的条目 key（导航/对话框类）。
     * bind 后由双 hook（XdfToggle.onBind 后置 + Preference.onBind 后置）隐藏 Switch。
     */
    private static final Set<String> sHideSwitchKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

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
        hookSwitchHide(cl);
        XDFHook.logi(TAG, "UI restorer armed (AOSP structure, XDF style)");
    }

    /**
     * 双 hook 隐藏 XDF 布局中的 Switch（仅 sHideSwitchKeys 中的条目）：
     *   1) XdfAutoBrightnessTogglePreference.onBind（最外层）→ 导航载体条目收尾隐藏
     *   2) androidx Preference.onBind（基类层）→ Ringtone/List 等未 override 的条目
     * 两者都先 proceed() 再处理；开关条目不在集合中不受影响。
     */
    private static void hookSwitchHide(ClassLoader cl) {
        Class<?> holderCls = null;
        try {
            holderCls = Reflect.findClass(CLS_PREF_HOLDER, cl);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "PreferenceViewHolder not found: " + t);
        }
        if (holderCls == null) {
            return;
        }
        // 1) XdfToggle 层（导航/对话框载体也实例化为 XdfToggle，此处最终收尾）
        XDFHook.safeHook(cl, CLS_XDF_TOGGLE, "onBindViewHolder",
                new Class<?>[]{holderCls}, chain -> {
                    chain.proceed();
                    hideSwitchIfNeeded(chain.getArg(0), chain.getThisObject());
                    return null;
                }, "UiRestorer.XdfToggle.hideSwitch");
        // 2) Preference 基类层（Ringtone/List 等直接用基类 bind 的条目）
        XDFHook.safeHook(cl, CLS_PREF, "onBindViewHolder",
                new Class<?>[]{holderCls}, chain -> {
                    chain.proceed();
                    hideSwitchIfNeeded(chain.getArg(0), chain.getThisObject());
                    return null;
                }, "UiRestorer.Preference.hideSwitch");
    }

    /** icon 容器（XDF 布局 @7F0A022B）：基类 bind 会把它重新 VISIBLE 导致 105px 缩进 */
    private static final int ID_ICON_FRAME = 0x7f0a022b;

    private static void hideSwitchIfNeeded(Object holderObj, Object prefObj) {
        try {
            String key = (String) Reflect.call(prefObj, "getKey", null);
            if (key == null || !sHideSwitchKeys.contains(key)) {
                return;
            }
            hideById(holderObj, ID_SWITCH);
            hideById(holderObj, ID_WIDGET_FRAME);
            hideById(holderObj, ID_ICON_FRAME);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hideSwitchIfNeeded");
        }
    }

    private static void hideById(Object holderObj, int id) {
        try {
            View v = (View) Reflect.call(holderObj, "findViewById",
                    new Class<?>[]{int.class}, id);
            if (v != null && v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
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

    /* ==================== 显示页（AOSP 平面结构，无分类） ====================
     *
     * AOSP display_settings.xml 相对顺序注入；XDF 原生已有
     * 自动调整亮度 + 手动亮度滑条，保留在原位不动。
     */
    private static void restoreDisplayPage(Object fragment, Context ctx) {
        Object screen = getScreen(fragment);
        if (screen == null) {
            XDFHook.logi(TAG, "display page: screen null, skip");
            return;
        }

        addNavPref(ctx, screen, "night_display", "夜间模式", FRAG_NIGHT_DISPLAY, null);
        addNavPref(ctx, screen, "wallpaper", "壁纸", null, intentTo(ACT_WALLPAPER));
        addSwitchPref(ctx, screen, "dark_ui_mode", "深色主题", DarkModeSwitch.INSTANCE, null);
        addScreenTimeoutPref(ctx, screen);
        addSwitchPref(ctx, screen, "auto_rotate", "自动旋转", AutoRotateSwitch.INSTANCE, null);
        // 色彩模式/白平衡：硬件不支持则 controller availability!=0 时不注入
        addNavPrefChecked(ctx, screen, "color_mode", "色彩模式", FRAG_COLOR_MODE, null,
                PKG + ".display.ColorModePreferenceController");
        addSwitchPref(ctx, screen, "display_white_balance", "显示器白平衡",
                DisplayWhiteBalanceSwitch.INSTANCE, CLS_WHITE_BALANCE_CTRL);
        addNavPrefChecked(ctx, screen, "font_size", "字体大小", FRAG_FONT_SIZE, null,
                PKG + ".display.FontSizePreferenceController");
        addNavPrefChecked(ctx, screen, "display_settings_screen_zoom", "显示大小",
                FRAG_SCREEN_ZOOM, null, PKG + ".display.ScreenZoomPreferenceController");
        addNavPrefChecked(ctx, screen, "screensaver", "屏保", FRAG_DREAM, null,
                PKG + ".dream.DreamPreferenceController");
        addNavPref(ctx, screen, "lockscreen_from_display_settings", "锁屏",
                FRAG_LOCKSCREEN, null);

        logKeys("[display]", screen);
        XDFHook.logi(TAG, "display page restored (AOSP order, XDF style)");
    }

    /* ==================== 声音页（AOSP 结构） ==================== */

    private static void restoreSoundPage(Object fragment, Context ctx) {
        Object screen = getScreen(fragment);
        if (screen == null) {
            XDFHook.logi(TAG, "sound page: screen null, skip");
            return;
        }

        // AOSP 音量顺序 media → call → ring → alarm → notification。
        // 本 androidx 版本无 addPreference(int, Preference)，只支持 append：
        // 先把 XDF 原生 notification_volume 移到末尾占位，再按 call,ring,alarm
        // 顺序追加，最后恢复 notification → [media, call, ring, alarm, notification]
        appendMoveToEnd(screen, "notification_volume");
        addVolumePrefAt(ctx, fragment, screen, -1, CLS_CALL_CTRL, "call_volume", "通话音量");
        addVolumePrefAt(ctx, fragment, screen, -1, CLS_RING_CTRL, "ring_volume", "铃声音量");
        addVolumePrefAt(ctx, fragment, screen, -1, CLS_ALARM_CTRL, "alarm_volume", "闹钟音量");
        appendMoveToEnd(screen, "notification_volume");

        // AOSP 媒体输出（controller availability 先判）
        addControllerPref(ctx, screen, "media_output", "媒体输出",
                CLS_MEDIA_OUTPUT_CTRL, null);

        addNavPref(ctx, screen, "zen_mode", "勿扰模式", FRAG_ZEN_MODE, null);
        addNavPref(ctx, screen, "gesture_prevent_ringing_sound", "防止铃声意外",
                FRAG_PREVENT_RINGING, null);

        // 三个铃声（persistent 与 AOSP xml 对齐：ringtone/notification 默认，alarm=false）
        addRingtonePref(ctx, screen, "ringtone", "默认铃声", 1, true);
        addRingtonePref(ctx, screen, "notification_ringtone", "默认通知铃声", 2, true);
        addRingtonePref(ctx, screen, "alarm_ringtone", "默认闹钟铃声", 4, false);

        // AOSP 分类：其他声音和振动
        Object cat = newCategory(ctx, screen, "other_sounds_and_vibrations_category",
                "其他声音和振动");
        if (cat != null) {
            addSwitchPref(ctx, cat, "dial_pad_tones", "拨号键盘音",
                    DialPadSwitch.INSTANCE, null);
            addSwitchPref(ctx, cat, "screen_locking_sounds", "屏幕锁定提示音",
                    ScreenLockSoundSwitch.INSTANCE, null);
            addSwitchPref(ctx, cat, "charging_sounds", "充电提示音",
                    ChargingSoundSwitch.INSTANCE, null);
            addSwitchPref(ctx, cat, "touch_sounds", "触摸时的声音",
                    TouchSoundSwitch.INSTANCE, null);
            addSwitchPref(ctx, cat, "vibrate_on_touch", "触感反馈",
                    HapticFeedbackSwitch.INSTANCE, null);
        }

        // AOSP 响铃时振动（音量组之后）
        addSwitchPref(ctx, screen, "vibrate_when_ringing", "响铃时振动",
                VibrateWhenRingSwitch.INSTANCE, null);

        logKeys("[sound]", screen);
        XDFHook.logi(TAG, "sound page restored (AOSP structure, XDF style)");
    }

    /* ==================== 系统页（tile 自动恢复 + 开发者选项兜底） ==================== */

    private static void restoreSystemPage(Object fragment, Context ctx) {
        Object screen = getScreen(fragment);
        if (screen == null) {
            XDFHook.logi(TAG, "system page: screen null, skip");
            return;
        }
        // 开发者选项：不注入（原生 dashboard tile 在组件 enable 后自动出现，
        // 注入会造成重复；其 fragment 跳转由 SettingsHooks 重定向到 XDFDevelopDevActivity）
        logKeys("[system]", screen);
        XDFHook.logi(TAG, "system page restored (native tiles only)");
    }

    /* ==================== 注入原语（XDF 原生布局风格，全反射） ==================== */

    private static int xdfLayoutRes(Context ctx) {
        int id = ctx.getResources().getIdentifier(LAYOUT_XDF_PREF, "layout", PKG);
        return id != 0 ? id : LAYOUT_XDF_PREF_FALLBACK;
    }

    private static boolean hasPref(Object group, String key) {
        try {
            return Reflect.call(group, "findPreference",
                    new Class<?>[]{CharSequence.class}, key) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 构造 fallback：RingtonePreference 等只有 (Context,AttributeSet,...) 构造器 */
    private static Object newPrefByClass(Context ctx, String cls) throws Exception {
        Class<?> clazz = Reflect.findClass(cls, ctx.getClassLoader());
        Class<?> aset = Reflect.findClass("android.util.AttributeSet",
                ctx.getClassLoader());
        try {
            return Reflect.newInstance(clazz, new Class<?>[]{Context.class}, ctx);
        } catch (NoSuchMethodException e) {
            try {
                return Reflect.newInstance(clazz,
                        new Class<?>[]{Context.class, aset}, ctx, null);
            } catch (NoSuchMethodException e2) {
                try {
                    return Reflect.newInstance(clazz,
                            new Class<?>[]{Context.class, aset, int.class}, ctx, null, 0);
                } catch (NoSuchMethodException e3) {
                    return Reflect.newInstance(clazz,
                            new Class<?>[]{Context.class, aset, int.class, int.class},
                            ctx, null, 0, 0);
                }
            }
        }
    }

    /** 全部 Preference 通用 setter（反射，避免 androidx 编译期引用） */
    private static void pSet(Object pref, String method, Class<?>[] types, Object... args) {
        try {
            Reflect.call(pref, method, types, args);
        } catch (Throwable t) {
            XDFHook.logw(TAG, method + " failed: " + t);
        }
    }

    /**
     * 普通导航条目：XdfToggle 载体（原生大控件布局）+ 隐藏开关 + fragment/intent 跳转。
     * TwoStatePreference.onClick 会先走 Preference.onClick 的 fragment/intent 跳转，
     * 之后的 setChecked 只改临时对象状态，无副作用。
     */
    private static void addNavPref(Context ctx, Object group, String key, String title,
                                   String fragment, Intent intent) {
        try {
            if (hasPref(group, key)) {
                return;
            }
            Object pref = newPrefByClass(ctx, CLS_XDF_TOGGLE);
            sHideSwitchKeys.add(key);
            pSet(pref, "setKey", new Class<?>[]{String.class}, key);
            pSet(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            pSet(pref, "setPersistent", new Class<?>[]{boolean.class}, false);
            if (fragment != null) {
                pSet(pref, "setFragment", new Class<?>[]{String.class}, fragment);
            }
            if (intent != null) {
                pSet(pref, "setIntent", new Class<?>[]{Intent.class}, intent);
            }
            addToGroup(group, pref, -1);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addNavPref(" + key + ")");
        }
    }

    /** 导航条目 + availability 预判（controller 存在且 AVAILABLE(0) 才注入） */
    private static void addNavPrefChecked(Context ctx, Object group, String key, String title,
                                          String fragment, Intent intent, String ctrlCls) {
        try {
            if (!controllerAvailable(ctx, ctrlCls)) {
                XDFHook.logi(TAG, "skip " + key + " (controller unavailable)");
                return;
            }
        } catch (Throwable ignored) {
            // controller 取不到 → 按可用处理（保底显示，点击仍能进 fragment）
        }
        addNavPref(ctx, group, key, title, fragment, intent);
    }

    /** 开关条目：XdfAutoBrightnessTogglePreference 原生大开关同款 */
    private static void addSwitchPref(Context ctx, Object group, String key, String title,
                                      SwitchLogic logic, String controllerCls) {
        try {
            if (hasPref(group, key)) {
                return;
            }
            if (controllerCls != null && !controllerAvailable(ctx, controllerCls)) {
                XDFHook.logi(TAG, "skip switch " + key + " (controller unavailable)");
                return;
            }
            Object pref = newPrefByClass(ctx, CLS_XDF_TOGGLE);
            pSet(pref, "setKey", new Class<?>[]{String.class}, key);
            pSet(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            pSet(pref, "setPersistent", new Class<?>[]{boolean.class}, false);
            pSet(pref, "setChecked", new Class<?>[]{boolean.class}, logic.isOn(ctx));

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
            pSet(pref, "setOnPreferenceChangeListener", new Class<?>[]{iface}, listener);
            addToGroup(group, pref, -1);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addSwitchPref(" + key + ")");
        }
    }

    /** 屏幕超时：ListPreference + XDF 布局，选择写 SCREEN_OFF_TIMEOUT（隐藏开关由集合处理） */
    private static void addScreenTimeoutPref(Context ctx, Object group) {
        try {
            if (hasPref(group, "screen_timeout")) {
                return;
            }
            Object pref = newPrefByClass(ctx, CLS_LIST_PREF);
            sHideSwitchKeys.add("screen_timeout");
            pSet(pref, "setKey", new Class<?>[]{String.class}, "screen_timeout");
            pSet(pref, "setTitle", new Class<?>[]{CharSequence.class}, "屏幕超时");
            pSet(pref, "setPersistent", new Class<?>[]{boolean.class}, false);
            pSet(pref, "setLayoutResource", new Class<?>[]{int.class}, xdfLayoutRes(ctx));

            String[] labels = {"30 秒", "1 分钟", "2 分钟", "5 分钟", "10 分钟",
                    "30 分钟", "永不"};
            String[] values = {"30000", "60000", "120000", "300000", "600000",
                    "1800000", "2147483647"};
            int entRes = ctx.getResources().getIdentifier("screen_timeout_entries",
                    "array", PKG);
            int valRes = ctx.getResources().getIdentifier("screen_timeout_values",
                    "array", PKG);
            CharSequence[] ents = labels;
            CharSequence[] vals = values;
            if (entRes != 0 && valRes != 0) {
                try {
                    ents = ctx.getResources().getTextArray(entRes);
                    vals = ctx.getResources().getStringArray(valRes);
                } catch (Throwable ignored) {
                }
            }
            pSet(pref, "setEntries", new Class<?>[]{CharSequence[].class}, (Object) ents);
            pSet(pref, "setEntryValues", new Class<?>[]{CharSequence[].class}, (Object) vals);

            final CharSequence[] finalVals = vals;
            final String[] finalLabels = labels;
            long cur = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 30000);
            pSet(pref, "setSummary", new Class<?>[]{CharSequence.class},
                    timeoutLabel(finalVals, finalLabels, cur));

            Class<?> iface = Reflect.findClass(IFACE_PREF_LISTENER, ctx.getClassLoader());
            Object listener = Proxy.newProxyInstance(iface.getClassLoader(),
                    new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        if ("onPreferenceChange".equals(method.getName())) {
                            try {
                                int v = Integer.parseInt(String.valueOf(args[1]));
                                Settings.System.putInt(ctx.getContentResolver(),
                                        Settings.System.SCREEN_OFF_TIMEOUT, v);
                                Reflect.call(args[0], "setSummary",
                                        new Class<?>[]{CharSequence.class},
                                        timeoutLabel(finalVals, finalLabels, v));
                            } catch (Throwable t) {
                                XDFHook.loge(t, TAG, "screen_timeout write");
                            }
                            return true;
                        }
                        return null;
                    });
            pSet(pref, "setOnPreferenceChangeListener", new Class<?>[]{iface}, listener);
            addToGroup(group, pref, -1);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addScreenTimeoutPref");
        }
    }

    private static String timeoutLabel(CharSequence[] vals, String[] labels, long v) {
        try {
            for (int i = 0; i < vals.length; i++) {
                if (Long.parseLong(String.valueOf(vals[i])) == v) {
                    return String.valueOf(i < labels.length ? labels[i] : vals[i]);
                }
            }
        } catch (Throwable ignored) {
        }
        return (v >= 2147483647L) ? "永不" : (v / 1000) + " 秒";
    }

    /** 铃声选择：DefaultRingtonePreference 弹窗 + 写对应 System 键（AOSP 原生行为） */
    private static void addRingtonePref(Context ctx, Object group, String key, String title,
                                        int ringtoneType, boolean persistent) {
        try {
            if (hasPref(group, key)) {
                return;
            }
            Object pref = newPrefByClass(ctx, CLS_XDF_RINGTONE);
            sHideSwitchKeys.add(key);
            pSet(pref, "setKey", new Class<?>[]{String.class}, key);
            pSet(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            pSet(pref, "setPersistent", new Class<?>[]{boolean.class}, persistent);
            pSet(pref, "setLayoutResource", new Class<?>[]{int.class}, xdfLayoutRes(ctx));
            pSet(pref, "setRingtoneType", new Class<?>[]{int.class}, ringtoneType);
            addToGroup(group, pref, -1);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addRingtonePref(" + key + ")");
        }
    }

    /** 普通条目 + controller 绑定（先 availability 再注入） */
    private static void addControllerPref(Context ctx, Object group, String key, String title,
                                          String ctrlCls, String dummyIgnored) {
        try {
            if (hasPref(group, key)) {
                return;
            }
            if (!controllerAvailable(ctx, ctrlCls)) {
                XDFHook.logi(TAG, "skip " + key + " (controller unavailable)");
                return;
            }
            Object pref = newPrefByClass(ctx, CLS_XDF_TOGGLE);
            sHideSwitchKeys.add(key);
            pSet(pref, "setKey", new Class<?>[]{String.class}, key);
            pSet(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            pSet(pref, "setPersistent", new Class<?>[]{boolean.class}, false);
            addToGroup(group, pref, -1);

            Class<?> clazz = Reflect.findClass(ctrlCls, ctx.getClassLoader());
            Object ctrl;
            try {
                ctrl = Reflect.newInstance(clazz, new Class<?>[]{Context.class}, ctx);
            } catch (NoSuchMethodException e) {
                ctrl = Reflect.newInstance(clazz,
                        new Class<?>[]{Context.class, String.class}, ctx, key);
            }
            Reflect.call(ctrl, "displayPreference",
                    new Class<?>[]{Reflect.findClass(CLS_PREF_SCREEN, ctx.getClassLoader())},
                    group);
            XDFHook.logi(TAG, "controller bound: " + key + " <- " + ctrlCls);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "controller bind failed (" + key + "), remove bare entry: " + t);
            try {
                Object p = Reflect.call(group, "findPreference",
                        new Class<?>[]{CharSequence.class}, key);
                if (p != null) {
                    Reflect.call(group, "removePreference",
                            new Class<?>[]{Reflect.findClass(CLS_PREF,
                                    group.getClass().getClassLoader())}, p);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** controller availability 预判：getAvailabilityStatus()==0 (AVAILABLE)；异常按可用 */
    private static boolean controllerAvailable(Context ctx, String ctrlCls) {
        try {
            Class<?> clazz = Reflect.findClass(ctrlCls, ctx.getClassLoader());
            Object ctrl;
            try {
                ctrl = Reflect.newInstance(clazz, new Class<?>[]{Context.class}, ctx);
            } catch (NoSuchMethodException e) {
                ctrl = Reflect.newInstance(clazz, new Class<?>[]{Context.class, String.class},
                        ctx, "");
            }
            Object st = Reflect.call(ctrl, "getAvailabilityStatus", null);
            return st == null || Integer.valueOf(0).equals(st);
        } catch (Throwable t) {
            // 该 controller 可能构造签名不同或方法缺失 → 保底可用
            return true;
        }
    }

    /** AOSP 分类标题：图标区关闭，主题 CategoryTitle 样式自动生效 */
    private static Object newCategory(Context ctx, Object screen, String key, String title) {
        try {
            if (hasPref(screen, key)) {
                return Reflect.call(screen, "findPreference",
                        new Class<?>[]{CharSequence.class}, key);
            }
            Object cat = newPrefByClass(ctx, CLS_PREF_CATEGORY);
            pSet(cat, "setKey", new Class<?>[]{String.class}, key);
            pSet(cat, "setTitle", new Class<?>[]{CharSequence.class}, title);
            pSet(cat, "setIconSpaceReserved", new Class<?>[]{boolean.class}, false);
            addToGroup(screen, cat, -1);
            return cat;
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "newCategory(" + key + ")");
            return null;
        }
    }

    /** 音量条目：XdfVolumeSeekBarPerference + 现成 controller，可插入指定位置 */
    private static void addVolumePrefAt(Context ctx, Object fragment, Object screen, int index,
                                        String ctrlCls, String key, String title) {
        try {
            if (hasPref(screen, key)) {
                return;
            }
            Object pref = newPrefByClass(ctx, CLS_XDF_VOLUME_PREF);
            pSet(pref, "setKey", new Class<?>[]{String.class}, key);
            pSet(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
            addToGroup(screen, pref, index);

            Object ctrl = newVolumeController(ctx, ctrlCls, key);
            Reflect.call(ctrl, "displayPreference",
                    new Class<?>[]{Reflect.findClass(CLS_PREF_SCREEN, ctx.getClassLoader())},
                    screen);

            Object lifecycle = Reflect.call(fragment, "getSettingsLifecycle", null);
            Class<?> observer = Reflect.findClass(
                    "androidx.lifecycle.LifecycleObserver", ctx.getClassLoader());
            Reflect.call(lifecycle, "addObserver", new Class<?>[]{observer}, ctrl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "addVolumePrefAt(" + key + ")");
        }
    }

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

    /** 把现有条目移到分组末尾（remove + append），用于 reorder */
    private static void appendMoveToEnd(Object group, String key) {
        try {
            Object pref = Reflect.call(group, "findPreference",
                    new Class<?>[]{CharSequence.class}, key);
            if (pref == null) {
                return;
            }
            Class<?> prefCls = Reflect.findClass(CLS_PREF,
                    group.getClass().getClassLoader());
            Reflect.call(group, "removePreference", new Class<?>[]{prefCls}, pref);
            Reflect.call(group, "addPreference", new Class<?>[]{prefCls}, pref);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "appendMoveToEnd(" + key + ") failed: " + t);
        }
    }

    /** 加入分组；index>=0 按位置插入，失败降级追加 */
    private static void addToGroup(Object group, Object pref, int index) {
        ClassLoader cl = group.getClass().getClassLoader();
        try {
            Class<?> prefCls = Reflect.findClass(CLS_PREF, cl);
            if (index >= 0) {
                Reflect.call(group, "addPreference",
                        new Class<?>[]{int.class, prefCls}, index, pref);
            } else {
                Reflect.call(group, "addPreference", new Class<?>[]{prefCls}, pref);
            }
        } catch (Throwable t) {
            // 显式 findDeclared 重试一次（拿到带参数的真实异常）
            try {
                Class<?> prefCls = Reflect.findClass(CLS_PREF, cl);
                java.lang.reflect.Method m = Reflect.findDeclared(group.getClass(),
                        "addPreference", new Class<?>[]{int.class, prefCls});
                m.setAccessible(true);
                m.invoke(group, index, pref);
                return;
            } catch (Throwable t3) {
                XDFHook.logw(TAG, "indexed addPreference failed: " + t
                        + " | explicit retry: " + t3);
            }
            try {
                Class<?> prefCls = Reflect.findClass(CLS_PREF, cl);
                Reflect.call(group, "addPreference", new Class<?>[]{prefCls}, pref);
            } catch (Throwable t2) {
                XDFHook.loge(t2, TAG, "addToGroup");
            }
        }
    }

    /* ==================== 通用工具 ==================== */

    private static void logKeys(String tag, Object screen) {
        try {
            int count = (int) Reflect.call(screen, "getPreferenceCount", null);
            StringBuilder sb = new StringBuilder(tag + " keys(" + count + "): ");
            for (int i = 0; i < count; i++) {
                Object p = Reflect.call(screen, "getPreference",
                        new Class<?>[]{int.class}, i);
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

    private static Intent intentTo(String activityCls) {
        return new Intent().setComponent(new ComponentName(PKG, activityCls));
    }

    /* ==================== 开关逻辑（与 AOSP 键一致，直接写系统 Settings） ==================== */

    private interface SwitchLogic {
        boolean isOn(Context ctx);

        void setOn(Context ctx, boolean on);
    }

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

    private static final class DisplayWhiteBalanceSwitch implements SwitchLogic {
        static final DisplayWhiteBalanceSwitch INSTANCE = new DisplayWhiteBalanceSwitch();

        @Override
        public boolean isOn(Context ctx) {
            return Settings.Secure.getInt(ctx.getContentResolver(),
                    "display_white_balance_enabled", 0) == 1;
        }

        @Override
        public void setOn(Context ctx, boolean on) {
            Settings.Secure.putInt(ctx.getContentResolver(),
                    "display_white_balance_enabled", on ? 1 : 0);
        }
    }

    private abstract static class SystemIntSwitch implements SwitchLogic {
        final String key;
        final int def;

        SystemIntSwitch(String key, int def) {
            this.key = key;
            this.def = def;
        }

        @Override
        public boolean isOn(Context ctx) {
            return Settings.System.getInt(ctx.getContentResolver(), key, def) == 1;
        }

        @Override
        public void setOn(Context ctx, boolean on) {
            Settings.System.putInt(ctx.getContentResolver(), key, on ? 1 : 0);
        }
    }

    private static final class DialPadSwitch extends SystemIntSwitch {
        static final DialPadSwitch INSTANCE = new DialPadSwitch();

        DialPadSwitch() {
            super("dtmf_tone", 1);
        }
    }

    private static final class ScreenLockSoundSwitch extends SystemIntSwitch {
        static final ScreenLockSoundSwitch INSTANCE = new ScreenLockSoundSwitch();

        ScreenLockSoundSwitch() {
            super("screen_locking_sounds", 1);
        }
    }

    private static final class ChargingSoundSwitch extends SystemIntSwitch {
        static final ChargingSoundSwitch INSTANCE = new ChargingSoundSwitch();

        ChargingSoundSwitch() {
            super("charging_sounds_enabled", 1);
        }
    }

    private static final class TouchSoundSwitch extends SystemIntSwitch {
        static final TouchSoundSwitch INSTANCE = new TouchSoundSwitch();

        TouchSoundSwitch() {
            super("sound_effects_enabled", 1);
        }
    }

    private static final class HapticFeedbackSwitch extends SystemIntSwitch {
        static final HapticFeedbackSwitch INSTANCE = new HapticFeedbackSwitch();

        HapticFeedbackSwitch() {
            super("haptic_feedback_enabled", 1);
        }
    }

    private static final class VibrateWhenRingSwitch extends SystemIntSwitch {
        static final VibrateWhenRingSwitch INSTANCE = new VibrateWhenRingSwitch();

        VibrateWhenRingSwitch() {
            super("vibrate_when_ringing", 0);
        }
    }
}
