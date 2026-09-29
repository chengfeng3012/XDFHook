package cn.cf3012.xdf;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * LockScreenHooks — 恢复 XDF 学习机被阉割的「锁屏方式」选择页（滑动 / PIN / 图案 / 密码）。
 *
 * ===== 背景（MT2 静态对比 AOSP-G_Settings vc29 vs XDF_MtkSettings vc11）=====
 *
 * 设密码的实现层 XDF 一行未改：
 *   - com.android.settings.password 包两边均 103 类，字段/方法数逐个对齐
 *   - ChooseLockGeneric$ChooseLockGenericFragment 26 字段 / 44 方法，完全一致
 *   - ChooseLockSettingsHelper / ChooseLockGenericController / SecurityFeatureProvider 均原生
 *   - framework 侧 LockPatternUtils.hasSecureLockScreen()、LockSettingsShellCommand 与 AOSP 逐字一致
 *
 * XDF 的阉割只有两处，都在 ChooseLockGenericFragment 内：
 *
 *   1) addPreferences()          —— 数据源被换
 *      AOSP: addPreferencesFromResource(R.xml.security_settings_picker)   // 8 项齐全
 *      XDF : addPreferencesFromResource(0x7f1500a7)                      // 另一份裁剪过的 xml
 *      → 原生的 security_settings_picker.xml 在 XDF 包里仍存在，但本页根本不用它。
 *
 *   2) updatePreferencesOrFinish() —— 出口被逐项掐断
 *      该方法内 removePreference 出现次数：AOSP 1 次 vs XDF 5 次（line 552~569 连续追加）。
 *      XDF 在原有那次之外，追加 4 次 removePreference(<ScreenLockType>.preferenceKey)，
 *      逐个摘掉 PATTERN / PIN / PASSWORD / SWIPE，只留 NONE（"无"）。
 *      SettingsPreferenceFragment.removePreference(String) 直接
 *      getPreferenceScreen().removePreference(findPreference(key)) —— 整项消失，不是禁用。
 *
 * 关键：这两处都发生在 disableUnusablePreferences() 之后，所以原生那套
 * "按设备能力决定可见性" 的逻辑（config_hide_none_security_option /
 * config_hide_swipe_security_option 均为 false、hasSecureLockScreen() 读
 * android.software.secure_lock_screen = true）根本没机会起作用。
 *
 * 现象：ChooseLockGeneric 界面上只剩"无"一项，滑动/PIN/图案/密码全部消失。
 *
 * ===== 为什么还要注入入口 =====
 *
 * 光恢复页面没有意义：XDF 把左栏「锁屏」项劫持到自造的
 * com.android.settings.lockscreen.XdfLockScreenFragment（manifest 无 SecuritySettingsActivity，
 * 左栏也没有"安全和隐私"），该页是**纯自定义 View**（非 PreferenceFragment），
 * 整页只有 fragment_xdf_lock_screen.xml 的两行：自动锁屏 / 锁屏来通知时亮屏。
 *
 * 因此本模块在该页【克隆 ROM 自己那行"自动锁屏"】改造成「屏幕锁定」条目（见
 * hookLockPageEntry）：不手搓控件，样式（18sp / #FF1E1E1E 标题、24dp·20dp padding、
 * 16sp #FF666666 状态、右侧箭头 @7F0F001F）天然与原生逐像素一致。
 *
 * ===== hook 清单 =====
 *   1) ChooseLockGenericFragment.addPreferences()   after → 换回原生 security_settings_picker
 *   2) SettingsPreferenceFragment.removePreference(String)Z before → 拦掉 XDF 的逐项摘除
 *   3) ChooseLockGenericController.isScreenLockVisible(ScreenLockType)Z after → 兜底恒 true
 *   4) XdfLockScreenFragment.onViewCreated / onResume → 注入「屏幕锁定」入口行
 *
 * 只做 UI 恢复，不碰任何锁屏凭据状态，不调 KeyguardManagerService，
 * 不在 system_server 中运行（scope 仅 com.android.settings）。
 * 开关：AppConfig SCOPE_SETTINGS / H_LOCK_UNLOCK（运行期实时生效，关掉即回落原生行为）。
 */
final class LockScreenHooks {

    private static final String TAG = "settings.lock";

    private static final String PKG = XDFHook.PKG_SETTINGS;

    /* ---- 宿主类（全部反射引用，本模块 compileOnly 无 Settings 依赖） ---- */

