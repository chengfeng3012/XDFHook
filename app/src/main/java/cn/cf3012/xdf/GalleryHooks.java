package cn.cf3012.xdf;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GalleryHooks — 恢复 XDF 定制相册 (com.android.gallery3d v2.0.0) 的图片「编辑」入口。
 *
 * <h3>★ ROM 的真实情况（MT2 逐方法实测 XDFAPP_XdfGallery.apk，2026-10-18）</h3>
 *
 * <p>编辑入口有两条链路，<b>两条链路的判定逻辑 ROM 都一字未改</b>：</p>
 *
 * <ol>
 *   <li><b>底部控制条</b>（XDF 定制的 {@code XdfPhotoPageControls}）
 * <pre>
 * PhotoPage.canDisplayBottomControl(int id) {
 *     boolean v0 = mShowBars &amp;&amp; !mReadOnlyView &amp;&amp; !mPhotoView.getFilmMode();
 *     if (id == 0x7f090327) return v0 &amp;&amp; mediaType == VIDEO;
 *     if (id == 0x7f09032a)                        // ← 编辑按钮
 *         return mHaveImageEditor &amp;&amp; v0 &amp;&amp; (ops &amp; 0x200) != 0 &amp;&amp; mediaType == IMAGE;
 *     // 其余按钮走 IBottomControl 扩展链
 * }</pre>
 *       ★ 而 {@code XdfPhotoPageControls.setup()} 只把 <b>0x7f090326/327/328/329</b>
 *       注册进 {@code mControlsVisible}，<b>唯独漏了编辑按钮 0x7f09032a</b> —— 于是
 *       {@code refresh()} 从不遍历它，它永远停在布局默认的 GONE。
 *       <b>这才是真正的阉割点（唯一的一处）。</b></li>
 *   <li><b>ActionBar 菜单</b>：{@code PhotoPage.onCreateActionBar(Menu)} →
 *       {@code mHaveImageEditor = GalleryUtils.isEditorAvailable(ctx, "image/*")} →
 *       {@code updateMenuOperations()} → {@code MenuExecutor.updateMenuOperation(menu, ops)}。
 *       链路同样完整，且 {@code updateMenuOperations()} 里带
 *       {@code if (mReadOnlyView) ops &amp;= ~0x200} 的只读保护。</li>
 * </ol>
 *
 * <h3>★ 本轮为何从 10 个 hook 精简到 2 个</h3>
 *
 * <p>原实现为了"确保按钮出现"，对上述判定做了大量<b>无条件强制</b>，结果是
 * <b>按钮确实出现、但把 ROM 的语义判坏</b>（用户反馈：能实现效果，但修坏了一个地方）：</p>
 * <ul>
 *   <li>{@code canDisplayBottomControl(编辑id)} 直接 return true —— 一次丢掉
 *       {@code mShowBars / !mReadOnlyView / !filmMode / mHaveImageEditor /
 *       SUPPORT_EDIT / mediaType==IMAGE} 全部前提
 *       ⇒ <b>视频页、胶片模式、只读页也会冒出「编辑」按钮</b>
 *       （点视频的"编辑"会去启动图片编辑器）。</li>
 *   <li>{@code XdfPhotoPageControls.refresh()} after 无差别 {@code setVisibility(VISIBLE)}
 *       —— 连 {@code mContainerVisible} 门控与 {@code mControlsVisible} 状态机一起绕过。</li>
 *   <li>{@code canDisplayBottomControls()}（容器级）非胶片模式恒 true —— 丢掉
 *       {@code mIsActive / !canUndo() / mShowBars} 与 IBottomControl 扩展链
 *       ⇒ 撤销态、页面非激活时控制条不隐藏。</li>
 *   <li>{@code PhotoPage.onCreate} 强制 {@code mReadOnlyView = false}、
 *       {@code GalleryActivity.startViewAction} 抹掉 intent 的 read-only extra
 *       ⇒ <b>只读图片变成可编辑</b>。</li>
 *   <li>菜单侧 {@code updateMenuOperation} / {@code updateMenuOperations} /
 *       {@code updateSupportedMenuEnabled} / {@code onPrepareOptionsMenu} 强制加
 *       SUPPORT_EDIT 位并 setVisible —— 会把 {@code updateMenuOperations()} 里的
 *       只读保护反向撤销（等价于第二处"只读失效"）。</li>
 *   <li>{@code MediaObject.getSupportedOperations() | 0x200} —— 不必要：
 *       {@code LocalImage.getSupportedOperations()} 对常见图片（region-decoder 可解）
 *       返回 {@code 0x2066d} / {@code 0x2062d}，<b>本就含 0x200</b>。</li>
 * </ul>
 *
 * <p>⇒ 只需保留两处「补齐 ROM 缺失声明」的最小修复，按钮就会在
 * <b>正确条件下</b>出现（图片 + 非只读 + 非胶片 + 控制条可见 + 有编辑器）：</p>
 * <ol>
 *   <li>{@link #hookXdfControlsSetup} —— 把编辑按钮重新注册进 {@code mControlsVisible}，
 *       让 {@code refresh()} 恢复管理它（<b>根因修复</b>，⑥c）。</li>
 *   <li>{@link #hookIsEditorAvailable} —— {@code GalleryUtils.isEditorAvailable()} 查的是
 *       "系统里有没有能处理 {@code ACTION_EDIT + image/*} 的 Activity"。学习机 ROM
 *       未预置图片编辑器时它恒为 false ⇒ {@code mHaveImageEditor=false} ⇒ 按钮与菜单项
 *       都不会出现。置 true 后，<b>是否显示仍由上面那 6 个条件正常决定</b>（⑨）。</li>
 * </ol>
 *
 * <p>开关：{@link AppConfig#H_GALLERY_EDIT}（scope: com.android.gallery3d）。
 * 每个 hook 点独立 guard，失败只降级该点并记日志，绝不影响相册启动。</p>
 */
final class GalleryHooks {

    private static final String TAG = "gallery";

    private static final String CLS_XDF_CONTROLS = "com.android.gallery3d.app.XdfPhotoPageControls";
    private static final String CLS_GALLERY_UTILS = "com.android.gallery3d.util.GalleryUtils";

    /** 底部控制条里的「编辑」按钮 id（res/N9.xml，XdfPhotoPageControls 管理） */
    private static final int ID_EDIT_BOTTOM = 0x7f09032a;

    /** 同一进程内防重复注册 */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());

    private GalleryHooks() {
    }

    static void hookAll(ClassLoader cl) {
        if (!sHooked.add(cl)) {
            return;
        }
        // 类名定位（失败 = 该进程没有相册代码，直接结束）
        Class<?> xdfControls;
        try {
            xdfControls = Reflect.findClass(CLS_XDF_CONTROLS, cl);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "gallery classes not found: " + t);
            return;
        }
        // ---- 仅两处修复：补齐 ROM 缺失的声明，不改任何判定逻辑 ----
        // ⑥c 根因：编辑按钮没被 setup() 注册进 mControlsVisible
        guard("xdfControlsSetup", () -> hookXdfControlsSetup(cl, xdfControls));
        // ⑨ 编辑器可用性（设备无 ACTION_EDIT 处理器时该检查恒 false）
        guard("isEditorAvailable", () -> hookIsEditorAvailable(cl));

        XDFHook.logi(TAG, "gallery hooks installed (2 minimal fixes: "
                + "re-register edit button + editor availability)");
    }

    /** 单点容错：失败记日志，绝不连累其它 hook 点 */
    private interface HookStep {
        void run() throws Exception;
    }

    private static void guard(String name, HookStep step) {
        try {
            step.run();
            XDFHook.logi(TAG, "hook ok: " + name);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "hook FAILED: " + name + " -> " + t);
        }
    }

    /* ==================== ⑥c 根因修复：重新注册编辑按钮 ==================== */

    /**
     * XDF 在 {@code setup()} 里把编辑按钮 0x7f09032a 从管理列表 {@code mControlsVisible}
     * 中除名（{@code initChildView} 只注册了 0x7f090326/327/328/329 四个），
     * 而 {@code refresh()} 只遍历该 map —— 编辑按钮因此永不更新，恒为布局默认的 GONE。
     *
     * <p>修复：{@code setup} after 把编辑按钮重新注册（绑点击监听 + 放进 map 初始 false），
     * 让 {@code refresh()} 恢复对它管理。此后按钮可见性完全由 ROM 的
     * {@code canDisplayBottomControl()} 正常决定 —— 本类不再做任何强制。</p>
     */
    @SuppressWarnings("unchecked")
    private static void hookXdfControlsSetup(ClassLoader cl, Class<?> xdfControls)
            throws Exception {
        Class<?> delegate = Reflect.findClass(CLS_XDF_CONTROLS + "$Delegate", cl);
        Method m = Reflect.findDeclared(xdfControls, "setup",
                new Class<?>[]{delegate, Context.class, ViewGroup.class});
        m.setAccessible(true);
        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> {
            chain.proceed();
            Object self = chain.getThisObject(); // setup 是 void，proceed() 返回 null
            try {
                Object container = Reflect.getField(self, "mContainer");
                if (container instanceof ViewGroup) {
                    View editView = ((ViewGroup) container).findViewById(ID_EDIT_BOTTOM);
                    if (editView != null) {
                        // XdfPhotoPageControls implements OnClickListener
                        editView.setOnClickListener((View.OnClickListener) self);
                        Object mapObj = Reflect.getField(self, "mControlsVisible");
                        if (mapObj instanceof Map) {
                            ((Map<Object, Object>) mapObj).put(editView, Boolean.FALSE);
                        }
                        XDFHook.logi(TAG, "setup: re-registered edit button (0x"
                                + Integer.toHexString(ID_EDIT_BOTTOM) + ") into mControlsVisible");
                    } else {
                        XDFHook.logw(TAG, "setup: edit button NOT FOUND in container!");
                    }
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "setup register edit");
            }
            return self;
        });
    }

    /* ==================== ⑨ 编辑器可用性 ==================== */

    /**
     * {@code GalleryUtils.isEditorAvailable(Context, String)Z} → true。
     *
     * <p>ROM 实现未被改：它读 SharedPreferences 缓存，缓存过期时用
     * {@code PackageManager.queryIntentActivities(ACTION_EDIT + mimeType)} 判断
     * "系统里有没有图片编辑器"，无则 false。学习机 ROM 未预置图片编辑器 ⇒
     * {@code mHaveImageEditor = false} ⇒ 底部按钮与菜单项都被判定为不可显示。</p>
     *
     * <p>置 true 只是让"有没有编辑器"这一项通过；<b>按钮最终是否可见仍由
     * {@code canDisplayBottomControl()} 的其余条件（只读/胶片/媒体类型/控制条可见性）
     * 正常决定</b>，不破坏任何语义。</p>
     */
    private static void hookIsEditorAvailable(ClassLoader cl) throws Exception {
        Method m = Reflect.findDeclared(
                Reflect.findClass(CLS_GALLERY_UTILS, cl),
                "isEditorAvailable", new Class<?>[]{Context.class, String.class});
        m.setAccessible(true);
        XDFHook.deopt(m);
        XDFHook.hook(m, chain -> Boolean.TRUE);
    }
}
