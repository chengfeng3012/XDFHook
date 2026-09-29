package cn.cf3012.xdf;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ResolverAlwaysRestore — 恢复 ResolverActivity 被 ROM 阉割的
 * 「始终」按钮 + 修复「仅此一次」按钮空转。
 *
 * 【ROM 阉割的静态事实】（framework.apk 实测，对照 AOSP-G 10）
 *  1. ResolverActivity 删除了字段 mAlwaysButton，resetButtonBar() 里
 *     不再 findViewById(button_always)，setAlwaysButtonEnabled() 被掏空。
 *  2. onButtonClick(View) 被截断：算完 which / resolveInfoForPosition
 *     后直接 return-void，【删除了 startSelected 调用】——这是
 *     「仅此一次」按钮点了没反应的根因（XML 里 android:onClick=
 *     "onButtonClick" 仍生效，点击进得来，只是里面什么都不做）。
 *  3. 但支撑全在：startSelected(IZZ)V / showSettingsForSelected(ResolveInfo)
 *     完整未被改动；资源表里 button_always、activity_resolver_use_always
 *     ("Always")、activity_resolver_set_always 全部健在。
 *  4. 车机布局 res/layout-car-v8/car_resolver_list.xml 两个按钮都还在
 *     （button_once 在前、button_always 在后，onClick 同为 onButtonClick），
 *     证明 ROM 只是没在手机布局里放。
 *
 * 【本类做法】（全部反射，不改 framework 签名）
 *  H1 onButtonClick before：完全接管，按 AOSP 原版语义重算并分派
 *     —— 「始终」+ 网页类布局 -> showSettingsForSelected(ri)
 *     —— 否则 startSelected(which, always, hasIndexBeenFiltered)
 *     一次补齐「仅此一次」和「始终」两个出口。
 *  H2 动态插入「始终」按钮：在 7 参 onCreate 之后（configureContentView
 *     已 setContentView + resetButtonBar）往 button_bar 插一个与
 *     button_once 同风格的 Button，并复制 button_once 的启用态。
 *
 * 【MT2 实测补充（XDF_framework.apk, android vc29）】
 *  - mAlwaysButton 字段确实不存在；resetButtonBar() 只 findViewById
 *    button_bar(0x010201EB) + button_once(0x010201EC)。
 *  - setAlwaysButtonEnabled(ZIZ)V 存在但【零调用者】= 死代码，
 *    其 smali 里算出的 enabled 从未被使用（ROM 阉割痕迹）。
 *  - resetAlwaysOrOnceButtonBar()V 只被 resetButtonBar() 调用一次，
 *    故 always 按钮的 enabled 必须在【插入那一刻】从 button_once 复制。
 *  - 资源健在：button_always=0x010201EA、
 *    activity_resolver_use_always=0x0104005F("Always")。
 *
 * 注：刻意不碰 mSupportsAlwaysUseOption —— 它同时控制
 * onPrepareAdapterView 里的 setChoiceMode，改它会连带把列表点击
 * 语义从「直接启动」切成「勾选」。
 */
final class ResolverAlwaysRestore {

    private static final String TAG = "resolver";

    /** 同一进程内防重复注册 */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 资源名 —— 按名字解析，规避 framework-res 各版本 id 漂移
     * （XDF 0x10201ea vs AOSP-G 0x10201eb，务必不要硬编码）。
     */
    private static final String ID_BUTTON_ALWAYS = "button_always";
    private static final String ID_BUTTON_ONCE = "button_once";
    private static final String ID_BUTTON_BAR = "button_bar";
    private static final String STR_USE_ALWAYS = "activity_resolver_use_always";
    private static final String STR_SET_ALWAYS = "activity_resolver_set_always";

    private ResolverAlwaysRestore() {
    }

    static void hookAll(ClassLoader cl) {
        if (!sHooked.add(cl)) {
            return;
        }
        try {
            hookOnButtonClick(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "onButtonClick dispatch");
        }
        try {
            hookInsertAlwaysButton(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "insert always button");
        }
    }

    /* ==================== H1: onButtonClick 完全接管 ==================== */