    /** 锁屏方式选择页真正的 fragment（外层 ChooseLockGeneric 是 SettingsActivity） */
    private static final String CLS_FRAGMENT =
            PKG + ".password.ChooseLockGeneric$ChooseLockGenericFragment";
    /** 外层 Activity（路由兜底：直接起它，新开窗口） */
    private static final String CLS_ACTIVITY_CHOOSE_LOCK = PKG + ".password.ChooseLockGeneric";
    private static final String CLS_CONTROLLER = PKG + ".password.ChooseLockGenericController";
    private static final String CLS_SCREEN_LOCK_TYPE = PKG + ".password.ScreenLockType";
    /** removePreference 的实现处：所有设置页的公共基类 */
    private static final String CLS_SETTINGS_PREF_FRAGMENT = PKG + ".SettingsPreferenceFragment";
    /** XDF 左栏「锁屏」项承载的自造页（纯 View） */
    private static final String CLS_XDF_LOCK_PAGE = PKG + ".lockscreen.XdfLockScreenFragment";

    /**
     * XDF 逐项移除时用到的 preference key（AOSP 原生 xml 中的定义）。
     * hook ② 据此判断"是 XDF 的非法移除"还是"AOSP 的合法移除"。
     */
    private static final String[] RESTORE_KEYS = {
            "unlock_set_pattern",   // ScreenLockType.PATTERN
            "unlock_set_pin",       // ScreenLockType.PIN
            "unlock_set_password",  // ScreenLockType.PASSWORD
            "unlock_set_off",       // ScreenLockType.SWIPE（preferenceKey = unlock_set_off）
            "unlock_set_none",      // 兼容：部分分支用 NONE
    };

    /* ---- 入口行 ---- */

    /** 被克隆的 XDF 锁屏页布局（按名取 id，规避 AOSP/XDF 资源 id 位移） */
    private static final String LAYOUT_XDF_LOCK_PAGE = "fragment_xdf_lock_screen";
    private static final int LAYOUT_XDF_LOCK_PAGE_FALLBACK = 0x7f0d00cf;
    /** 注入行的 tag（幂等判定 + onResume 刷新状态） */
    private static final String TAG_ROW = "xdfhook_lock_method_row";
    private static final String TITLE_LOCK_METHOD = "屏幕锁定";
    private static final String STATUS_SET = "已设置";
    private static final String STATUS_UNSET = "未设置";

    private LockScreenHooks() {
    }

    /* ==================== 入口 ==================== */

