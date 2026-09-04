package cn.cf3012.xdf;

import android.view.View;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SystemHooks — framework 分享/选择器修复（原独立模块 XposedModule / XdfHook 整合）。
 *
 * 1. ResolverActivity.onButtonClick："仅此一次/始终"按钮被 ROM 阉割（删除了
 *    startSelected 调用），before 重算选中项并恢复调用，完全接管原方法。
 *
 * 2. ChooserActivity 分享面板点击修复（Android 10 / API 29）：
 *    startSelected(IZZ) 里 targetInfoForPosition 命中 NotSelectableTargetInfo
 *    （EmptyTargetInfo / PlaceHolderTargetInfo 的基类，所有方法含 start 都是
 *    空实现）会直接 return-void，点击被吞掉，且不能简单"放行"（会 NPE）。
 *    招1（主）：completeServiceTargetLoading 后清掉 mServiceTargets 中的
 *              NotSelectable 占位目标，让点击落位到真实目标；
 *    招2（兜底）：startSelected 命中 NotSelectable 时，手动找真实目标走
 *              onTargetSelected + finish，完全接管原方法。
 *
 * 注：ResolverActivity/ChooserActivity 是 framework 类，本 hook 对 scope 内
 * 所有进程生效；触摸/键盘无反应的另一层修复（item 点击监听补注册）在
 * ZeusUnlocker C 组，两者配合使用。
 */
final class SystemHooks {

    private static final String TAG = "resolver";

    /** 同一进程内防重复注册（多包共享 classloader 场景） */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private SystemHooks() {
    }

    static void hookAll(ClassLoader cl) {
        if (!sHooked.add(cl)) {
            return;
        }
        try {
            hookResolverButton(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "ResolverActivity.onButtonClick");
        }
        try {
            hookChooser(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "ChooserActivity");
        }
    }

    /* ==================== ResolverActivity ==================== */