    /**
     * 恢复 AOSP 原版 onButtonClick 语义：
     *   int which = hasFilteredItem ? getFilteredPosition()
     *                               : mAdapterView.getCheckedItemPosition();
     *   boolean hasIndexBeenFiltered = !hasFilteredItem;
     *   ResolveInfo ri = resolveInfoForPosition(which, hasIndexBeenFiltered);
     *   if (mUseLayoutForBrowsables && !ri.handleAllWebDataURI
     *           && id == R.id.button_always) {
     *       showSettingsForSelected(ri);      // 网页类：跳浏览器默认设置
     *       return;
     *   }
     *   boolean always = (id == R.id.button_always);
     *   startSelected(which, always, hasIndexBeenFiltered);
     *
     * ROM 把后半段全删了。before 完全接管重建；失败回退 proceed()
     * （= ROM 当前空转行为，不会引入更糟的故障）。
     */
    private static void hookOnButtonClick(ClassLoader cl) throws Exception {
        Class<?> resolver = Reflect.findClass("com.android.internal.app.ResolverActivity", cl);
        Method m = Reflect.findDeclared(resolver, "onButtonClick", new Class<?>[]{View.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            try {
                Object vObj = chain.getArg(0);
                Object thiz = chain.getThisObject();
                if (!(vObj instanceof View) || thiz == null) {
                    return chain.proceed();
                }
                View v = (View) vObj;
                // thiz 是 ResolverActivity（Activity/Context），【不是 View】，
                // 不能强转成 View 取资源，必须按 Context 走 getResources()。
                if (!(thiz instanceof android.app.Activity)) {
                    return chain.proceed();
                }
                android.app.Activity act = (android.app.Activity) thiz;
                int idAlways = resId(act, ID_BUTTON_ALWAYS, "id");
                boolean isAlways = (idAlways != 0 && v.getId() == idAlways);

                Object adapter = Reflect.getField(thiz, "mAdapter");
                if (adapter == null) {
                    return chain.proceed();
                }

                boolean hasFilteredItem =
                        Boolean.TRUE.equals(Reflect.call(adapter, "hasFilteredItem", null));
                int which;
                if (hasFilteredItem) {
                    Object p = Reflect.call(adapter, "getFilteredPosition", null);
                    which = (p instanceof Number) ? ((Number) p).intValue() : -1;
                } else {
                    Object av = Reflect.getField(thiz, "mAdapterView");
                    which = (av instanceof AbsListView)
                            ? ((AbsListView) av).getCheckedItemPosition() : -1;
                }
                // AOSP：hasIndexBeenFiltered = !hasFilteredItem
                boolean hasIndexBeenFiltered = !hasFilteredItem;

                if (which < 0) {
                    XDFHook.logd(TAG, "onButtonClick: no selection (which=" + which + ")");
                    return null;    // 无选中项，AOSP 亦不会启动
                }

                Object riObj = Reflect.call(adapter, "resolveInfoForPosition",
                        new Class<?>[]{int.class, boolean.class},
                        which, hasIndexBeenFiltered);
                if (!(riObj instanceof ResolveInfo)) {
                    XDFHook.logd(TAG, "onButtonClick: ri null at pos " + which);
                    return null;
                }
                ResolveInfo ri = (ResolveInfo) riObj;

                // 网页类 + 布局模式 + 点「始终」-> 跳浏览器默认设置（AOSP 原版分支）
                Object useLayoutObj = Reflect.getField(thiz, "mUseLayoutForBrowsables");
                boolean useLayout = Boolean.TRUE.equals(useLayoutObj);
                if (useLayout && isAlways && !handleAllWebDataUri(ri)) {
                    XDFHook.logi(TAG, "onButtonClick: always+browsable -> showSettingsForSelected");
                    Reflect.call(thiz, "showSettingsForSelected",
                            new Class<?>[]{ResolveInfo.class}, ri);
                    return null;
                }

                Reflect.call(thiz, "startSelected",
                        new Class<?>[]{int.class, boolean.class, boolean.class},
                        which, isAlways, hasIndexBeenFiltered);
                XDFHook.logi(TAG, "onButtonClick dispatched: pos=" + which
                        + " always=" + isAlways + " filtered=" + hasIndexBeenFiltered);
                return null;        // 完全接管
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "onButtonClick restore");
                return chain.proceed();     // 回退 ROM 空转
            }
        });
        XDFHook.logi(TAG, "hooked: ResolverActivity.onButtonClick (once+always dispatch)");
    }

    /* ==================== H2: 动态插入「始终」按钮 ==================== */

    /**
     * 钩 7 参 onCreate(Bundle,Intent,CharSequence,int,Intent[],List,boolean) 之后，
     * 往 button_bar 插入「始终」按钮。configureContentView 在其内部已
     * setContentView + resetButtonBar，故此刻 view 树已就绪，
     * 且 mOnceButton 的 enabled 态已被 ROM 的 resetAlwaysOrOnceButtonBar 决定。
     */
    private static void hookInsertAlwaysButton(ClassLoader cl) throws Exception {
        Class<?> resolver = Reflect.findClass("com.android.internal.app.ResolverActivity", cl);
        Method m = Reflect.findDeclared(resolver, "onCreate", new Class<?>[]{
                android.os.Bundle.class, Intent.class, CharSequence.class, int.class,
                Intent[].class, java.util.List.class, boolean.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            Object r = chain.proceed();
            try {
                Object thiz = chain.getThisObject();
                // thiz 是 Activity（Context），不是 View —— 用 Activity.findViewById
                if (thiz instanceof android.app.Activity) {
                    insertAlwaysButton((android.app.Activity) thiz);
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "insert always button");
            }
            return r;
        });
        XDFHook.logi(TAG, "hooked: ResolverActivity.onCreate (insert always button)");
    }

    /**
     * 在 button_bar 中插入「始终」按钮。幂等：已存在则跳过。
     * 复用 button_once 的 LayoutParams，保证与「仅此一次」视觉一致。
     *
     * 【启用态】不能写死 false：XDF 的 setAlwaysButtonEnabled(ZIZ)V 虽存在，
     * 但 MT2 xref 实测【零调用者】（死代码），而 resetAlwaysOrOnceButtonBar()
     * 只在 resetButtonBar() 里被调一次 —— 即 configureContentView 期间，
     * 早于本方法。故没有任何后续流程会再改 always 按钮的 enabled。
     * 手机布局 resolver_list.xml 中 button_once 本身就带
     * android:enabled="false"，由 ROM 在开屏时按
     * (useLayoutWithDefault && filteredPos!=-1) || (adapterView.checked!=-1)
     * 打开。此刻直接【复制 button_once 的 enabled 态】，
     * 即精确等价于 AOSP 的 hasValidSelection 语义。
     */
    private static void insertAlwaysButton(android.app.Activity act) {
        // 分享面板 ChooserActivity 继承 ResolverActivity，但用的是 chooser 布局，
        // 不该出现 always/once 按钮条；类名命中则跳过。
        if (act.getClass().getName().contains("ChooserActivity")) {
            return;
        }
        int barId = resId(act, ID_BUTTON_BAR, "id");
        if (barId == 0) {
            XDFHook.logd(TAG, "insert: button_bar id not resolved");
            return;
        }
        View barView = act.findViewById(barId);
        if (!(barView instanceof LinearLayout)) {
            XDFHook.logd(TAG, "insert: button_bar not a LinearLayout");
            return;
        }
        LinearLayout bar = (LinearLayout) barView;

        int alwaysId = resId(act, ID_BUTTON_ALWAYS, "id");
        if (alwaysId == 0) {
            XDFHook.logw(TAG, "insert: button_always id not found in resources");
            return;
        }
        if (bar.findViewById(alwaysId) != null) {
            return;     // 幂等
        }

        // 先取 button_once：布局参数 + 启用态都以它为基准
        int onceId = resId(act, ID_BUTTON_ONCE, "id");
        View once = onceId != 0 ? bar.findViewById(onceId) : null;

        Button btn = new Button(act);
        btn.setId(alwaysId);
        int textId = resId(act, STR_USE_ALWAYS, "string");
        if (textId == 0) {
            textId = resId(act, STR_SET_ALWAYS, "string");
        }
        if (textId != 0) {
            btn.setText(textId);
        }
        // 关键：跟随 button_once 当前启用态（= ROM 刚判定的 hasSelection）
        boolean enabled = (once != null) ? once.isEnabled() : true;
        btn.setEnabled(enabled);

        // 复制 button_once 的布局参数（间距/minHeight/gravity），但不共用同一实例
        ViewGroup.LayoutParams srcLp = (once != null) ? once.getLayoutParams() : null;
        if (srcLp != null) {
            bar.addView(btn, new ViewGroup.LayoutParams(srcLp));
        } else {
            bar.addView(btn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 点击 -> 走 onButtonClick 的同一套分派语义
        btn.setOnClickListener(v -> {
            try {
                dispatchOnButtonClick(act, v);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "always button click");
            }
        });
        XDFHook.logi(TAG, "insert: always button added (id=" + alwaysId
                + ", enabled=" + enabled + ")");
    }

    /* ==================== always 按钮点击复用分派 ==================== */

    /**
     * always 按钮 onClick 复用 onButtonClick 分派。
     * 注意：这里直接反射调用【原始】onButtonClick 会被 H1 再次拦截，
     * 属于预期——H1 会识别 isAlways=true 并走 startSelected(always=true)
     * 或 showSettingsForSelected 分支。
     */
    private static void dispatchOnButtonClick(Object thiz, View v) throws Exception {
        Method m = Reflect.findDeclared(thiz.getClass(), "onButtonClick",
                new Class<?>[]{View.class});
        m.setAccessible(true);
        m.invoke(thiz, v);
    }

    /**
     * 读 ResolveInfo.handleAllWebDataURI（@hide，SDK android.jar 无此符号）。
     * 读取失败按 false 处理（=走普通 startSelected 路径，不会误跳设置页）。
     */
    private static boolean handleAllWebDataUri(ResolveInfo ri) {
        try {
            Field f = Reflect.findField(ri.getClass(), "handleAllWebDataURI");
            f.setAccessible(true);
            Object v = f.get(ri);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 工具 ==================== */

    /**
     * 按资源名解析 id，规避 framework-res 版本漂移；失败返回 0。
     * 入参用 Context 而非 View：ResolverActivity 的 receiver 是 Activity（Context），
     * 绝不能强转 View。
     */
    private static int resId(Context ctx, String name, String defType) {
        try {
            if (ctx == null) {
                return 0;
            }
            return ctx.getResources().getIdentifier(name, defType, "android");
        } catch (Throwable t) {
            return 0;
        }
    }
}
