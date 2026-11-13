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
 * <h3>★ 资源 id 映射（设备真实 {@code /system/framework/framework-res.apk} 实测）</h3>
 * <pre>
 *   button_always = 0x010201EA
 *   button_bar    = 0x010201EB
 *   button_once   = 0x010201EC
 * </pre>
 * <p>★ 不要用 {@code XDF_framework.apk}（用户自行封装的样本）在 MT2 里查到的
 * EB/EC/ED —— 那套比设备真实值整体 <b>+1</b>（样本资源表与设备不一致）。
 * 自证：{@code resetButtonBar()} 用 {@code 0x10201eb} 找 <b>ViewGroup</b>(button_bar)、
 * 用 {@code 0x10201ec} 找 <b>Button</b>(button_once)，只有 EB/EC 这套才自洽。</p>
 *
 * <h3>★ ROM 阉割的静态事实（XDF_framework.apk / android vc29，2026-10-18）</h3>
 *
 * <ol>
 *   <li><b>onButtonClick(View) 被截断</b>：算完 which / resolveInfoForPosition 后直接
 *       {@code return-void}（.line 981），<b>删除了 startSelected / showSettingsForSelected</b>。
 *       XML 里 {@code android:onClick="onButtonClick"} 仍生效（点击进得来，只是里面什么都不做）
 *       —— 这是「仅此一次」按钮点了没反应的根因。支撑方法
 *       {@code startSelected(IZZ)V} / {@code showSettingsForSelected(ResolveInfo)} 完整未改。</li>
 *   <li><b>「始终」按钮：ROM 把整个 View 删掉了</b>
 *       <ul>
 *         <li>设备真实 {@code res/layout/resolver_list.xml}（fw_dev.apk 实测）：button_bar
 *             （0x010201EB）里<b>只有 button_once（0x010201EC）</b>，
 *             <b>完全没有 button_always</b>。</li>
 *         <li>字段 {@code mAlwaysButton} 不存在；{@code resetButtonBar()} 只
 *             {@code findViewById(button_bar)} → {@code buttonLayout.findViewById(button_once)}
 *             → {@code iput mOnceButton}，<b>全程不碰 button_always</b>。</li>
 *         <li>{@code setAlwaysButtonEnabled(ZIZ)V} 的 dispatch xref = <b>0</b>（死代码）。</li>
 *         <li>{@code ItemClickListener.onItemClick()} 里唯一的按钮更新是
 *             {@code access$1100(this).setEnabled(valid)}，而 {@code access$1100} 的实现是
 *             <b>{@code return this.mOnceButton;}</b> —— 只更新 once。</li>
 *         <li>{@code resetAlwaysOrOnceButtonBar()} 也只 {@code mOnceButton.setEnabled(true)}（单向）。</li>
 *       </ul>
 *       ⇒ <b>按钮在，但永远是灰的、点不动</b>，且没有任何代码路径会打开它。</li>
 * </ol>
 *
 * <h3>★ 旧实现的错误（用户反馈"显示没修好 + 点不了"）</h3>
 * <ol>
 *   <li><b>enabled 只同步一次</b>：旧实现只在 onCreate 复制一次 once 的 enabled；而 ROM
 *       之后只会更新 mOnceButton（{@code access$1100} 的实现就是 return mOnceButton），
 *       我们的按钮没有任何代码路径会 enable ⇒ 永远停在初始 disabled（"点不了"）。</li>
 *   <li><b>样式不对</b>：{@code new Button(act)} 用默认样式，而 button_once 用的是
 *       {@code ?android:attr/buttonBarButtonStyle} ⇒ 观感不一致
 *       （用户实测："看着和仅此一次的 style 不一样"）。</li>
 *   <li>附带：插入时用 {@code new ViewGroup.LayoutParams(srcLp)} 会丢掉 LinearLayout 的
 *       weight / gravity（若源宽度为 0dp，按钮宽度直接变 0）。</li>
 * </ol>
 *
 * <h3>★ 现方案</h3>
 * <ul>
 *   <li><b>H1</b> {@code onButtonClick} before 完全接管：按 AOSP 原版语义重算并分派
 *       once/always（含网页类 {@code showSettingsForSelected} 分支）。
 *       修复「仅此一次」空转，同时让「始终」按钮的点击天然生效
 *       （XML 的 onClick 会走进这里）。</li>
 *   <li><b>H2</b> 接管布局里已有的 button_always（没有才插入，且用
 *       {@code LinearLayout.LayoutParams} 完整复制），并让它的 enabled 跟随
 *       mOnceButton —— 同步点取 ROM <b>唯二</b>会更新按钮状态的时机：
 *       {@code ItemClickListener.onItemClick} 与 {@code resetAlwaysOrOnceButtonBar}。</li>
 * </ul>
 *
 * <p>注：刻意不碰 {@code mSupportsAlwaysUseOption} —— 它同时控制
 * {@code onPrepareAdapterView} 里的 setChoiceMode，改它会连带把列表点击语义
 * 从「直接启动」切成「勾选」。</p>
 */