    /**
     * 修复 ResolverActivity 的"仅此一次"按钮：
     * onButtonClick 中 ROM 删除了 startSelected 调用。
     * 恢复逻辑（与 AOSP 原版一致）：hasFilteredItem 时取 getFilteredPosition
     * （filtered=false），否则取 getCheckedItemPosition（filtered=true），
     * 再 startSelected(which, false=仅此一次, filtered)，完全接管原方法。
     */
    private static void hookResolverButton(ClassLoader cl) throws Exception {
        Class<?> resolver = Reflect.findClass("com.android.internal.app.ResolverActivity", cl);
        Method m = Reflect.findDeclared(resolver, "onButtonClick", new Class<?>[]{View.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            try {
                Object thiz = chain.getThisObject();
                Object adapter = Reflect.getField(thiz, "mAdapter");
                Object adapterView = Reflect.getField(thiz, "mAdapterView");

                int which = -1;
                boolean filtered = false;
                boolean hasFilteredItem =
                        (Boolean) Reflect.call(adapter, "hasFilteredItem", null);
                if (hasFilteredItem) {
                    which = (Integer) Reflect.call(adapter, "getFilteredPosition", null);
                    filtered = false;
                } else if (adapterView != null) {
                    which = (Integer) Reflect.call(adapterView, "getCheckedItemPosition", null);
                    filtered = true;
                }
                XDFHook.logi(TAG, "onButtonClick which=" + which + " filtered=" + filtered);

                if (which >= 0) {
                    Reflect.call(thiz, "startSelected",
                            new Class<?>[]{int.class, boolean.class, boolean.class},
                            which, false, filtered);
                    XDFHook.logi(TAG, "startSelected called successfully");
                } else {
                    XDFHook.logi(TAG, "no item selected, skipping startSelected");
                }
                return null;            // 完全接管原方法
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "onButtonClick hook");
                return chain.proceed(); // 出错时回退原逻辑
            }
        });
        XDFHook.logi(TAG, "hooked: ResolverActivity.onButtonClick");
    }

    /* ==================== ChooserActivity ==================== */

    private static void hookChooser(ClassLoader cl) throws Exception {
        Class<?> chooser = Reflect.findClass("com.android.internal.app.ChooserActivity", cl);
        Class<?> notSelectable = Reflect.findClass(
                "com.android.internal.app.ChooserActivity$NotSelectableTargetInfo", cl);

        hookClearPlaceholders(cl, notSelectable);
        hookStartSelectedFallback(cl, chooser, notSelectable);
        XDFHook.logi(TAG, "hooked: ChooserActivity (placeholder clear + startSelected fallback)");
    }

    /** 招1（主）：服务目标加载完成后清掉 mServiceTargets 中的不可选占位目标 */
    private static void hookClearPlaceholders(ClassLoader cl, final Class<?> notSelectable)
            throws Exception {
        Class<?> listAdapter = Reflect.findClass(
                "com.android.internal.app.ChooserActivity$ChooserListAdapter", cl);
        Method m = Reflect.findDeclared(listAdapter, "completeServiceTargetLoading",
                new Class<?>[0]);
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            chain.proceed();
            Object adapter = chain.getThisObject(); // void 方法 proceed() 返回 null
            try {
                Object targets = Reflect.getField(adapter, "mServiceTargets");
                if (targets instanceof List) {
                    List<?> list = (List<?>) targets;
                    int before = list.size();
                    boolean removed = list.removeIf(t -> t != null && notSelectable.isInstance(t));
                    XDFHook.logi(TAG, "completeServiceTargetLoading size " + before
                            + " -> " + list.size() + ", removed=" + removed);
                    if (removed) {
                        Reflect.call(adapter, "notifyDataSetChanged", null);
                    }
                }
                return adapter;
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "completeServiceTargetLoading hook");
                return adapter;
            }
        });
    }

    /** 招2（兜底）：startSelected 命中 NotSelectableTargetInfo 时转发给真实目标 */
    private static void hookStartSelectedFallback(ClassLoader cl, Class<?> chooser,
                                                  final Class<?> notSelectable) throws Exception {
        Method m = Reflect.findDeclared(chooser, "startSelected",
                new Class<?>[]{int.class, boolean.class, boolean.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            // ---- 探测阶段：只读取与判定；失败时还未 proceed，可安全回退原方法 ----
            int which;
            boolean always;
            boolean filtered;
            Object thiz;
            Object adapter;
            Object target;
            try {
                which = (Integer) chain.getArg(0);
                always = (Boolean) chain.getArg(1);
                filtered = (Boolean) chain.getArg(2);
                thiz = chain.getThisObject();
                adapter = Reflect.getField(thiz, "mChooserListAdapter");
                target = adapter == null ? null
                        : Reflect.call(adapter, "targetInfoForPosition",
                                new Class<?>[]{int.class, boolean.class}, which, filtered);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "startSelected fallback probe");
                return chain.proceed();     // 尚未 proceed，安全回退
            }

            if (target == null || !notSelectable.isInstance(target)) {
                // 正常目标，放行原逻辑
                XDFHook.logi(TAG, "startSelected pos=" + which + " filtered=" + filtered
                        + " target="
                        + (target == null ? "null" : target.getClass().getSimpleName())
                        + " -> passthrough");
                return chain.proceed();
            }

            // ---- 命中 NotSelectable：转发真实目标并完全接管（不再执行原方法，
            //      原方法对 NotSelectable 只会吞掉点击，且不可二次 proceed）----
            XDFHook.logi(TAG, "forwarding NotSelectable at pos " + which);
            try {
                Object real = findRealTarget(adapter, notSelectable);
                if (real != null) {
                    XDFHook.logi(TAG, "forwarding to real target "
                            + real.getClass().getSimpleName());
                    Object ok = Reflect.call(thiz, "onTargetSelected",
                            new Class<?>[]{
                                    Reflect.findClass(
                                            "com.android.internal.app.ResolverActivity$TargetInfo", cl),
                                    boolean.class},
                            real, always);
                    if (Boolean.TRUE.equals(ok)) {
                        Reflect.call(thiz, "finish", null);
                    }
                } else {
                    XDFHook.logi(TAG, "findRealTarget returned null, cannot forward");
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "startSelected fallback");
            }
            return null;
        });
    }

    /** 在 caller 直达目标 / ranked 应用列表里找第一个真实可启动的目标 */
    private static Object findRealTarget(Object adapter, Class<?> notSelectable) {
        try {
            Object caller = Reflect.getField(adapter, "mCallerTargets");
            if (caller instanceof List) {
                for (Object t : (List<?>) caller) {
                    if (t != null && !notSelectable.isInstance(t)) {
                        return t;
                    }
                }
            }
            int n = (Integer) Reflect.call(adapter, "getDisplayResolveInfoCount", null);
            for (int i = 0; i < n; i++) {
                Object t = Reflect.call(adapter, "getDisplayResolveInfo",
                        new Class<?>[]{int.class}, i);
                if (t != null && !notSelectable.isInstance(t)) {
                    return t;
                }
            }
            // 兜底：直接读 mDisplayList 字段
            Object displayList = Reflect.getField(adapter, "mDisplayList");
            if (displayList instanceof List) {
                for (Object t : (List<?>) displayList) {
                    if (t != null && !notSelectable.isInstance(t)) {
                        return t;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