    /** 入口：在 com.android.settings 进程安装全部 hook（由 XDFHook 按开关分发） */
    static void hookAll(ClassLoader cl) {
        try {
            hookAddPreferences(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookAddPreferences");
        }
        try {
            hookRemovePreference(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookRemovePreference");
        }
        try {
            hookVisibleFallback(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookVisibleFallback");
        }
        try {
            hookLockPageEntry(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookLockPageEntry");
        }
        XDFHook.logi(TAG, "lock screen hooks installed"
                + " (xml restore + remove block + visibility + page entry)");
    }

    /** 运行期开关（per-scope hook 单元，改动实时生效） */
    private static boolean enabled() {
        return AppConfig.get().hookEnabled(AppConfig.SCOPE_SETTINGS, AppConfig.H_LOCK_UNLOCK);
    }

    /* ==================== hook ①：换回原生 xml ==================== */

    /**
     * addPreferences() 是 protected 无参。after 阶段把 PreferenceScreen 清空并
     * 重新 inflate 原生 security_settings_picker，再复刻 viewId 设置。
     */
    private static void hookAddPreferences(ClassLoader cl) throws Exception {
        XDFHook.hookMethod(cl, CLS_FRAGMENT, "addPreferences", new Class<?>[0], chain -> {
            try {
                // 先让 XDF 原逻辑跑完（inflate 它那份裁剪 xml）
                chain.proceed();
                if (enabled()) {
                    rebuildWithNativeXml(chain.getThisObject(), cl);
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "addPreferences.after");
            }
            return null; // void 方法
        });
        XDFHook.logi(TAG, "addPreferences.after restore armed");
    }

    /**
     * 在 fragment 上重建原生选项列表。
     *
     * 不依赖硬编码资源 id：用 R$xml 按名反查 security_settings_picker，
     * 规避 XDF 与 AOSP 的资源 id 位移（对比中同类条目 id 普遍差 0x4x）。
     */
    private static void rebuildWithNativeXml(Object frag, ClassLoader cl) throws Exception {
        int xmlId = 0;
        try {
            Class<?> resIdCls = Reflect.findClass(PKG + ".R$xml", cl);
            Field f = Reflect.findField(resIdCls, "security_settings_picker");
            xmlId = f.getInt(null);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "resolve security_settings_picker");
            return;
        }
        if (xmlId == 0) {
            XDFHook.logi(TAG, "security_settings_picker not found in R$xml, skip");
            return;
        }
        XDFHook.logd(TAG, "native security_settings_picker id = 0x" + Integer.toHexString(xmlId));

        Object screen = Reflect.call(frag, "getPreferenceScreen", new Class<?>[0]);
        if (screen == null) {
            XDFHook.logi(TAG, "getPreferenceScreen() == null, skip");
            return;
        }

        // 清空 XDF 那份裁剪 xml 已经 inflate 出来的错项
        Reflect.call(screen, "removeAll", new Class<?>[0]);
        // 重新 inflate 原生 xml
        Reflect.call(frag, "addPreferencesFromResource", new Class<?>[]{int.class}, xmlId);

        // 复刻原 addPreferences() 的 viewId 设置，否则条目排版异常。
        // 原方法：NONE / skip_fingerprint / skip_face → viewA；PIN → viewB，PASSWORD → viewC
        applyViewIds(frag, "unlock_set_off", "unlock_skip_fingerprint", "unlock_skip_face");
        applyViewIds(frag, "unlock_set_pin");
        applyViewIds(frag, "unlock_set_password");

        XDFHook.logi(TAG, "rebuildWithNativeXml done: lock options restored");
    }

    /** 给指定 key 的 Preference 设置 viewId（复刻原生布局分配）；取不到就跳过 */
    private static void applyViewIds(Object frag, String... keys) {
        for (String key : keys) {
            try {
                Object pref = Reflect.call(frag, "findPreference",
                        new Class<?>[]{CharSequence.class}, key);
                if (pref == null) {
                    continue;
                }
                int viewId = resolveAnyLayoutId(frag);
                if (viewId == 0) {
                    continue;
                }
                Reflect.call(pref, "setViewId", new Class<?>[]{int.class}, viewId);
            } catch (Throwable ignored) {
                // 单项失败不影响其余（只影响排版，不影响可见性）
            }
        }
    }

    /** 取一个 layout 资源 id 作为 viewId 替身：复用已存在 Preference 的 viewId */
    private static int resolveAnyLayoutId(Object frag) {
        for (String key : new String[]{"unlock_set_off", "unlock_set_none"}) {
            try {
                Object pref = Reflect.call(frag, "findPreference",
                        new Class<?>[]{CharSequence.class}, key);
                if (pref == null) {
                    continue;
                }
                int id = (Integer) Reflect.call(pref, "getViewId", new Class<?>[0]);
                if (id != 0) {
                    return id;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /* ==================== hook ②：拦掉 XDF 的逐项移除 ==================== */

    /**
     * SettingsPreferenceFragment.removePreference(String)Z —— before 拦截。
     * key 命中 RESTORE_KEYS 时直接返回 true 跳过，其余调用一律原样 proceed，
     * 不影响 AOSP 自身的合法移除（如换锁时先摘旧方式）。
     *
     * 挂在 SettingsPreferenceFragment（最基类）而非 fragment 本身，
     * 因为 removePreference 的实现位于前者，且是所有设置页的公共出口。
     */
    private static void hookRemovePreference(ClassLoader cl) throws Exception {
        XDFHook.safeHook(cl, CLS_SETTINGS_PREF_FRAGMENT, "removePreference",
                new Class<?>[]{String.class},
                chain -> {
                    try {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        Object keyObj = chain.getArg(0);
                        if (keyObj instanceof String) {
                            String key = (String) keyObj;
                            for (String k : RESTORE_KEYS) {
                                if (k.equals(key)) {
                                    XDFHook.logd(TAG, "block XDF removePreference(\"" + key + "\")");
                                    return Boolean.TRUE; // 跳过方法体，视为已移除
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "removePreference.before");
                    }
                    return chain.proceed();
                },
                "lockScreen.blockRemovePreference");
    }

    /* ==================== hook ③：兜底强制可见性 ==================== */

    /**
     * ChooseLockGenericController.isScreenLockVisible(ScreenLockType)Z —— after 强制 true。
     *
     * 原生返回 false 的两种情况：
     *   - PIN/PATTERN/PASSWORD → LockPatternUtils.hasSecureLockScreen()，读系统特性
     *     android.software.secure_lock_screen（本机实测存在，但不同设备可能缺）
     *   - SWIPE/NONE → config_hide_*_security_option 布尔 或 mUserId != myUserId
     * 一旦为 false，原生 disableUnusablePreferencesImpl 会再次 removePreference。
     */
    private static void hookVisibleFallback(ClassLoader cl) throws Exception {
        final Class<?> typeCls = Reflect.findClass(CLS_SCREEN_LOCK_TYPE, cl);
        XDFHook.safeHook(cl, CLS_CONTROLLER, "isScreenLockVisible",
                new Class<?>[]{typeCls},
                chain -> {
                    try {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        Object result = chain.proceed();
                        if (!Boolean.TRUE.equals(result)) {
                            XDFHook.logd(TAG, "force isScreenLockVisible("
                                    + chain.getArg(0) + ") = true");
                            return Boolean.TRUE;
                        }
                        return result;
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "isScreenLockVisible.after");
                        return chain.proceed();
                    }
                },
                "lockScreen.forceVisible");
    }

    /* ==================== hook ④：左栏「锁屏」页注入入口 ====================
     *
     * XdfLockScreenFragment 是纯 androidx Fragment（非 PreferenceFragment），
     * 视图来自 fragment_xdf_lock_screen.xml：
     *   行1 = LinearLayout[0x7f0a054b]：18sp/#FF1E1E1E「自动锁屏」+ 16sp/#FF666666 状态
     *        + 右箭头 ImageView(@7F0F001F)
     *   行2 = LinearLayout：「锁屏来通知时亮屏」+ 开关 ImageView(@7F08035A)
     *
     * 做法：再次 inflate 同一布局，取【行1】克隆一份改造成「屏幕锁定」。
     * 好处：不手搓任何控件，padding/字号/颜色/箭头图与原生逐像素一致；
     *       且行1 本身就是"标题 + 状态 + 右箭头"的导航型行，语义完全匹配。
     * 插入位置：index 0（本页最核心操作，与左栏选中项同层级）。
     * 幂等：tag 判定，重复 onViewCreated 不会重复注入。
     */

    private static void hookLockPageEntry(ClassLoader cl) throws Exception {
        // ④-1 注入：onViewCreated 之后视图树已就绪（XDF 原实现的点击监听已绑好）
        XDFHook.hookMethod(cl, CLS_XDF_LOCK_PAGE, "onViewCreated",
                new Class<?>[]{View.class, Bundle.class},
                chain -> {
                    try {
                        chain.proceed();
                        if (enabled()) {
                            Object frag = chain.getThisObject();
                            Object root = Reflect.getField(frag, "mRootView");
                            if (root instanceof ViewGroup) {
                                injectEntry((ViewGroup) root, frag, cl);
                            }
                        }
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "lockPageEntry.onViewCreated");
                    }
                    return null; // void
                });
        // ④-2 状态刷新：改完密码返回本页时更新"已设置/未设置"（onViewCreated 不会重跑）
        XDFHook.hookMethod(cl, CLS_XDF_LOCK_PAGE, "onResume", new Class<?>[0],
                chain -> {
                    try {
                        Object r = chain.proceed();
                        if (enabled()) {
                            Object root = Reflect.getField(chain.getThisObject(), "mRootView");
                            if (root instanceof ViewGroup) {
                                refreshStatus((ViewGroup) root);
                            }
                        }
                        return r;
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "lockPageEntry.onResume");
                        return chain.proceed();
                    }
                });
        XDFHook.logi(TAG, "lock page entry armed (clones ROM row for 屏幕锁定)");
    }

    /** 在 XDF 锁屏页根布局首位插入「屏幕锁定」行（幂等） */
    private static void injectEntry(ViewGroup root, final Object frag, final ClassLoader cl) {
        if (root.findViewWithTag(TAG_ROW) != null) {
            return;
        }
        Context ctx = root.getContext();
        if (ctx == null) {
            return;
        }
        try {
            int layoutId = resolveLayoutId(ctx);
            if (layoutId == 0) {
                XDFHook.logi(TAG, "layout " + LAYOUT_XDF_LOCK_PAGE + " not found, entry skipped");
                return;
            }
            View inflated = LayoutInflater.from(ctx).inflate(layoutId, root, false);
            if (!(inflated instanceof ViewGroup) || ((ViewGroup) inflated).getChildCount() < 1) {
                return;
            }
            // 行1 = ROM 原生的"自动锁屏"行（标题 + 状态 + 右箭头）
            ViewGroup holder = (ViewGroup) inflated;
            ViewGroup row = (ViewGroup) holder.getChildAt(0);
            // 必须先脱离临时容器：否则 addView 抛 IllegalStateException
            // （「The specified child already has a parent」，实测踩过）
            holder.removeView(row);
            // 清掉克隆体的 id：与原行 id 重复会在同一棵视图树里造成 findViewById 歧义
            clearIds(row);
            // 标题 → 屏幕锁定；状态 → 当前是否已设密码
            TextView title = childAt(row, 0, TextView.class);
            if (title != null) {
                title.setText(TITLE_LOCK_METHOD);
            }
            TextView status = childAt(row, 1, TextView.class);
            if (status != null) {
                status.setText(lockStatusText(ctx));
            }
            row.setTag(TAG_ROW);
            row.setOnClickListener(v -> openChooseLockPage(frag, cl));
            // removeView 后 LayoutParams 仍在；为极端情况留兜底（克隆自 XDF 布局，
            // 本身就是 match_parent/wrap_content，与所在 LinearLayout 完全一致）
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp == null) {
                lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            root.addView(row, 0, lp);
            XDFHook.logi(TAG, "「" + TITLE_LOCK_METHOD + "」entry injected into XDF lock page");
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "injectEntry");
        }
    }

    /** onResume 刷新"已设置/未设置"（改动密码后返回本页，条目仍不重建） */
    private static void refreshStatus(ViewGroup root) {
        try {
            View row = root.findViewWithTag(TAG_ROW);
            if (!(row instanceof ViewGroup)) {
                return;
            }
            TextView status = childAt((ViewGroup) row, 1, TextView.class);
            if (status != null) {
                status.setText(lockStatusText(root.getContext()));
            }
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "refreshStatus");
        }
    }

    /** 当前锁屏状态：isKeyguardSecure() 对滑动（无密码）也返回 false，故用它做"已设置"判定 */
    private static String lockStatusText(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return (km != null && km.isKeyguardSecure()) ? STATUS_SET : STATUS_UNSET;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 打开原生「锁屏方式」页。
     *
     * 主路径：与「更多设置」非 Dashboard 条目一致——直接 replace 到 XDF 主窗体右栏
     * 容器（SettingsHooks.replaceRightFragment，0x7f0a029f），左栏保持「锁屏」高亮、
     * 返回栈可回退，不新开窗口。
     *
     * 兜底：宿主非 SettingsActivity / 反射失败时，直接起 ChooseLockGeneric
     * （XDF manifest 已声明，exported=false，同 uid 进程内可起，实测可进）。
     */
    private static void openChooseLockPage(Object frag, ClassLoader cl) {
        if (!enabled()) {
            return;
        }
        try {
            Context act = (Context) Reflect.call(frag, "getActivity", new Class<?>[0]);
            if (act == null) {
                return;
            }
            // 标题栏：XDF 各页统一走 SettingsActivity.updateTitleBar
            try {
                Reflect.call(act, "updateTitleBar",
                        new Class<?>[]{CharSequence.class}, TITLE_LOCK_METHOD);
            } catch (Throwable ignored) {
            }
            Bundle args = new Bundle();
            try {
                SettingsHooks.replaceRightFragment(act, CLS_FRAGMENT, args, cl);
                XDFHook.logi(TAG, "lock page -> in-place: " + CLS_FRAGMENT);
                return;
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "in-place route failed, fallback to new window");
            }
            Intent i = new Intent().setClassName(PKG, CLS_ACTIVITY_CHOOSE_LOCK);
            if (act instanceof android.app.Activity) {
                ((android.app.Activity) act).startActivity(i);
                XDFHook.logi(TAG, "lock page -> new window: " + CLS_ACTIVITY_CHOOSE_LOCK);
            }
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "openChooseLockPage");
        }
    }

    /* ==================== 小工具 ==================== */

    /** 按名取 XDF 锁屏页布局 id，取不到回退常量 */
    private static int resolveLayoutId(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier(
                    LAYOUT_XDF_LOCK_PAGE, "layout", PKG);
            if (id != 0) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        return LAYOUT_XDF_LOCK_PAGE_FALLBACK;
    }

    /** 按下标取指定类型的子 View（克隆体内结构固定，比 findViewById 更抗 id 变动） */
    private static <T> T childAt(ViewGroup parent, int index, Class<T> type) {
        if (parent == null || index >= parent.getChildCount()) {
            return null;
        }
        View v = parent.getChildAt(index);
        return type.isInstance(v) ? type.cast(v) : null;
    }

    /** 递归清空克隆体的 id（避免与原行 id 重复） */
    private static void clearIds(View v) {
        if (v == null) {
            return;
        }
        v.setId(View.NO_ID);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                clearIds(g.getChildAt(i));
            }
        }
    }
}
