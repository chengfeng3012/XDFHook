package cn.cf3012.xdf;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChooserStandardActionRestore — 绕过 ChooserActivity 的 activityStartingStandardAction 过滤
 *
 * <p>XDF_Framework 在 {@code com.android.internal.app.xdf.IntentStandardActionManager}
 * 中新增了 {@code activityStartingStandardAction(Intent)} 静态方法，用于拦截/过滤
 * 某些标准 Action（{@code ACTION_SET_DISABLE} 集合包含 DIAL/CALL/SEND/SENDTO/ANSWER/
 * SEARCH/WEB_SEARCH/TRANSLATE/PROCESS_TEXT 等）。
 *
 * <p>ChooserActivity.onCreate（Android 10，XDF 定制版）在 .line ~510 调用：
 * <pre>
 *   invoke-static {v1}, Lcom/android/internal/app/xdf/IntentStandardActionManager;->activityStartingStandardAction(Landroid/content/Intent;)Z
 *   move-result v13
 *   if-nez v13:  finish() + startIntentStandardActionActivity() + return
 * </pre>
 *
 * <p>当返回 false 时，ChooserActivity 会直接终止并跳转到 {@code cn.xdf.zeus.activity.intent.standard.action}，
 * 导致原本应该弹出的分享/选择器（Chooser）被拦截或替换。
 *
 * <p>本模块将该静态方法的<strong>全部过滤条件去除</strong>，强制其返回 {@code true}，
 * 从根源上放行所有 Intent，不再干预 ChooserActivity 的正常启动流程。
 *
 * <p>作用域：{@code android:ui} 进程（framework 分享面板宿主，PKG_ANDROID）。
 * 与 {@link ChooserClickRestore} 配合使用，互不干扰。
 */
final class ChooserStandardActionRestore {
    private static final String TAG = "chooser.std";

    private static final String CLS_INTENT_STANDARD_ACTION_MANAGER =
            "com.android.internal.app.xdf.IntentStandardActionManager";

    private static final Class<?> CLS_INTENT = android.content.Intent.class;

    /** 同一进程内防重复注册 */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ChooserStandardActionRestore() {
    }

    static void hookAll(ClassLoader cl) {
        if (!sHooked.add(cl)) {
            return;
        }
        try {
            hookActivityStartingStandardAction(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "hookActivityStartingStandardAction");
        }
    }

    /**
     * Hook {@code IntentStandardActionManager.activityStartingStandardAction(Intent)}，
     * 强制返回 true，去除全部过滤条件。
     */
    private static void hookActivityStartingStandardAction(ClassLoader cl) throws Exception {
        Class<?> cls = Reflect.findClass(CLS_INTENT_STANDARD_ACTION_MANAGER, cl);
        Method m = Reflect.findDeclared(cls, "activityStartingStandardAction",
                new Class<?>[]{CLS_INTENT});
        if (m == null) {
            XDFHook.logw(TAG, "method not found: activityStartingStandardAction(Intent)");
            return;
        }
        m.setAccessible(true);

        // 替换返回值：强制 true（放行所有标准 Action）
        XDFHook.hook(m, chain -> {
            // 原方法完全跳过：日志自身都可能抛异常，不做任何有风险的操作
            return Boolean.TRUE;
        });

        XDFHook.logi(TAG, "hooked: IntentStandardActionManager.activityStartingStandardAction -> always true (filters removed)");
    }
}