final class ResolverAlwaysRestore {

    private static final String TAG = "resolver";

    /** 同一进程内防重复注册 */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());

    /**
     * 资源名 —— 按名字解析，规避 framework-res 各版本 id 漂移。
     * 实测 XDF：button_always=0x010201EB / button_bar=0x010201EC / button_once=0x010201ED。
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
            hookAlwaysButton(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "always button takeover");
        }
    }

    /* ==================== H1: onButtonClick 完全接管 ==================== */

    /**
     * 恢复 AOSP 原版 onButtonClick 语义：
     * <pre>
     *   int which = hasFilteredItem ? getFilteredPosition()
     *                               : mAdapterView.getCheckedItemPosition();
     *   boolean hasIndexBeenFiltered = !hasFilteredItem;
     *   ResolveInfo ri = resolveInfoForPosition(which, hasIndexBeenFiltered);
     *   if (mUseLayoutForBrowsables &amp;&amp; !ri.handleAllWebDataURI
     *           &amp;&amp; id == R.id.button_always) {
     *       showSettingsForSelected(ri);      // 网页类：跳浏览器默认设置
     *       return;
     *   }
     *   boolean always = (id == R.id.button_always);
     *   startSelected(which, always, hasIndexBeenFiltered);
     * </pre>
     * ROM 把后半段全删了。before 完全接管重建；失败回退 proceed()
     * （= ROM 当前空转行为，不会引入更糟的故障）。
     */
    private static void hookOnButtonClick(ClassLoader cl) throws Exception {
        Class<?> resolver = Reflect.findClass("com.android.internal.app.ResolverActivity", cl);
        Method m = Reflect.findDeclared(resolver, "onButtonClick", new Class<?>[]{View.class});
        m.setAccessible(true);
        XDFHook.deopt(m);
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

    /* ==================== H2: 接管「始终」按钮 + enabled 同步 ==================== */

    private static void hookAlwaysButton(ClassLoader cl) throws Exception {
        Class<?> resolver = Reflect.findClass("com.android.internal.app.ResolverActivity", cl);

        // (a) onCreate(7参) after —— 此时 configureContentView/resetButtonBar 已跑完
        Method onCreate = Reflect.findDeclared(resolver, "onCreate", new Class<?>[]{
                android.os.Bundle.class, Intent.class, CharSequence.class, int.class,
                Intent[].class, java.util.List.class, boolean.class});
        onCreate.setAccessible(true);
        XDFHook.hook(onCreate, chain -> {
            Object r = chain.proceed();
            try {
                Object thiz = chain.getThisObject();
                if (thiz instanceof android.app.Activity) {
                    ensureAlwaysButton((android.app.Activity) thiz);
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "ensure always button");
            }
            return r;
        });
        XDFHook.logi(TAG, "hooked: ResolverActivity.onCreate (ensure always button)");

        // (b) 同步点 1 —— 列表项点击（ROM 在这里更新 mOnceButton）
        try {
            Class<?> icl = Reflect.findClass(
                    "com.android.internal.app.ResolverActivity$ItemClickListener", cl);
            Method m = Reflect.findDeclared(icl, "onItemClick", new Class<?>[]{
                    android.widget.AdapterView.class, View.class, int.class, long.class});
            m.setAccessible(true);
            XDFHook.hook(m, chain -> {
                Object r = chain.proceed();
                syncAlwaysEnabled(chain.getThisObject());   // ItemClickListener
                return r;
            });
            XDFHook.logi(TAG, "hooked: ItemClickListener.onItemClick (always-enabled sync)");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "ItemClickListener.onItemClick skipped: " + t);
        }

        // (c) 同步点 2 —— resetAlwaysOrOnceButtonBar（resetButtonBar 内，onCreate/onRestart）
        try {
            Method m = Reflect.findDeclared(resolver, "resetAlwaysOrOnceButtonBar",
                    new Class<?>[0]);
            m.setAccessible(true);
            XDFHook.hook(m, chain -> {
                Object r = chain.proceed();
                syncAlwaysEnabled(chain.getThisObject());   // ResolverActivity
                return r;
            });
            XDFHook.logi(TAG, "hooked: resetAlwaysOrOnceButtonBar (always-enabled sync)");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "resetAlwaysOrOnceButtonBar skipped: " + t);
        }
    }

    /**
     * 确保「始终」按钮存在且可用。
     *
     * <p><b>本 ROM 实测（设备真实 fw_dev.apk 的 res/layout/resolver_list.xml）</b>：
     * button_bar 里<b>只有 button_once 一个 Button</b> —— ROM 把 button_always 的
     * <b>整棵 View 删掉了</b>（不是只删代码引用），所以必须自己插入。</p>
     *
     * <p>插入时两个细节决定观感与可用性：</p>
     * <ul>
     *   <li><b>样式</b>：必须沿用 button_once 的 {@code ?android:attr/buttonBarButtonStyle}。
     *       直接用 {@code new Button(act)} 的默认样式会让它"看着和仅此一次不一样"
     *       （上一版就是这样，用户实测指出）。</li>
     *   <li><b>布局参数</b>：用 {@link LinearLayout.LayoutParams} 的拷贝构造完整复制
     *       （{@code ViewGroup.LayoutParams} 的拷贝构造会丢掉 weight / gravity）。</li>
     * </ul>
     */
    private static void ensureAlwaysButton(android.app.Activity act) {
        // 分享面板 ChooserActivity 继承 ResolverActivity，但用 chooser 布局、没有 button_bar
        if (act.getClass().getName().contains("ChooserActivity")) {
            return;
        }
        int barId = resId(act, ID_BUTTON_BAR, "id");
        if (barId == 0) {
            XDFHook.logd(TAG, "always: button_bar id not resolved");
            return;
        }
        View barView = act.findViewById(barId);
        if (!(barView instanceof LinearLayout)) {
            XDFHook.logd(TAG, "always: button_bar is not a LinearLayout");
            return;
        }
        LinearLayout bar = (LinearLayout) barView;

        int alwaysId = resId(act, ID_BUTTON_ALWAYS, "id");
        if (alwaysId == 0) {
            XDFHook.logw(TAG, "always: button_always id not resolved");
            return;
        }

        // ---- ① 某些 ROM 变体保留了该按钮 → 直接接管，不重复插入 ----
        View existing = bar.findViewById(alwaysId);
        if (existing instanceof Button) {
            syncAlwaysEnabled(act);
            XDFHook.logi(TAG, "always: reused existing button (ROM kept it)");
            return;
        }

        // ---- ② 布局里没有 → 插入（修掉旧实现的 LayoutParams 丢 weight 问题）----
        int onceId = resId(act, ID_BUTTON_ONCE, "id");
        View once = onceId != 0 ? bar.findViewById(onceId) : null;

        // ② 本 ROM：布局里只有 button_once，必须插入；样式沿用 once 的 buttonBarButtonStyle
        Button btn = newStyledButton(act);
        btn.setId(alwaysId);
        int textId = resId(act, STR_USE_ALWAYS, "string");
        if (textId == 0) {
            textId = resId(act, STR_SET_ALWAYS, "string");
        }
        if (textId != 0) {
            btn.setText(textId);
        }
        btn.setEnabled(once != null && once.isEnabled());
        btn.setOnClickListener(v -> {
            try {
                dispatchOnButtonClick(act, v);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "always button click");
            }
        });

        ViewGroup.LayoutParams lp = (once != null) ? once.getLayoutParams() : null;
        LinearLayout.LayoutParams newLp;
        if (lp instanceof LinearLayout.LayoutParams) {
            // ★ 完整复制（含 weight/margins/gravity），不要用 ViewGroup.LayoutParams 的拷贝构造
            newLp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp);
        } else if (lp != null) {
            newLp = new LinearLayout.LayoutParams(lp.width, lp.height);
        } else {
            newLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        bar.addView(btn, newLp);
        XDFHook.logi(TAG, "always button: inserted (id=0x" + Integer.toHexString(alwaysId)
                + ", weight=" + newLp.weight + ", enabled=" + btn.isEnabled() + ")");
    }

    /**
     * 创建一个样式与 button_once 一致的 Button。
     *
     * <p>button_once 在布局里是 {@code style="?android:attr/buttonBarButtonStyle"}。
     * 直接把该 attr 解析成 style 资源，再走 {@code Button(Context, AttributeSet, int, int)}
     * 四参构造（API 21+）应用 —— 这样"始终"与"仅此一次"视觉完全一致。</p>
     *
     * <p>解析失败（定制主题没定义该 attr）时退回默认构造，不影响功能。</p>
     */
    private static Button newStyledButton(android.app.Activity act) {
        int styleRes = 0;
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (act.getTheme().resolveAttribute(
                    android.R.attr.buttonBarButtonStyle, tv, true)) {
                styleRes = tv.resourceId;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (styleRes != 0) {
                XDFHook.logd(TAG, "always: applying buttonBarButtonStyle 0x"
                        + Integer.toHexString(styleRes));
                return new Button(act, null, 0, styleRes);
            }
        } catch (Throwable t) {
            XDFHook.logw(TAG, "always: styled ctor failed, fallback: " + t);
        }
        return new Button(act);
    }

    /**
     * 把「始终」按钮的 enabled 同步为「仅此一次」的状态。
     *
     * <p>ROM 只维护 {@code mOnceButton}（{@code access$1100} 的实现就是 return mOnceButton），
     * 所以我们的按钮必须自己跟随它 —— 同步点即 ROM 唯二会更新按钮状态的时机。</p>
     *
     * @param fromActivityOrListener ResolverActivity，或 ItemClickListener（内部先取 this$0）
     */
    private static void syncAlwaysEnabled(Object fromActivityOrListener) {
        try {
            Object act = fromActivityOrListener;
            if (!(act instanceof android.app.Activity)) {
                act = Reflect.getField(fromActivityOrListener, "this$0");
            }
            if (!(act instanceof android.app.Activity)) {
                return;
            }
            android.app.Activity a = (android.app.Activity) act;

            int barId = resId(a, ID_BUTTON_BAR, "id");
            int onceId = resId(a, ID_BUTTON_ONCE, "id");
            int alwaysId = resId(a, ID_BUTTON_ALWAYS, "id");
            if (barId == 0 || onceId == 0 || alwaysId == 0) {
                return;
            }
            View bar = a.findViewById(barId);
            if (!(bar instanceof ViewGroup)) {
                return;
            }
            View once = ((ViewGroup) bar).findViewById(onceId);
            View always = ((ViewGroup) bar).findViewById(alwaysId);
            if (once == null || always == null) {
                return;
            }
            boolean want = once.isEnabled();
            if (always.isEnabled() != want) {
                always.setEnabled(want);
                XDFHook.logd(TAG, "always.enabled -> " + want + " (follow once)");
            }
        } catch (Throwable t) {
            XDFHook.logd(TAG, "syncAlwaysEnabled skipped: " + t);
        }
    }

    /* ==================== always 按钮点击复用分派 ==================== */

    /**
     * always 按钮 onClick 复用 onButtonClick 分派（仅"布局里没有该按钮、由我们插入"
     * 的 ROM 变体用到；本 ROM 走 XML 的 android:onClick，同样落到 H1）。
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
