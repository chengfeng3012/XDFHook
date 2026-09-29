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
     * ChooserActivity 点击修复已迁移到 {@link ChooserClickRestore}（单 hook
     * loadViewsIntoRow，100% 复刻 AOSP 的 $2/$3 监听器绑定）。
     *
     * 【原两招为何整体删除】—— 逐方法核对 smali 后的结论：
     *  招1 completeServiceTargetLoading 后清 mServiceTargets 的 NotSelectable：
     *      该方法 ROM 【未改动】（两边均 14 指令），且其 removeIf + 空列表补
     *      EmptyTargetInfo 正是「服务目标异步加载」的正常设计。强行删占位符会
     *      破坏列表项数与实际目标的一致性，属有害操作。
     *  招2 startSelected 命中 NotSelectable 时转发现实目标：
     *      该早退 ROM 【未改动】（.line 1285-1286 保留），且仅
     *      EmptyTargetInfo / PlaceHolderTargetInfo 继承 NotSelectableTargetInfo
     *      （SelectableTargetInfo extends Object，不受影响）—— 正常目标根本
     *      不会命中。findRealTarget 扫描 mCallerTargets/mDisplayList 属猜测式
     *      定位，可能转发到错误目标。
     *
     * 真正的缺口只是 loadViewsIntoRow 里被删的两次 setXxxListener，
     * 前后（含 RowViewHolder.mItemIndices 映射表与 bindViewHolder 填充逻辑）
     * ROM 全部完好，因此单点修复即可，无需任何数据层改写。
     *
     * 保留本方法名作为调用点（由 SystemHooks.hookAll 调用），避免改动调度。
     */
    private static void hookChooser(ClassLoader cl) {
        ChooserClickRestore.hookAll(cl);
    }

}
