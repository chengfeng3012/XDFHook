package cn.cf3012.xdf;

import android.content.pm.ResolveInfo;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChooserClickRestore — 恢复 ChooserActivity 分享面板被 ROM 删除的图标
 * 点击 / 长按监听器。
 *
 * 【ROM 阉割的静态事实】（framework.apk 实测，对照 AOSP-G 10，指令级比对）
 *
 *  AOSP-G 的 ChooserRowAdapter.loadViewsIntoRow(RowViewHolder)（69 指令）在
 *  .line 3158-3173 有两段绑定：
 *      new-instance v8, ChooserActivity$ChooserRowAdapter$2;   // OnClickListener
 *      invoke-direct {v8, p0, p1, v7}, ...$2;-><init>(RowAdapter, RowViewHolder, int)V
 *      invoke-virtual {v6, v8}, Landroid/view/View;->setOnClickListener(...)V
 *      new-instance v8, ChooserActivity$ChooserRowAdapter$3;   // OnLongClickListener
 *      invoke-direct {v8, p0, p1, v7}, ...$3;-><init>(RowAdapter, RowViewHolder, int)V
 *      invoke-virtual {v6, v8}, Landroid/view/View;->setOnLongClickListener(...)V
 *
 *  XDF 版（63 指令）把 .line 3158-3173 整段删除 —— .line 从 3072 直跳 3088，
 *  createView 之后直接 addView，【不绑任何监听器】。
 *  佐证：XDF 的 ChooserRowAdapter 只剩 $1（DataSetObserver），
 *        AOSP-G 有 $1/$2/$3；$2/$3 两个内部类在 XDF 中不存在。
 *
 *  这就是「分享面板图标点了没反应 / 空格无反应」的唯一根因：
 *  每个图标格从创建到显示从未绑定点击回调。
 *
 * 【为什么其余部分不需要修】（均已逐方法核对，未被 ROM 改动）
 *  - ChooserRowAdapter.areAllItemsEnabled()：两边均 2 指令 = 原生 return true。
 *    旧实现「ROM 硬编码 return false」的判断与实测不符。
 *  - ChooserRowAdapter.isEnabled(int)：两边均 11 指令，逻辑一致。
 *  - ChooserActivity.onPrepareAdapterView：两边一致且【故意】不调 super、
 *    不注册 OnItemClickListener —— 这是 AOSP 原生设计（Chooser 用 cell 自带
 *    监听器），不是缺陷。旧实现在此补 OnItemClickListener 属非原版机制。
 *  - ChooserListAdapter.onBindView / targetInfoForPosition /
 *    completeServiceTargetLoading：两边指令数完全一致。
 *  - EmptyTargetInfo / PlaceHolderTargetInfo 占位机制：XDF 保留完好。
 *    completeServiceTargetLoading 本来就负责 removeIf + 空列表时补占位，
 *    强行删除占位符会破坏「列表项数 ↔ 实际目标」一致性。
 *  - startSelected 的 NotSelectable 早退：XDF 保留（.line 1285-1286）。
 *    仅 EmptyTargetInfo / PlaceHolderTargetInfo 继承 NotSelectableTargetInfo；
 *    SelectableTargetInfo extends Object，故正常目标完全不受该早退影响。
 *
 * 【本类做法：1 个 hook，100% 复刻 AOSP $2/$3 语义】
 *  hook loadViewsIntoRow after（此时 cell 已由原方法创建并 addView），
 *  遍历 holder 的每一列补绑两个监听器：
 *    点击   -> startSelected(holder.getItemIndex(column), false, true)
 *    长按   -> showTargetDetails(adapter.resolveInfoForPosition(
 *                    holder.getItemIndex(column), true))，返回 true 消费事件
 *
 *  关键：行内列 -> 目标列表索引的映射必须用 RowViewHolder.getItemIndex(column)。
 *  该映射表 mItemIndices[] 由 XDF 原生的 bindViewHolder 在 .line 3232
 *  用 setItemIndex(column, start+column) 正常填充，基础设施完好。
 *  旧实现改用 ChooserListAdapter 线性扫 getItem(i)==info 反查，既绕路又依赖
 *  合成字段 this$0；本实现与原版一致，无此问题。
 *
 *  filtered 参数固定传 true —— 照抄 AOSP $2/$3 的
 *  startSelected(itemIndex, false, true) / resolveInfoForPosition(itemIndex, true)。
 *  旧实现传 false，会走错 targetInfoForPosition 分支。
 */
final class ChooserClickRestore {

    private static final String TAG = "chooser";

    private static final String CLS_ROW_ADAPTER =
            "com.android.internal.app.ChooserActivity$ChooserRowAdapter";
    private static final String CLS_HOLDER =
            "com.android.internal.app.ChooserActivity$RowViewHolder";

    /** 同一进程内防重复注册 */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ChooserClickRestore() {
    }

    static void hookAll(ClassLoader cl) {
        if (!sHooked.add(cl)) {
            return;
        }
        try {
            hookLoadViewsIntoRow(cl);
        } catch (Throwable t) {
            XDFHook.loge(t, TAG, "loadViewsIntoRow restore");
        }
    }

