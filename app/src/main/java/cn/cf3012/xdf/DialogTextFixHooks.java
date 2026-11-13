package cn.cf3012.xdf;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DialogTextFixHooks — 修复「深色主题 App 的 AlertDialog 变成白底白字」。
 *
 * <p>开关：{@link AppConfig#K_DIALOG_FIX_APPS}（UI「总览 → 弹窗文字修复」里勾选的目标应用，
 * 默认空 = 不注入任何 App）。只注入 App 进程，**不进 system_server**；重新打开目标 App 生效。</p>
 *
 * <h3>根因（2026-11-13 设备实测，非推断）</h3>
 *
 * <p>XDF 在 framework-res 里给 <b>ThemeOverlay</b>
 * {@code ThemeOverlay.Material.Dialog.Alert} 加了一条 AOSP 原本没有的覆盖：</p>
 *
 * <pre>
 * &lt;style name="ThemeOverlay.Material.Dialog.Alert"
 *        parent="ThemeOverlay.Material.Dialog"&gt;
 *     &lt;item name="android:windowBackground"&gt;@drawable/bg_white_rect_corner_16&lt;/item&gt;
 *     &lt;item name="android:windowMinWidthMajor"&gt;...&lt;/item&gt;
 *     &lt;item name="android:windowMinWidthMinor"&gt;...&lt;/item&gt;
 * &lt;/style&gt;
 *
 * &lt;!-- res/drawable/bg_white_rect_corner_16.xml：写死纯白 + 16dp 圆角，无 inset --&gt;
 * &lt;shape android:shape="rectangle"&gt;
 *     &lt;solid android:color="@android:color/white"/&gt;
 *     &lt;corners android:radius="16dp"/&gt;
 * &lt;/shape&gt;
 * </pre>
 *
 * <p>{@code alertDialogTheme} 只是个 <b>ThemeOverlay</b>：<b>只覆盖背景，不带任何文字色</b>。
 * 而 {@code new AlertDialog.Builder(activity)} 等价于
 * {@code ContextThemeWrapper(activity, activity.theme.alertDialogTheme)}，于是：</p>
 *
 * <ul>
 *   <li><b>背景</b> ← 被 overlay 写死为 <b>#FFFFFF</b>（永远白，与明暗无关）</li>
 *   <li><b>文字</b> ← 仍从 Activity 主题继承
 *       （{@code ?attr/textColorPrimary} / {@code ?attr/textColorAlertDialogListItem}）</li>
 * </ul>
 *
 * <p>⇒ <b>Activity 主题是深色</b>的 App（{@code Theme.Material}、{@code Theme.DeviceDefault}）
 * 得到「白底 + 白字」：标题/正文/单选列表全部隐形；按钮走 {@code ?attr/colorAccent}
 * （深色下 #80CBC4 浅青）反而看得见 —— 现场表现就是「按钮正常、其它文字全废」。</p>
 *
 * <h3>修复策略：改背景，不改文字</h3>
 *
 * <p>判据是 <b>资源 id 精确匹配</b>，不是猜颜色：{@code resolve(?attr/windowBackground)}
 * 得到的 resId 等于 XDF 那个 {@code bg_white_rect_corner_16} 时才动手（resId 通过
 * {@code Resources.getIdentifier(name, "drawable", "android")} 动态解析并缓存，
 * 不硬编码 id，避免 ROM 重编后 id 漂移）。</p>
 *
 * <p>命中后用 {@link GradientDrawable} 重建同外形（满窗口 + {@code ?attr/dialogCornerRadius}
 * 圆角）、但颜色取 <b>{@code ?attr/colorBackground}</b> 的背景 —— 在 dialog 主题里它就是
 * {@code ?attr/colorBackgroundFloating}：</p>
 *
 * <pre>
 * App 主题                    修复前（XDF 白板）    修复后（跟随主题）
 * Theme.Material.Light       #FFFFFF + 黑字        #FFFFFF + 黑字（无变化）
 * Theme.Material (dark)      #FFFFFF + 白字 ✗      #424242 + 白字 ✓ 恢复原设计
 * </pre>
 *
 * <p><b>为什么不在 show 之后做</b>：{@code Dialog.show()} 内部顺序是
 * {@code dispatchOnCreate → onCreate（installDecor/应用 windowBackground）→ addView}。
 * 本 hook 在 {@code chain.proceed()} <b>之前</b>就设置好窗口背景，decor 生成时直接带上，
 * 因此<b>不会出现白底闪一帧</b>。</p>
 *
 * <h3>兜底（只在中招但改背景失败时启用）</h3>
 * <p>若确实命中白板、但重建背景抛异常，则退回「改文字」，并用 <b>WCAG 对比度</b>
 * 而不是简单亮度判断：只有 {@code contrast(text, bg) < 2.0}（几乎不可读）才改，
 * 颜色按背景明暗取 {@code #DE000000} / {@code #DEFFFFFF}。这样蓝色链接、品牌色强调
 * 等「对比度够」的彩色文字不会被误刷黑（旧版按 luma>0.4 判断会把 #3482FF、#9192FF
 * 一并刷黑，属误伤）。文字修复同样跳过 {@link Button}（颜色来自 colorAccent）与
 * {@link EditText}（自带背景/占位色）。</p>
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>只注入普通 App 进程，不碰 system_server。</li>
 *   <li>PROTECTIVE + hooker 强引用保活（见 {@link XDFHook#hook}）：任何异常最多让本次修复
 *       跳过，绝不影响宿主弹窗本身。</li>
 *   <li>只在「主题确实解析到 XDF 白板」时动手；App 自己覆盖了 windowBackground 的
 *       dialog（resId 不是白板）一律不碰。</li>
 *   <li>同一 Window 只处理一次（{@link WeakHashMap}），dialog 复用/反复 show 不重复重建。</li>
 * </ul>
 */
final class DialogTextFixHooks {

    private static final String TAG = "DialogTextFix";

    /** XDF 的写死白板 drawable 名（framework-res，res/drawable/） */
    private static final String XDF_WHITE_PLATE = "bg_white_rect_corner_16";
    /** 兜底文字修复的 WCAG 对比度阈值：低于此值视为不可读 */
    private static final double MIN_CONTRAST = 2.0;
    /** 兜底时的浅底深字 / 深底浅字 */
    private static final int FIX_ON_LIGHT = 0xDE000000;
    private static final int FIX_ON_DARK = 0xDEFFFFFF;
    private static final int FIX_DISABLED = 0x61000000;

    /** 动态解析并缓存的 XDF 白板 resId（0 = 尚未解析/不存在） */
    private static volatile int sWhitePlateId = 0;
    private static volatile boolean sWhitePlateResolved = false;

    /** 已处理过的窗口（WeakHashMap：dialog 销毁后自动释放） */
    private static final Map<Window, Boolean> sFixedWindows =
            Collections.synchronizedMap(new WeakHashMap<Window, Boolean>());

    private static final AtomicInteger sFixedDialogs = new AtomicInteger();
    private static volatile boolean sLoggedDetail = false;

    private DialogTextFixHooks() {
    }

    /**
     * App 进程入口（{@link XDFHook#onPackageReady} 调用）。
     *
     * @param cl  宿主 ClassLoader（Dialog 在 boot classloader，能解析即可）
     * @param pkg 宿主包名（仅日志用）
     */
    static void hookAll(ClassLoader cl, String pkg) {
        try {
            Class<?> dialogCls = Class.forName("android.app.Dialog", false, cl);
            Method show = dialogCls.getDeclaredMethod("show");
            show.setAccessible(true);
            XDFHook.deopt(show);
            XDFHook.hook(show, chain -> {
                final Dialog dialog = (Dialog) chain.getThisObject();
                // ★ 在 proceed() 之前设背景：decor 尚未 install，PhoneWindow 会把它
                //   保存在 mBackgroundDrawable，installDecor 时直接应用 → 无闪白。
                int state = STATE_SKIPPED;
                try {
                    state = fixBackground(dialog);
                } catch (Throwable ignored) {
                }
                Object ret = chain.proceed();
                // 兜底：命中白板但改背景失败时，才按 WCAG 对比度修文字
                if (state == STATE_FAILED) {
                    try {
                        fallbackFixText(dialog);
                    } catch (Throwable ignored) {
                    }
                }
                return ret;
            });
            XDFHook.logi(TAG, "hooked: android.app.Dialog#show()V @ " + pkg);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "FAILED hook Dialog#show in " + pkg + ": " + t);
        }
    }

    /* ==================== 主路径：改背景 ==================== */

    private static final int STATE_SKIPPED = 0;   // 不是 XDF 白板（或已处理）→ 不动
    private static final int STATE_FIXED = 1;     // 已把背景换成主题色
    private static final int STATE_FAILED = 2;    // 命中白板但重建失败 → 走文字兜底

    private static int fixBackground(Dialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) {
            return STATE_SKIPPED;
        }
        if (sFixedWindows.containsKey(w)) {
            return STATE_FIXED;   // 同一窗口已处理
        }
        Context ctx = dialog.getContext();
        if (ctx == null) {
            return STATE_SKIPPED;
        }
        Drawable current;
        int curId;
        try {
            TypedValue tv = new TypedValue();
            if (!ctx.getTheme().resolveAttribute(android.R.attr.windowBackground, tv, true)) {
                return STATE_SKIPPED;
            }
            curId = tv.resourceId != 0 ? tv.resourceId : tv.data;
            int plateId = whitePlateId(ctx);
            if (plateId == 0 || curId != plateId) {
                // 不是 XDF 那个白板：说明这个 App 自己有主题/背景设计，绝不插手
                return STATE_SKIPPED;
            }
            current = ctx.getDrawable(curId);
        } catch (Throwable t) {
            return STATE_SKIPPED;
        }

        try {
            int bg = resolveColor(ctx, android.R.attr.colorBackground);
            if (bg == 0) {
                return STATE_FAILED;
            }
            float radiusPx = resolveCornerRadiusPx(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setColor(bg);
            if (radiusPx > 0f) {
                g.setCornerRadius(radiusPx);
            }
            w.setBackgroundDrawable(g);
            sFixedWindows.put(w, Boolean.TRUE);

            int total = sFixedDialogs.incrementAndGet();
            if (!sLoggedDetail) {
                sLoggedDetail = true;
                XDFHook.logi(TAG, "命中 XDF 白板：" + describe(curId)
                        + " → 换成主题背景 " + hex(bg)
                        + "（圆角 " + radiusPx + "px, dialog=" + dialog.getClass().getName()
                        + (current != null ? ", 原 drawable=" + current.getClass().getSimpleName() : "")
                        + "）");
            } else if ((total & 0x0F) == 0) {
                XDFHook.logd(TAG, "累计修复 dialog " + total + " 个");
            }
            return STATE_FIXED;
        } catch (Throwable t) {
            XDFHook.logw(TAG, "改背景失败，退回文字兜底：" + t);
            return STATE_FAILED;
        }
    }

    /* ==================== 兜底：按 WCAG 对比度修文字 ==================== */

    private static void fallbackFixText(Dialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        View decor = w.getDecorView();
        Context ctx = dialog.getContext();
        if (decor == null || ctx == null) {
            return;
        }
        int bg = resolveColor(ctx, android.R.attr.colorBackground);
        if (bg == 0) {
            return;
        }
        boolean lightBg = wcagLum(bg) > 0.5;
        // 兜底时也要等列表项建出来：post 一次足够（背景此时已是 App 原样）
        final int bgFinal = bg;
        final int fg = lightBg ? FIX_ON_LIGHT : FIX_ON_DARK;
        decor.post(() -> {
            try {
                int n = fixTreeFallback(decor, bgFinal, fg);
                if (n > 0) {
                    XDFHook.logd(TAG, "兜底修正 " + n + " 个不可读文字 → " + hex(fg)
                            + "（背景 " + hex(bgFinal) + "）");
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static int fixTreeFallback(View v, int bg, int fg) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Button || v instanceof EditText) {
            return 0;
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            int cur;
            try {
                cur = tv.getCurrentTextColor();
            } catch (Throwable t) {
                return 0;
            }
            if (contrastRatio(compositeOver(cur, bg), bg) >= MIN_CONTRAST) {
                return 0;   // 对比度够（含彩色强调文字）→ 不碰
            }
            try {
                int disabled = fg == FIX_ON_LIGHT ? FIX_DISABLED : 0x61FFFFFF;
                tv.setTextColor(new ColorStateList(
                        new int[][]{
                                new int[]{-android.R.attr.state_enabled},
                                new int[]{}
                        },
                        new int[]{disabled, fg}));
                return 1;
            } catch (Throwable t) {
                return 0;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += fixTreeFallback(g.getChildAt(i), bg, fg);
            }
            return n;
        }
        return 0;
    }

    /* ==================== 资源解析 ==================== */

    /** XDF 白板 drawable 的 resId（动态解析，缓存；不存在返回 0） */
    private static int whitePlateId(Context ctx) {
        if (sWhitePlateResolved) {
            return sWhitePlateId;
        }
        synchronized (DialogTextFixHooks.class) {
            if (sWhitePlateResolved) {
                return sWhitePlateId;
            }
            int id = 0;
            try {
                Resources r = ctx.getResources();
                id = r.getIdentifier(XDF_WHITE_PLATE, "drawable", "android");
            } catch (Throwable ignored) {
            }
            sWhitePlateId = id;
            sWhitePlateResolved = true;
            if (id == 0) {
                XDFHook.logw(TAG, "framework 里找不到 " + XDF_WHITE_PLATE
                        + "（ROM 可能已变），本单元不动作");
            }
            return id;
        }
    }

    private static int resolveColor(Context ctx, int attr) {
        try {
            TypedValue tv = new TypedValue();
            if (!ctx.getTheme().resolveAttribute(attr, tv, true)) {
                return 0;
            }
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            int resId = tv.resourceId != 0 ? tv.resourceId : tv.data;
            if (resId == 0) {
                return 0;
            }
            ColorStateList csl = ctx.getResources().getColorStateList(resId, ctx.getTheme());
            return csl != null ? csl.getDefaultColor() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 圆角半径（px）：取 ?attr/dialogCornerRadius，取不到退回 16dp */
    private static float resolveCornerRadiusPx(Context ctx) {
        float dp = 16f;
        try {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.dialogCornerRadius, tv, true)
                    && tv.type == TypedValue.TYPE_DIMENSION) {
                dp = TypedValue.complexToFloat(tv.data);
            }
        } catch (Throwable ignored) {
        }
        float density = 1f;
        try {
            density = ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
        }
        return dp * density;
    }

    /* ==================== 颜色工具 ==================== */

    /** WCAG 相对亮度（sRGB 线性化，忽略 alpha） */
    private static double wcagLum(int c) {
        return 0.2126 * lin(Color.red(c) / 255.0)
                + 0.7152 * lin(Color.green(c) / 255.0)
                + 0.0722 * lin(Color.blue(c) / 255.0);
    }

    private static double lin(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG 对比度（1.0 ~ 21.0） */
    private static double contrastRatio(int fg, int bg) {
        double l1 = wcagLum(fg);
        double l2 = wcagLum(bg);
        if (l1 < l2) {
            double t = l1;
            l1 = l2;
            l2 = t;
        }
        return (l1 + 0.05) / (l2 + 0.05);
    }

    /** 半透明前景色叠在背景上之后的实际颜色（判断对比度前必须合成） */
    private static int compositeOver(int fg, int bg) {
        int a = Color.alpha(fg);
        if (a == 255) {
            return fg;
        }
        if (a == 0) {
            return bg;
        }
        float af = a / 255f;
        return Color.argb(255,
                Math.round(Color.red(fg) * af + Color.red(bg) * (1 - af)),
                Math.round(Color.green(fg) * af + Color.green(bg) * (1 - af)),
                Math.round(Color.blue(fg) * af + Color.blue(bg) * (1 - af)));
    }

    private static String describe(int resId) {
        return String.format("drawable#0x%08X", resId);
    }

    private static String hex(int c) {
        return String.format("#%08X", c);
    }
}
