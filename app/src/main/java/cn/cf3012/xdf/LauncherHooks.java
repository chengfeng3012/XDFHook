package cn.cf3012.xdf;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LauncherHooks — 解除 Launcher3 最近任务的应用隐藏列表（原 XposedModule 整合）。
 *
 * XDF 定制 RecentTasksList (com.android.quickstep) 持有 mFilterPackageNames
 * （后台隐藏黑名单），并用 isRecentViewExempted(String) 把受控应用从最近任务
 * 中剔除。三层处理：
 *   1. 构造函数 (Context) after 清空 mFilterPackageNames（源头）；
 *   2. isRecentViewExempted(String) 恒返回 false（判定兜底）；
 *   3. loadTasksInBackground(int,boolean) after 打印任务数（诊断）。
 */
final class LauncherHooks {

    private static final String TAG = "launcher";
    private static final String CLS = "com.android.quickstep.RecentTasksList";

    /** 同一进程内防重复注册 */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private LauncherHooks() {
    }

    static void hookAll(ClassLoader cl) {
        if (!sHooked.add(cl)) {
            return;
        }
        try {
            Class<?> rtl = Reflect.findClass(CLS, cl);

            // 1) 构造函数 after：清空隐藏列表
            Constructor<?> ctor = Reflect.findConstructor(rtl, new Class<?>[]{Context.class});
            XDFHook.hook(ctor, chain -> {
                chain.proceed();
                Object self = chain.getThisObject(); // 构造器 after：thisObject 即实例
                if (self == null) {
                    return null;
                }
                try {
                    Object filterList = Reflect.getField(self, "mFilterPackageNames");
                    if (filterList instanceof List) {
                        List<?> list = (List<?>) filterList;
                        int size = list.size();
                        list.clear();
                        XDFHook.logi(TAG, "cleared mFilterPackageNames (size=" + size + ")");
                    }
                } catch (Throwable t) {
                    XDFHook.loge(t, TAG, "clear mFilterPackageNames");
                }
                return self;
            });
            XDFHook.logi(TAG, "hooked: RecentTasksList.<init>(Context)");

            // 2) 豁免判定 -> false
            Method exempted = Reflect.findDeclared(rtl, "isRecentViewExempted",
                    new Class<?>[]{String.class});
            XDFHook.hook(exempted, chain -> {
                // 每个最近任务项都会走一次（高频）：降为 debug 级
                XDFHook.logd(TAG, "isRecentViewExempted: " + chain.getArg(0) + " -> false");
                return Boolean.FALSE;
            });
            XDFHook.logi(TAG, "hooked: RecentTasksList.isRecentViewExempted -> false");

            // 3) 任务加载诊断日志（容错：方法签名可能随 ROM 变化）
            try {
                Method load = Reflect.findDeclared(rtl, "loadTasksInBackground",
                        new Class<?>[]{int.class, boolean.class});
                XDFHook.hook(load, chain -> {
                    Object result = chain.proceed();
                    if (result instanceof List) {
                        XDFHook.logi(TAG, "loadTasksInBackground -> "
                                + ((List<?>) result).size() + " tasks");
                    }
                    return result;
                });
                XDFHook.logi(TAG, "hooked: RecentTasksList.loadTasksInBackground (diagnostic)");
            } catch (Throwable t) {
                XDFHook.logw(TAG, "loadTasksInBackground skipped: " + t);
            }

            XDFHook.logi(TAG, "Launcher3 hooks installed");
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookAll");
        }
    }
}