    /**
     * hook loadViewsIntoRow(RowViewHolder) after：原方法已跑完，cell 已创建并
     * addView 到 holder；此处按 AOSP 原版补回 $2/$3 的两次绑定。
     */
    private static void hookLoadViewsIntoRow(ClassLoader cl) throws Exception {
        Class<?> rowAdapter = Reflect.findClass(CLS_ROW_ADAPTER, cl);
        Class<?> holderCls = Reflect.findClass(CLS_HOLDER, cl);
        Method m = Reflect.findDeclared(rowAdapter, "loadViewsIntoRow", new Class<?>[]{holderCls});
        m.setAccessible(true);

        XDFHook.hook(m, chain -> {
            Object result = chain.proceed();
            try {
                Object adapter = chain.getThisObject();     // ChooserRowAdapter
                Object holder = chain.getArg(0);            // RowViewHolder
                if (adapter == null || holder == null) {
                    return result;
                }
                bindListeners(adapter, holder);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "bind cell listeners");
            }
            return result;
        });
        XDFHook.logi(TAG, "hooked: ChooserRowAdapter.loadViewsIntoRow (click+longclick restored)");
    }

    /** 为 holder 每一列的 cell 补绑点击 / 长按监听器（复刻 AOSP $2/$3） */
    private static void bindListeners(Object rowAdapter, Object holder) {
        // 外层 Activity：ChooserRowAdapter.this$0
        final Object activity;
        try {
            activity = Reflect.getField(rowAdapter, "this$0");
        } catch (Throwable t) {
            XDFHook.logd(TAG, "bind: outer activity field missing");
            return;
        }
        if (activity == null) {
            XDFHook.logd(TAG, "bind: outer activity unavailable");
            return;
        }
        // 行适配器引用的目标列表适配器（长按需要 resolveInfoForPosition）
        final Object listAdapter;
        try {
            listAdapter = Reflect.getField(rowAdapter, "mChooserListAdapter");
        } catch (Throwable t) {
            XDFHook.logd(TAG, "bind: mChooserListAdapter unavailable");
            return;
        }

        final int columnCount;
        try {
            Object n = Reflect.call(holder, "getColumnCount", null);
            columnCount = (n instanceof Number) ? ((Number) n).intValue() : 0;
        } catch (Throwable t) {
            XDFHook.logd(TAG, "bind: getColumnCount failed");
            return;
        }
        int bound = 0;
        for (int column = 0; column < columnCount; column++) {
            final int col = column;
            Object viewObj;
            try {
                viewObj = Reflect.call(holder, "getView", new Class<?>[]{int.class}, col);
            } catch (Throwable t) {
                continue;
            }
            if (!(viewObj instanceof View)) {
                continue;
            }
            final View cell = (View) viewObj;

            // ---- $2: OnClickListener ----
            // startSelected(holder.getItemIndex(col), always=false, filtered=true)
            cell.setOnClickListener(v -> {
                try {
                    int itemIndex = itemIndexOf(holder, col);
                    if (itemIndex < 0) {
                        XDFHook.logd(TAG, "click: invalid itemIndex at col=" + col);
                        return;
                    }
                    Reflect.call(activity, "startSelected",
                            new Class<?>[]{int.class, boolean.class, boolean.class},
                            itemIndex, false, true);
                    XDFHook.logv(TAG, "click -> startSelected pos=" + itemIndex
                            + " always=false filtered=true");
                } catch (Throwable t) {
                    XDFHook.loge(t, TAG, "cell onClick col=" + col);
                }
            });

            // ---- $3: OnLongClickListener ----
            // showTargetDetails(adapter.resolveInfoForPosition(itemIndex, true))
            if (listAdapter != null) {
                cell.setOnLongClickListener(v -> {
                    try {
                        int itemIndex = itemIndexOf(holder, col);
                        if (itemIndex < 0) {
                            return false;
                        }
                        Object ri = Reflect.call(listAdapter, "resolveInfoForPosition",
                                new Class<?>[]{int.class, boolean.class}, itemIndex, true);
                        if (ri instanceof ResolveInfo) {
                            Reflect.call(activity, "showTargetDetails",
                                    new Class<?>[]{ResolveInfo.class}, ri);
                            XDFHook.logv(TAG, "longclick -> showTargetDetails pos=" + itemIndex);
                        } else {
                            return false;
                        }
                        return true;      // 照抄 $3：消费事件
                    } catch (Throwable t) {
                        XDFHook.loge(t, TAG, "cell onLongClick col=" + col);
                        return false;
                    }
                });
            }
            bound++;
        }
        XDFHook.logv(TAG, "bind: " + bound + "/" + columnCount + " cells got listeners");
    }

    /** holder.getItemIndex(column)，失败返回 -1 */
    private static int itemIndexOf(Object holder, int column) {
        try {
            Object v = Reflect.call(holder, "getItemIndex", new Class<?>[]{int.class}, column);
            return (v instanceof Number) ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
