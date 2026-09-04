package cn.cf3012.xdf;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GalleryHooks — 修复 XDF 定制相册 (com.android.gallery3d v2.0.0) 的
 * 图片"编辑"按钮不显示问题（原独立 GalleryHook 模块整合，LibXposed api 102）。
 *
 * 可见性控制链（XdfGallery v2.0.0）:
 *   updateMenuOperations() {                                          // PhotoPage 私有方法
 *       ops = mCurrentPhoto.getSupportedOperations()
 *       if (mReadOnlyView)      ops &= ~0x200     // 只读 → 清除编辑位
 *       if (!mHaveImageEditor)  ops &= ~0x200     // 无编辑器 → 清除编辑位
 *       mSupportedOperations = ops
 *       MenuExecutor.updateMenuOperation(menu, ops)  // ← 唯一出口，强制覆盖按钮可见性
 *   }
 *   调用点: onCreateActionBar / onResume / updateUIForCurrentPhoto
 *
 * 旧方案只 hook onPrepareOptionsMenu（该回调本身不碰可见性），
 * 临时 setVisible(true) 每次都被 updateMenuOperation 覆盖 → 不生效。
 *
 * 本类采用多层保险：
 *   ① MenuExecutor.updateMenuOperation()   → 强制 ops |= 0x200（主方案，覆盖所有调用路径）
 *   ② PhotoPage.updateMenuOperations()     → after 重算 mSupportedOperations（双保险）
 *   ③ PhotoPage.onCreate()                 → 强制 mReadOnlyView = false（只读场景）
 *   ④ GalleryActivity.startViewAction()    → 源头清除 intent 的 read-only extra
 *   ⑤ MediaObject.getSupportedOperations() → 兜底返回 ops | 0x200
 *   ⑥ PhotoPage.canDisplayBottomControl(I) → 编辑底部按钮强制 true
 *   ⑥b XdfPhotoPageControls.refresh()      → after 无差别强制编辑按钮 VISIBLE
 *   ⑥c XdfPhotoPageControls.setup()        → 重新注册被除名的编辑按钮（根因修复）
 *   ⑦ PhotoPage.canDisplayBottomControls() → 容器保险（非胶片模式）
 *   ⑧ MenuExecutor.updateSupportedMenuEnabled() → 多选模式菜单强制加编辑位
 *   ⑨ GalleryUtils.isEditorAvailable() → true（mHaveImageEditor 环节）
 *   ⑩ PhotoPage.onPrepareOptionsMenu()     → 最外层 setVisible 保险
 */
final class GalleryHooks {

    private static final String TAG = "gallery";

    private static final String CLS_PHOTOPAGE = "com.android.gallery3d.app.PhotoPage";
    private static final String CLS_MENU_EXECUTOR = "com.android.gallery3d.ui.MenuExecutor";
    private static final String CLS_XDF_CONTROLS = "com.android.gallery3d.app.XdfPhotoPageControls";

    /** "编辑"能力位 */
    private static final int SUPPORT_EDIT = 0x200;
    /** action_edit 菜单项 id */
    private static final int ID_EDIT_MENU = 0x7f090045;
    /** 底部控制条"编辑"按钮 id（res/N9.xml，XdfPhotoPageControls 管理） */
    private static final int ID_EDIT_BOTTOM = 0x7f09032a;

    /** 同一进程内防重复注册 */
    private static final Set<ClassLoader> sHooked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private GalleryHooks() {
    }

