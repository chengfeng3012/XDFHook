package cn.cf3012.xdf;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QuickSettingsHooks — 修复 XDF 定制 SystemUI 控制中心（下拉菜单 / QS）被阉割的六处问题
 * （原独立 QSFix 模块整合而来，LibXposed api 102）。
 *
 * <p>逆向依据：MT2 逐条对比 AOSP-G_SystemUI vs XDF_MtkSystemUI（2026-09-28 首轮，
 * 2026-09-30 独立复审纠偏，2026-10-01 编辑页列数对齐）。</p>
 *
 * <p><b>① QS 面板拉不开 —— MTK 焊死了手势总开关</b><br>
 * XDF 的 {@code StatusBar.updateQsExpansionEnabled()} 被砍成 {@code .registers 2}
 * + 19 行，只剩 {@code const/4 v0, 0x0}，日志文案也写死 "enabled: false"；AOSP 原版是
 * {@code .registers 4} + 79 行 / 6 大条件（isDeviceProvisioned / mUserSetup /
 * isSimpleUserSwitcher / mDisabled2&amp;4 / mDozing / ONLY_CORE_APPS）。<br>
 * 这一行同时废掉三件事：{@code NotificationPanelView.handleQsTouch} 的闸门
 * （mQsTracking 永不置 true）、{@code onQsTouch} 里的 setQsExpansion
 * （expansion 恒 0 → 面板被顶出屏幕外再裁掉）、{@code mQsFullyExpanded} 恒 false
 * （铅笔/分页圆点锁在 GONE）。<br>
 * 挂载点选 {@code NotificationPanelView.setQsExpansionEnabled(Z)}：StatusBar 里那个是
 * private 且时机不定，而这个是 public 唯一写入口、全 dex 仅 1 个调用点，在此处把 false
 * 改写成 true 可覆盖所有来源，天然幂等。→ {@link #hookQsExpansionEnabled}</p>
 *
 * <p><b>② 网格列数/行数被写死</b><br>
 * XDF {@code TileLayout.updateResources()}：{@code mMaxAllowedRows = 1}、
 * {@code mColumns = 6}，{@code getInteger + Math.max} 整段被删换成字面量。
 * 消费端 {@code updateMaxRows(II)Z} 两侧 targetVersion 完全相同（未被阉割），改字段即生效。<br>
 * 挂基类 {@code TileLayout} 而非 {@code TilePage}：{@code TilePage.updateResources()} 会
 * invoke-super 上来，{@code PagedTileLayout} 不调 super 只遍历 mPages，所以基类这一个
 * hook 能命中每个 TilePage 实例。→ {@link #hookTileLayout}</p>
 *
 * <p><b>③ 只改字段还不够，分页建不起来</b><br>
 * {@code PagedTileLayout.onMeasure} 里 {@code distributeTiles()} 只在
 * {@code updateMaxRows()} 返回 true（行数发生变化）时执行；mMaxAllowedRows 被钉成 1 后
 * mRows 恒被钳为 1 → 永不变化 → 不建页 → 退化成早退路径，12 个 tile 一次性塞进
 * 唯一一页而被裁掉第二行，现象正是「只显示 6 个 + 划不动」。<br>
 * 必须在 onMeasure before 把 mLastMaxHeight 打成不可能值，强制每次重算 + 重建页；
 * 带 {@code mPages.size() <= 1} 门控（onMeasure 是布局热路径，distributeTiles 含 inflate，
 * 无条件干预会白白吃 CPU），建出 2 页以上后完全交回 AOSP 原生逻辑，幂等且自限。
 * → {@link #hookPagingRebuild}</p>
 *
 * <p><b>④ XDF 的页数算法有 bug（是 bug 不是阉割）</b><br>
 * {@code emptyAndInflateOrRemovePages()} 里算页数的一句被改成了数学上不等价的写法：<br>
 * AOSP: {@code if (numTiles > maxTiles * newNumPages) newNumPages++;}<br>
 * XDF : {@code if (numTiles % maxTiles != 0) newNumPages++;}<br>
 * 两者仅在 {@code 0 < numTiles < maxTiles} 时不一致：6 个 tile、每页 10 个时，
 * AOSP 判 1 页（对），XDF 判 2 页（凭空多一页空页，划过去什么都没有）。<br>
 * 不改字节码，在 distributeTiles 填充【之后】裁掉尾部 {@code getChildCount()==0} 的页，
 * 并同步 ViewPager adapter 与分页圆点。只裁空页、不按公式重算 → 最保守，绝不误删有内容的页。
 * → {@link #hookPagingTrimEmptyPages}</p>
 *
 * <p><b>⑤ footer 前景太暗（铅笔 / 齿轮 / Build 版本号）</b><br>
 * MT2 核验三者均【非】XDF 阉割：铅笔 AlphaOptimizedImageView(0x01020003) 与齿轮
 * SettingsButton(0x7f0a0364) 的 tint 均为 {@code ?01010030}（= android:colorForeground，
 * 两侧同一 attr id=16）；Build TextView(0x7f0a00ce) 走 TextAppearance.QS.Status →
 * {@code dark_mode_qs_icon_color_single_tone = #b3000000}（70% 黑），两侧 targetVersion
 * 均 {@code da1d77763ef09bb9} 逐字节一致。{@code Theme.SystemUI} 两侧 12 个 item 逐条相同，
 * 唯一差异是 parent，而两者都不定义 colorForeground → 最终都落 framework 默认。<br>
 * 即：真机实测是【黑色】落在深色 footer 底上"看不清"，属原厂观感问题而非阉割，
 * 直接刷纯白对症。挂 {@code QSFooterImpl.onFinishInflate} 这一个点（该时机子 View 树
 * 已 inflate 完毕）遍历即可。<br>
 * ★ 精确匹配，绝不碰用户头像：MultiUserSwitch 内还有个普通 ImageView(0x7f0a0284)
 * 显示头像，一并刷白会变白块 → 只匹配 AlphaOptimizedImageView / SettingsButton /
 * TextView 三类。→ {@link #hookFooterStylingAndForeground}</p>
 *
 * <p><b>⑥ 编辑页(QSCustomizer)列数与主面板对齐 + tile 黑字</b><br>
 * MT2 实测两处硬编码必须同改：{@code new GridLayoutManager(ctx, 3)} 与
 * {@code TileAdapter$5.getSpanSize(int)} 对 header 写死 return 3。GridLayoutManager 会校验
 * {@code getSpanSize() > mSpanCount} 抛 IllegalArgumentException，只改一处必崩
 * （历史上"排版崩坏被 revert"即此因）。<br>
 * header 的 getSpanSize hook 挂在【运行时真实 mSpanSizeLookup 实例的类】上
 * （{@link #installSpanSizeHookOn}），不靠枚举匿名类去猜 —— 2026-10-01 实机踩坑：
 * 安装期用 getDeclaredClasses() 猜到的那个匿名类与被 setSpanSizeLookup 真正使用的实例
 * 不保证是同一个类，hook 根本没生效（现象：header 仍跨 3 列，同行右边又补了 2 个 tile）。
 * mSpanCount 则在 setCustomizing 入口改（不新 hook QSCustomizer.&lt;init&gt;：
 * 同一方法挂两个 hook 会静默失效，项目已踩过）。
 * → {@link #hookCustomizer}</p>
 *
 * <p>另：编辑页 tile 名称文字在 {@code qs_tile_label.xml} 里被 XDF 从 AOSP 的
 * {@code ?android:attr/textColorSecondary}（跟随主题）改成硬编码
 * {@code @android:color/white}，而 tile 背景 selectableItemBackground 在本机解析为
 * 不透明白 → 白字白底。→ 改回深色 {@code #FF212121}（同 ⑥ 挂载）。</p>
 *
 * <p>所有 hook 点由 {@code XDFHook.runGuarded} + 本类 guard 双重隔离，单点失败只降级该
 * 功能并记日志，绝不影响 SystemUI 启动；hooker 强引用由 {@code XDFHook.hook} 统一保活
 * （LibXposed PROTECTIVE 模式下 hook 挂不上不会抛给调用方，不记日志就彻底静默失效）。</p>
 */
final class QuickSettingsHooks {

    private static final String TAG = "qsfix";

    /* ==================== 类名常量 ==================== */

    private static final String CLS_PANEL_VIEW =
            "com.android.systemui.statusbar.phone.NotificationPanelView";
    private static final String CLS_TILE_LAYOUT = "com.android.systemui.qs.TileLayout";
    private static final String CLS_PAGED_TILE_LAYOUT = "com.android.systemui.qs.PagedTileLayout";
    private static final String CLS_FOOTER = "com.android.systemui.qs.QSFooterImpl";
    private static final String CLS_TILE_ADAPTER = "com.android.systemui.qs.customize.TileAdapter";
    private static final String CLS_TILE_ADAPTER_HOLDER =
            "com.android.systemui.qs.customize.TileAdapter$Holder";
    private static final String CLS_CUSTOMIZER = "com.android.systemui.qs.customize.QSCustomizer";
    /* ==================== 行为参数 ==================== */

    /* 行为参数全部来自 AppConfig（可在 App UI 中调整），不再硬编码常量。
     * 取值时统一走 clamp* —— SystemUI 里 mColumns/mMaxAllowedRows 直接进
     * TileLayout.onMeasure 的除法（mCellWidth = (宽-边距-列数*marginH)/列数），
     * 列数=0 会除零、列数过大格子宽度掉到 0 都会让布局崩/卡死 SystemUI，
     * 所以 UI 写入与此处读取两端都做钳制。 */

    /** 目标列数（AppConfig.clampQsColumns 保证 1..6） */
    private static volatile int sColumns = 5;
    /** 目标最大行数（AppConfig.clampQsMaxRows 保证 1..3） */
    private static volatile int sMaxRows = 2;
    /** footer 前景目标色：ARGB */
    private static volatile int sFooterFg = 0xFFFFFFFF;
    /** 编辑页 tile 名称目标色：ARGB */
    private static volatile int sTileTextDark = 0xFF212121;

    /**
     * QS 参数热刷新监听（2026-10 新增，静态强引用）。
     *
     * <p><b>要解决的坑</b>：本类把配置一次性拷进上面的静态字段
     * （{@code loadParams()}），而 {@code loadParams()} 只在 {@code hookAll()} —
     * 即进程启动的 {@code onPackageReady} — 调一次。于是配置虽然被 daemon 推送到了
     * 这个进程，却没人去读那些静态字段 → 用户改列数/行数完全没反应，必须重启 SystemUI。</p>
     *
     * <p><b>修复</b>：订阅这 4 个键，收到变更就重跑 {@code loadParams()}。
     * {@code hookTileLayout} 的 after hook 本就每次调用都会重读 {@code sColumns}/
     * {@code sMaxRows} 并回填字段（自愈），所以这里只需刷新数据源，
     * 等下一次系统自身的 layout 流程触发 {@code updateResources()} 即可生效。</p>
     *
     * <p><b>为什么不用热重载</b>：remote prefs 的 {@code getXxx} 只是
     * {@code HashMap.getOrDefault}（纯内存，无 binder/无 IO），一次 26 个键的
     * 哈希查找在 MT8788 上是微秒级；热重载却要重载模块 dex + 重跑
     * {@code onPackageReady} + 重装全部 hook，成本高几个数量级，且 system_server
     * 完全不支持。为这点开销去重载不划算。</p>
     *
     * <p><b>为什么必须 static 强引用</b>：历史教训 —— 曾出现「hook 启动有效、
     * 久后失效」，根因是 lambda/匿名实例被 GC 后 native 回调丢失，而 PROTECTIVE
     * 模式把异常静默吞掉，无从排查。故 listener 与所有 hooker 一律 static final 持有。</p>
     */
    private static final AppConfig.ConfigListener QS_PARAM_LISTENER =
            new AppConfig.ConfigListener() {
                @Override
                public void onConfigChanged(String key) {
                    try {
                        int before = sColumns;
                        loadParams();
                        XDFHook.logi(TAG, "qs params hot-reloaded on '" + key
                                + "': columns " + before + " -> " + sColumns
                                + ", maxRows=" + sMaxRows);
                    } catch (Throwable t) {
                        XDFHook.logw(TAG, "qs param reload failed: " + t);
                    }
                }
            };

    /** 各 hook 内部统一读这两个（避免读半新半旧的组合） */
    private static int columns() {
        return sColumns;
    }

    private static int maxRows() {
        return sMaxRows;
    }

    /* ==================== 内部状态 ==================== */

    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());

    private static final Set<String> sSpanHookedCls =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final ConcurrentHashMap<String, Object> sFieldCache =
            new ConcurrentHashMap<String, Object>();

    private QuickSettingsHooks() {
    }

    static void hookAll(ClassLoader cl) {
        // ★ 订阅 QS 参数变更（202-10）：本类把配置拷进静态字段（loadParams），
        //   若只在进程启动时调一次，配置推送到了却没人刷新 → 改了列数/行数没反应。
        //   收到这 4 个键变更就重新 loadParams()，配合 hookTileLayout 的 after hook
        //   自愈字段，无需重启 SystemUI。
        AppConfig.addConfigListener(QS_PARAM_LISTENER,
                AppConfig.K_QS_COLUMNS,
                AppConfig.K_QS_MAX_ROWS,
                AppConfig.K_QS_FOOTER_FG,
                AppConfig.K_QS_TILE_TEXT_DARK);

        if (!sHooked.add(cl)) {
            return;
        }
        loadParams();
        guard("qsExpansionEnabled", () -> hookQsExpansionEnabled(cl));
        guard("tileLayoutColumns", () -> hookTileLayout(cl));
        guard("pagingRebuild", () -> hookPagingRebuild(cl));
        guard("pagingTrimEmptyPages", () -> hookPagingTrimEmptyPages(cl));
        guard("footerForeground", () -> hookFooterStylingAndForeground(cl));
        guard("customizerColumnsAndTileText", () -> hookCustomizer(cl));
        XDFHook.logi(TAG, "all hooks installed");
    }

    /**
     * 从 AppConfig 载入可配置参数。
     *
     * <p>两端都做钳制：AppConfig 读取时用 clampQsColumns/clampQsMaxRows，
     * 这里再加一道 —— 因为 QS 布局参数一旦越界（列数进 TileLayout.onMeasure 的
     * 除法、行数进 distributeTiles 的分页判定）会让 SystemUI 布局崩甚至卡死，
     * 这类值宁可退回默认也不能带病注入。</p>
     */
    private static void loadParams() {
        int cols = 5, rows = 2, fg = 0xFFFFFFFF, tx = 0xFF212121;
        try {
            AppConfig cfg = AppConfig.refresh();
            cols = cfg.qsColumns;
            rows = cfg.qsMaxRows;
            fg = cfg.qsFooterFg;
            tx = cfg.qsTileTextDark;
        } catch (Throwable t) {
            XDFHook.logw(TAG, "cfg load failed, fallback default: " + t);
        }
        // 防御式钳制：与 AppConfig 的取值域保持一致
        if (cols < 1 || cols > 6) cols = 5;
        if (rows < 1 || rows > 3) rows = 2;
        sColumns = cols;
        sMaxRows = rows;
        sFooterFg = fg;
        sTileTextDark = tx;
        XDFHook.logi(TAG, "params: columns=" + cols + " maxRows=" + rows
                + " footerFg=" + hex(fg) + " tileText=" + hex(tx));
    }

    private static String hex(int c) {
        return String.format("#%08X", c);
    }

    private interface HookStep {
        void run() throws Exception;
    }

    private static void guard(String name, HookStep step) {
        try {
            step.run();
        } catch (Throwable t) {
            XDFHook.logw(TAG, "hook FAILED: " + name + " -> " + t);
        }
    }

    /* ==================== ① QS 展开手势总开关 ==================== */

    private static void hookQsExpansionEnabled(ClassLoader cl) throws Exception {
        Method m = Reflect.findDeclared(Reflect.findClass(CLS_PANEL_VIEW, cl),
                "setQsExpansionEnabled", new Class<?>[]{boolean.class});
        m.setAccessible(true);
        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> {
            Object arg = chain.getArg(0);
            if (arg instanceof Boolean && !((Boolean) arg)) {
                return chain.proceed(new Object[]{Boolean.TRUE});
            }
            return chain.proceed();
        });
    }

    /* ==================== ② 列数 / 行数 ==================== */

    private static void hookTileLayout(ClassLoader cl) throws Exception {
        Class<?> tileLayout = Reflect.findClass(CLS_TILE_LAYOUT, cl);
        Method ur = Reflect.findDeclared(tileLayout, "updateResources", new Class<?>[0]);
        ur.setAccessible(true);
        XDFHook.deopt(ur);
        XDFHook.hook(ur, chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            try {
                boolean changed = false;
                if (getInt(self, "mColumns", tileLayout) != columns()) {
                    setInt(self, "mColumns", columns(), tileLayout);
                    changed = true;
                }
                if (getInt(self, "mMaxAllowedRows", tileLayout) != maxRows()) {
                    setInt(self, "mMaxAllowedRows", maxRows(), tileLayout);
                    changed = true;
                }
                if (changed && self instanceof View) {
                    ((View) self).requestLayout();
                }
            } catch (Throwable t) {
                XDFHook.logw(TAG, "updateResources field write failed: " + t);
            }
            return result;
        });
    }
    /* ==================== ③ 强制重建分页 ==================== */

    private static void hookPagingRebuild(ClassLoader cl) throws Exception {
        Class<?> ptl = Reflect.findClass(CLS_PAGED_TILE_LAYOUT, cl);
        Method om = Reflect.findDeclared(ptl, "onMeasure",
                new Class<?>[]{int.class, int.class});
        om.setAccessible(true);
        XDFHook.deopt(om);
        XDFHook.hook(om, chain -> {
            Object self = chain.getThisObject();
            try {
                List<?> pages = (List<?>) getField(self, "mPages", ptl);
                if (pages == null || pages.size() <= 1) {
                    setInt(self, "mLastMaxHeight", Integer.MIN_VALUE / 2, ptl);
                }
            } catch (Throwable t) {
                try {
                    setInt(self, "mLastMaxHeight", Integer.MIN_VALUE / 2, ptl);
                } catch (Throwable ignored) {
                }
            }
            return chain.proceed();
        });
    }

    /* ==================== ④ 裁掉多出来的尾部空页 ==================== */

    private static void hookPagingTrimEmptyPages(ClassLoader cl) throws Exception {
        Class<?> ptl = Reflect.findClass(CLS_PAGED_TILE_LAYOUT, cl);
        Method dt = Reflect.findDeclared(ptl, "distributeTiles", new Class<?>[0]);
        dt.setAccessible(true);
        XDFHook.deopt(dt);
        XDFHook.hook(dt, chain -> {
            Object r = chain.proceed();
            try {
                Object self = chain.getThisObject();
                List<?> pages = (List<?>) getField(self, "mPages", ptl);
                if (pages == null || pages.size() <= 1) {
                    return r;
                }
                int removed = 0;
                while (pages.size() > 1) {
                    Object last = pages.get(pages.size() - 1);
                    if (!(last instanceof ViewGroup)
                            || ((ViewGroup) last).getChildCount() != 0) {
                        break;
                    }
                    pages.remove(pages.size() - 1);
                    removed++;
                }
                if (removed > 0) {
                    notifyPagerChanged(self, ptl, pages.size());
                    XDFHook.logd(TAG, "trimmed " + removed + " empty page(s), left "
                            + pages.size());
                }
            } catch (Throwable t) {
                XDFHook.logw(TAG, "trim empty pages failed: " + t);
            }
            return r;
        });
    }

    /** 裁页后通知 ViewPager adapter 与分页圆点重新数页（任一步失败都忽略） */
    private static void notifyPagerChanged(Object self, Class<?> ptl, int pageCount) {
        try {
            Object adapter = getField(self, "mAdapter", ptl);
            if (adapter != null) {
                adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
            }
        } catch (Throwable ignored) {
        }
        try {
            Object pi = getField(self, "mPageIndicator", ptl);
            if (pi != null) {
                pi.getClass().getMethod("setNumPages", int.class).invoke(pi, pageCount);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (self instanceof View) {
                ((View) self).requestLayout();
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== ⑤ footer 前景刷白 ==================== */

    private static void hookFooterStylingAndForeground(ClassLoader cl) throws Exception {
        Class<?> footer = Reflect.findClass(CLS_FOOTER, cl);
        Method m = Reflect.findDeclared(footer, "onFinishInflate", new Class<?>[0]);
        m.setAccessible(true);
        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> {
            Object r = chain.proceed();
            try {
                Object self = chain.getThisObject();
                if (self instanceof ViewGroup) {
                    int n = whitenFooterForeground((ViewGroup) self, 0);
                    XDFHook.logd(TAG, "footer foreground whitened: " + n + " view(s)");
                }
            } catch (Throwable t) {
                XDFHook.logw(TAG, "whiten footer failed: " + t);
            }
            return r;
        });
    }

    /** 铅笔 / 齿轮 / Build 文本刷纯白，返回本次刷白个数 */
    private static int whitenFooterForeground(ViewGroup root, int depth) {
        if (root == null || depth > 3) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c == null) {
                continue;
            }
            String cn = c.getClass().getName();
            boolean isPencil = cn.endsWith("AlphaOptimizedImageView");
            boolean isGear = cn.endsWith("SettingsButton");
            if (isPencil || isGear) {
                try {
                    ImageView iv = (ImageView) c;
                    iv.setColorFilter(null);
                    iv.setImageTintList(ColorStateList.valueOf(sFooterFg));
                    n++;
                } catch (Throwable ignored) {
                }
            } else if (c instanceof TextView) {
                try {
                    ((TextView) c).setTextColor(sFooterFg);
                    n++;
                } catch (Throwable ignored) {
                }
            } else if (c instanceof ViewGroup) {
                n += whitenFooterForeground((ViewGroup) c, depth + 1);
            }
        }
        return n;
    }
    /* ==================== ⑥ 编辑页：列数对齐 + tile 黑字 ==================== */

    private static void hookCustomizer(ClassLoader cl) throws Exception {
        Class<?> holder = Reflect.findClass(CLS_TILE_ADAPTER_HOLDER, cl);
        XDFHook.hookAllByName(cl, CLS_TILE_ADAPTER, "onBindViewHolder", chain -> {
            Object r = chain.proceed();
            try {
                Object h = chain.getArg(0);
                Object tv = getField(h, "mTileView", holder);
                if (tv instanceof View) {
                    darkenTileText((View) tv, 0);
                }
            } catch (Throwable ignored) {
            }
            return r;
        }, "qs customizer tile text");

        Class<?> cz = Reflect.findClass(CLS_CUSTOMIZER, cl);
        Method sc = Reflect.findDeclared(cz, "setCustomizing", new Class<?>[]{boolean.class});
        sc.setAccessible(true);
        XDFHook.deopt(sc);
        XDFHook.hook(sc, chain -> {
            Object r = chain.proceed();
            try {
                Object self = chain.getThisObject();
                if (self != null) {
                    alignCustomizerColumns(self, cz);
                    paintCustomizerTiles(self, cz);
                }
            } catch (Throwable t) {
                XDFHook.logw(TAG, "customizer align failed: " + t);
            }
            return r;
        });
    }

    /** 编辑页 GridLayoutManager.mSpanCount 对齐主面板列数 */
    private static void alignCustomizerColumns(Object customizer, Class<?> cz) {
        try {
            Object rv = getField(customizer, "mRecyclerView", cz);
            if (rv == null) {
                return;
            }
            Object lm = rv.getClass().getMethod("getLayoutManager").invoke(rv);
            if (lm == null) {
                return;
            }
            Class<?> lmCls = lm.getClass();
            try {
                Object ssl = getField(lm, "mSpanSizeLookup", lmCls);
                if (ssl != null) {
                    installSpanSizeHookOn(ssl.getClass());
                }
            } catch (Throwable ignored) {
            }
            if (getInt(lm, "mSpanCount", lmCls) != columns()) {
                setInt(lm, "mSpanCount", columns(), lmCls);
                ((View) rv).requestLayout();
                XDFHook.logd(TAG, "customizer spanCount -> " + columns());
            }
        } catch (Throwable t) {
            XDFHook.logw(TAG, "alignCustomizerColumns: " + t);
        }
    }

    /** 编辑页 header 跨列数跟随主面板配置列数（只改 header，普通 tile 仍占 1 格） */
    private static void installSpanSizeHookOn(Class<?> sslCls) {
        if (sslCls == null) {
            return;
        }
        String key = sslCls.getName();
        if (!sSpanHookedCls.add(key)) {
            return;
        }
        try {
            Method gss = sslCls.getDeclaredMethod("getSpanSize", int.class);
            gss.setAccessible(true);
            XDFHook.deopt(gss);
            XDFHook.hook(gss, chain -> {
                Object r = chain.proceed();
                if (r instanceof Integer && ((Integer) r).intValue() > 1) {
                    return columns();
                }
                return r;
            });
            XDFHook.logd(TAG, "customizer span hook installed on " + key);
        } catch (Throwable t) {
            sSpanHookedCls.remove(key);
            XDFHook.logw(TAG, "customizer span hook FAILED on " + key + ": " + t);
        }
    }

    /** 编辑页显示后，把已创建但尚未重新 bind 的存量 item 也补一遍 */
    private static void paintCustomizerTiles(Object customizer, Class<?> cz) {
        try {
            Object rv = getField(customizer, "mRecyclerView", cz);
            if (!(rv instanceof ViewGroup)) {
                return;
            }
            ViewGroup list = (ViewGroup) rv;
            for (int i = 0; i < list.getChildCount(); i++) {
                View item = list.getChildAt(i);
                if (!(item instanceof ViewGroup)) {
                    continue;
                }
                ViewGroup ig = (ViewGroup) item;
                for (int j = 0; j < ig.getChildCount(); j++) {
                    View t = ig.getChildAt(j);
                    if (t.getClass().getName().endsWith("CustomizeTileView")) {
                        darkenTileText(t, 0);
                    }
                }
            }
        } catch (Throwable t) {
            XDFHook.logw(TAG, "paintCustomizerTiles: " + t);
        }
    }

    /** 递归把 tile 内可见 TextView 改成深色（depth 限 5 层） */
    private static void darkenTileText(View v, int depth) {
        if (v == null || depth > 5) {
            return;
        }
        try {
            if (v instanceof TextView && v.getVisibility() == View.VISIBLE) {
                TextView tv = (TextView) v;
                if (tv.getCurrentTextColor() != sTileTextDark) {
                    tv.setTextColor(sTileTextDark);
                }
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                darkenTileText(vg.getChildAt(i), depth + 1);
            }
        }
    }

    /* ==================== 字段工具（带缓存） ==================== */

    private static Field resolveField(String name, Class<?> cls) throws NoSuchFieldException {
        String key = cls.getName() + "#" + name;
        Object cached = sFieldCache.get(key);
        if (cached instanceof Field) {
            return (Field) cached;
        }
        if (cached instanceof NoSuchFieldException) {
            throw (NoSuchFieldException) cached;
        }
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                sFieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        NoSuchFieldException e = new NoSuchFieldException(cls.getName() + "#" + name);
        sFieldCache.put(key, e);
        throw e;
    }

    private static Object getField(Object obj, String name, Class<?> cls) throws Exception {
        return resolveField(name, cls).get(obj);
    }

    private static int getInt(Object obj, String name, Class<?> cls) throws Exception {
        return ((Number) resolveField(name, cls).get(obj)).intValue();
    }

    private static void setInt(Object obj, String name, int v, Class<?> cls) throws Exception {
        resolveField(name, cls).setInt(obj, v);
    }
}
