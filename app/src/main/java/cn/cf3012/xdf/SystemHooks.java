package cn.cf3012.xdf;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SystemHooks — framework 分享/选择器修复的调度入口。
 *
 * 本类只负责分发，两个具体修复各自独立成类：
 *
 * 1. ResolverActivity 按钮 → {@link ResolverAlwaysRestore}
 *    ROM 删除了 mAlwaysButton 字段、resetButtonBar 的按钮绑定，并截断了
 *    onButtonClick 内的 startSelected 调用（导致「仅此一次」空转）。
 *    新实现按 AOSP 原版语义同时分派 once/always，并动态插入「始终」按钮。
 *
 * 2. ChooserActivity 图标点击 → {@link ChooserClickRestore}
 *    ROM 删除了 loadViewsIntoRow 中给每个 cell 绑定
 *    OnClickListener(ChooserRowAdapter$2) 与
 *    OnLongClickListener(ChooserRowAdapter$3) 的两段代码，
 *    且 $2/$3 两个内部类一并消失，导致图标点击完全无响应。
 *    新实现单 hook loadViewsIntoRow，100% 复刻 $2/$3 语义。
 *
 * 注：两个修复都经逐条 smali 指令比对确认缺口边界，只补被删的部分，
 * 不改动 ROM 保留的逻辑。详见各实现类的注释。
 */
final class SystemHooks {

    private static final String TAG = "resolver";

    /** 同一进程内防重复注册（多包共享 classloader 场景） */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private SystemHooks() {
    }

    static void hookAll(ClassLoader cl, boolean shareChooser, boolean chooserStdAction) {
        if (!sHooked.add(cl)) {
            return;
        }
        if (shareChooser) {
            try {
                hookResolverButton(cl);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "ResolverActivity.onButtonClick");
            }
        }
        if (shareChooser || chooserStdAction) {
            try {
                hookChooser(cl, chooserStdAction);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "ChooserActivity");
            }
        }
    }

    /* ==================== ResolverActivity ==================== */

    /**
     * ResolverActivity 按钮修复已迁移到 {@link ResolverAlwaysRestore}。
     *
     * 迁移原因：原实现只补了 startSelected(which, false=仅此一次, ...)，
     * 恒为 once，无法支持「始终」；且 ROM 已删除 mAlwaysButton 字段与
     * resetButtonBar 里的绑定，需要一并恢复按钮 UI。新的 ResolverAlwaysRestore
     * 按 AOSP 原版语义同时分派 once/always（含网页类 showSettingsForSelected
     * 分支），并动态插入 always 按钮。
     *
     * 保留本方法名作为调用点（由 SystemHooks.hookAll 调用），避免改动调度。
     */
    private static void hookResolverButton(ClassLoader cl) {
        ResolverAlwaysRestore.hookAll(cl);
    }

    /* ==================== ChooserActivity ==================== */

    /**
     * ChooserActivity 修复
     */
    private static void hookChooser(ClassLoader cl, boolean stdActionEnabled) {
        if (stdActionEnabled) {
            // 独立开关：解除 IntentStandardActionManager 对 9 条标准 Action 的劫持过滤。
            // 不依赖「分享面板修复」，两者开关相互独立。
            try {
                ChooserStandardActionRestore.hookAll(cl);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "ChooserStandardActionRestore");
            }
        } else {
            XDFHook.logi(TAG, "chooserStdAction disabled, skip");
        }
        ChooserClickRestore.hookAll(cl);
    }

}