    static void hookAll(ClassLoader cl) {
        if (!sHooked.add(cl)) {
            return;
        }
        // 类名定位（失败 = 该进程根本没有相册代码，直接结束）
        Class<?> photoPage, menuExecutor, xdfControls;
        try {
            photoPage = Reflect.findClass(CLS_PHOTOPAGE, cl);
            menuExecutor = Reflect.findClass(CLS_MENU_EXECUTOR, cl);
            xdfControls = Reflect.findClass(CLS_XDF_CONTROLS, cl);
        } catch (Throwable t) {
            XDFHook.logw(TAG, "gallery classes not found: " + t);
            return;
        }
        // 每个 hook 点独立容错：单点失败不影响其余，且失败经日志广播上行可见
        guard("updateMenuOperation", () -> hookMenuOperation(menuExecutor));
        guard("updateMenuOperations", () -> hookUpdateMenuOperations(photoPage, menuExecutor));
        guard("photoPageCreate", () -> hookPhotoPageCreate(photoPage));
        guard("startViewAction", () -> hookStartViewAction(cl));
        guard("mediaObject", () -> hookMediaObject(cl));
        guard("canDisplayBottomControl", () -> hookCanDisplayBottomControl(photoPage));
        guard("xdfControlsRefresh", () -> hookXdfControlsRefresh(xdfControls));
        guard("xdfControlsSetup", () -> hookXdfControlsSetup(cl, xdfControls));
        guard("updateSupportedMenuEnabled", () -> hookUpdateSupportedMenuEnabled(menuExecutor));
        guard("canDisplayBottomControls", () -> hookCanDisplayBottomControls(photoPage));
        guard("isEditorAvailable", () -> hookIsEditorAvailable(cl));
        guard("onPrepareOptionsMenu", () -> hookOnPrepareOptionsMenu(photoPage));

        XDFHook.logi(TAG, "gallery hookAll done (failures, if any, logged above)");
    }

    /** 单点容错：失败记日志（logcat + 广播上行），绝不连累其它 hook 点 */
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

    /* ==================== ① 主方案 ==================== */

    /**
     * 可见性唯一出口：before 里强制把编辑位 0x200 加上。
     * 无论 updateMenuOperations / updateMenuOperationWhenLoadingFail 怎么算，
     * 最终设置给 action_edit 的 visible 一定是 true。
     */
    private static void hookMenuOperation(Class<?> menuExecutor) throws Exception {
        Method m = Reflect.findDeclared(menuExecutor, "updateMenuOperation",
                new Class<?>[]{Menu.class, int.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            int ops = (Integer) chain.getArg(1);
            XDFHook.logi(TAG, "updateMenuOperation ops=0x" + Integer.toHexString(ops));
            if ((ops & SUPPORT_EDIT) == 0) {
                return chain.proceed(new Object[]{chain.getArg(0), ops | SUPPORT_EDIT});
            }
            return chain.proceed();
        });
    }

    /* ==================== ② 双保险 ==================== */

    /** after 里把字段强制置位并重算一次（即使 ① 被谁绕过，这里也兜住） */
    private static void hookUpdateMenuOperations(Class<?> photoPage, Class<?> menuExecutor)
            throws Exception {
        Method m = Reflect.findDeclared(photoPage, "updateMenuOperations", new Class<?>[0]);
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            // void 方法 chain.proceed() 返回 null —— after 逻辑必须用 getThisObject()
            chain.proceed();
            Object self = chain.getThisObject();
            try {
                int ops = (Integer) Reflect.getField(self, "mSupportedOperations");
                ops |= SUPPORT_EDIT;
                Reflect.setField(self, "mSupportedOperations", ops);
                Object actionBar = Reflect.getField(self, "mActionBar");
                if (actionBar != null) {
                    Object menu = Reflect.call(actionBar, "getMenu", null);
                    if (menu != null) {
                        Reflect.callStatic(menuExecutor, "updateMenuOperation",
                                new Class<?>[]{Menu.class, int.class}, menu, ops);
                    }
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "updateMenuOperations");
            }
            return self;
        });
    }

    /* ==================== ③④⑤ 源头与兜底 ==================== */

    /** ③ 只读场景：onCreate(BB) after 强制 mReadOnlyView = false */
    private static void hookPhotoPageCreate(Class<?> photoPage) throws Exception {
        Method m = Reflect.findDeclared(photoPage, "onCreate",
                new Class<?>[]{Bundle.class, Bundle.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            chain.proceed();
            Object self = chain.getThisObject(); // onCreate 是 void，proceed() 返回 null
            try {
                Reflect.setField(self, "mReadOnlyView", false);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "mReadOnlyView");
            }
            return self;
        });
    }

    /** ④ 源头：startViewAction(Intent) before 清除外部入口 intent 的 read-only extra */
    private static void hookStartViewAction(ClassLoader cl) throws Exception {
        Method m = Reflect.findDeclared(
                Reflect.findClass("com.android.gallery3d.app.GalleryActivity", cl),
                "startViewAction", new Class<?>[]{Intent.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            try {
                Intent intent = (Intent) chain.getArg(0);
                if (intent != null && intent.hasExtra("read-only")) {
                    intent.removeExtra("read-only");
                    XDFHook.logi(TAG, "removed read-only extra");
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "startViewAction");
            }
            return chain.proceed();
        });
    }

    /** ⑤ 兜底：MediaObject.getSupportedOperations() after |0x200
     *  （对未 override 的 MediaItem 子类生效；LocalImage/LocalVideo 等
     *    已 override，主要走 ① 保证按钮可见） */
    private static void hookMediaObject(ClassLoader cl) throws Exception {
        Method m = Reflect.findDeclared(
                Reflect.findClass("com.android.gallery3d.data.MediaObject", cl),
                "getSupportedOperations", new Class<?>[0]);
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            Object result = chain.proceed();
            if (result instanceof Integer) {
                return (Integer) result | SUPPORT_EDIT;
            }
            return result;
        });
    }

    /* ==================== ⑥ 底部控制条（核心） ==================== */

    /** ⑥ XDF 定制 ROM 的"编辑"按钮是底部控制条控件，可见性由本方法决定
     *  （XdfPhotoPageControls.refresh 逐按钮判定）。对编辑按钮强制返回 true。 */
    private static void hookCanDisplayBottomControl(Class<?> photoPage) throws Exception {
        Method m = Reflect.findDeclared(photoPage, "canDisplayBottomControl",
                new Class<?>[]{int.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            int id = (Integer) chain.getArg(0);
            if (id == ID_EDIT_BOTTOM) {
                XDFHook.logi(TAG, "canDisplayBottomControl id=0x"
                        + Integer.toHexString(id) + " -> FORCE true");
                return Boolean.TRUE;
            }
            return chain.proceed();
        });
    }

    /** ⑥b 无差别强制：refresh() after 把编辑按钮 View 设为 VISIBLE（兜底 + 诊断） */
    private static void hookXdfControlsRefresh(Class<?> xdfControls) throws Exception {
        Method m = Reflect.findDeclared(xdfControls, "refresh", new Class<?>[0]);
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            chain.proceed();
            Object self = chain.getThisObject(); // refresh 是 void，proceed() 返回 null
            try {
                forceEditVisible(self);
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "refresh force");
            }
            return self;
        });
    }

    /** ⑥c 根因修复：XDF 在 setup() 里把编辑按钮 0x7f09032a 从管理列表
     *  mControlsVisible 中除名（initChildView 只注册了返回/修剪/删除/详细信息 4 个），
     *  refresh() 只遍历 map，编辑按钮永不更新 → 永远 invisible。
     *  修复：setup 后把编辑按钮重新注册（点击事件 + 加入 map 初始 false），
     *  让 refresh() 恢复管理；配合 ⑥ 对编辑返回 true → 显示。 */
    @SuppressWarnings("unchecked")
    private static void hookXdfControlsSetup(ClassLoader cl, Class<?> xdfControls)
            throws Exception {
        Class<?> delegate = Reflect.findClass(CLS_XDF_CONTROLS + "$Delegate", cl);
        Method m = Reflect.findDeclared(xdfControls, "setup",
                new Class<?>[]{delegate, Context.class, ViewGroup.class});
        m.setAccessible(true);
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
                        XDFHook.logi(TAG, "setup: RE-REGISTERED edit button (0x"
                                + Integer.toHexString(ID_EDIT_BOTTOM) + ")");
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

    @SuppressWarnings("unchecked")
    private static void forceEditVisible(Object controls) throws Exception {
        Object mapObj = Reflect.getField(controls, "mControlsVisible");
        if (mapObj instanceof Map) {
            for (Object v : ((Map<Object, Object>) mapObj).keySet()) {
                if (v instanceof View && ((View) v).getId() == ID_EDIT_BOTTOM) {
                    ((View) v).setVisibility(View.VISIBLE);
                    XDFHook.logi(TAG, "refresh() forced edit button VISIBLE");
                }
            }
        }
    }

    /* ==================== ⑦⑧⑨⑩ 外围保险 ==================== */

    /** ⑦ 容器保险：底部控制条整体是否显示（mIsActive && !canUndo && mShowBars
     *  && !filmMode + 扩展判定）。仅当容器本应显示时才强制 true，避免影响胶片模式布局。 */
    private static void hookCanDisplayBottomControls(Class<?> photoPage) throws Exception {
        Method m = Reflect.findDeclared(photoPage, "canDisplayBottomControls", new Class<?>[0]);
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            try {
                Object self = chain.getThisObject();
                Object photoView = Reflect.getField(self, "mPhotoView");
                boolean filmMode = (Boolean) Reflect.call(photoView, "getFilmMode", null);
                if (!filmMode) {
                    return Boolean.TRUE;
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "canDisplayBottomControls");
            }
            return chain.proceed();
        });
    }

    /** ⑧ 多选模式菜单：updateSupportedMenuEnabled(Menu,int,boolean) 强制加编辑位 */
    private static void hookUpdateSupportedMenuEnabled(Class<?> menuExecutor) throws Exception {
        Method m = Reflect.findDeclared(menuExecutor, "updateSupportedMenuEnabled",
                new Class<?>[]{Menu.class, int.class, boolean.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            int ops = (Integer) chain.getArg(1);
            if ((ops & SUPPORT_EDIT) == 0) {
                return chain.proceed(new Object[]{chain.getArg(0), ops | SUPPORT_EDIT,
                        chain.getArg(2)});
            }
            return chain.proceed();
        });
    }

    /** ⑨ mHaveImageEditor 环节：GalleryUtils.isEditorAvailable(Context,String) -> true */
    private static void hookIsEditorAvailable(ClassLoader cl) throws Exception {
        Method m = Reflect.findDeclared(
                Reflect.findClass("com.android.gallery3d.util.GalleryUtils", cl),
                "isEditorAvailable", new Class<?>[]{Context.class, String.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> Boolean.TRUE);
    }

    /** ⑩ 最外层保险：onPrepareOptionsMenu(Menu) 直接 setVisible 编辑项 */
    private static void hookOnPrepareOptionsMenu(Class<?> photoPage) throws Exception {
        Method m = Reflect.findDeclared(photoPage, "onPrepareOptionsMenu",
                new Class<?>[]{Menu.class});
        m.setAccessible(true);
        XDFHook.hook(m, chain -> {
            try {
                Object self = chain.getThisObject();
                Menu menu = (Menu) chain.getArg(0);
                int ops = (Integer) Reflect.getField(self, "mSupportedOperations");
                ops |= SUPPORT_EDIT;
                Reflect.setField(self, "mSupportedOperations", ops);
                if (menu != null) {
                    MenuItem editItem = menu.findItem(ID_EDIT_MENU);
                    if (editItem != null) {
                        editItem.setVisible(true);
                    }
                }
            } catch (Throwable t) {
                XDFHook.loge(t, TAG, "onPrepareOptionsMenu");
            }
            return chain.proceed();
        });
    }
}
