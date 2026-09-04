package cn.cf3012.xdf;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * ZeusUnlocker — 解除 XDF 家长管控（cn.xdf.zeus）三层拦截。
 *
 * 基于逆向报告（APP图标无法点击原因分析.md / chooser_activity_analysis.md /
 * XDF家长管控系统完整逆向分析报告）+ current apk (framework.apk = framework.jar + services.jar)
 * 的逐一核实，按调用层次分三组：
 *
 * 【A. system_server 进程 — onSystemServerStarting 传入 boot classloader】
 *  A1 IntentStandardActionManager.activityStartingStandardAction(Intent) -> true
 *     framework 层直接放行。禁用集合 ACTION_SET_DISABLE 实为 9 个 action：
 *     DIAL / CALL / SEND / SENDTO / ANSWER / SEARCH / WEB_SEARCH / TRANSLATE / PROCESS_TEXT。
 *     ChooserActivity.onCreate 在 .line 510 调用它，false 时会 setIntent(空) + finish()，
 *     然后跳转 zeus 提示页 "cn.xdf.zeus.activity.intent.standard.action"。
 *     即使 disable zeus APK，这条 framework 硬编码依然生效 —— 必须在 framework 层 hook。
 *  A2 IntentStandardActionManager.startIntentStandardActionActivity(Context) -> 不执行
 *     双保险：即使走到禁止分支，也不再跳转 "不支持此功能" 提示页。
 *  A3 ResolverActivity.setDefaultLauncher(int) -> 不执行
 *     defPackageName/defClassName 两个字段只在 setDefaultLauncher 中使用（已核实：
 *     clinit 默认 cn.xdf.pad.launcher，onCreate 处理 HOME intent 时按 user_setup_complete
 *     动态改写为 com.android.provision 或 launcher，然后 addPreferredActivityAsUser
 *     强制把 HOME 首选设为 XDF 桌面并 finish）。hook 整个方法 = 覆盖全部赋值分支，
 *     恢复标准 HOME 选择器行为。
 *  A4 XdfManagerService$ActivityController.activityStarting/activityResuming -> true
 *     XdfManagerService.systemReady() 时通过 ActivityManagerInternal.setActivityController()
 *     注册 IActivityController，AMS 里【每一次】Activity 启动都会回调 zeus 的检查链。
 *     这是"分享/分享目标 App 图标点了没反应"的最可能主因：面板打开了，但点图标后
 *     目标 Activity 启动被这里拦下。
 *  A5 ZeusManager(services 侧 Kotlin 单例) 管控方法全部短路：
 *     activityStarting/canInstall/canWebLoadUrl -> true；
 *     isDisabledExpand/isDisabledHomeAndRecent/isFocusModeStatus/getVolumeControlState -> false。
 *     XdfManagerService 的 IXdfManager Binder 实现全部转发该单例，hook 它 =
 *     所有用 XdfManager.isDisabledExpand() 等 API 的系统组件拿到的都是"未管控"。
 *  A6 AppMetaDataChecker.checkAppMetaDataExempted -> true
 *     canWebLoadUrl 的 meta-data 豁免检查（salt=beyond_twgdh_xjdmg），置 true 豁免所有应用。
 *
 * 【A+. ChooserActivity UI 修复（基于用户实测"图标点击无反应/无动画"）】
 *  已核实 startSelected 唯一入口（ItemClickListener）从未注册（ChooserActivity 重写
 *  onPrepareAdapterView 不调 super）、item focusable=true + setItemsCanFocus(true) 使
 *  AbsListView 触摸/键盘点击被 hasFocusable 门禁短路、ChooserRowAdapter.areAllItemsEnabled
 *  硬编码 false —— 三者叠加导致手点无涟漪、空格无反应。修复：
 *  C1 areAllItemsEnabled -> true；
 *  C2 onPrepareAdapterView after 补注册 OnItemClickListener（键盘兜底）；
 *  C3 ChooserListAdapter.onBindView after 给图标 cell 绑定 View.OnClickListener
 *     → 反查 position → startSelected(pos,false,false)（主修复，触摸+空格都生效）。
 *
 * 【B. cn.xdf.zeus 进程 — onPackageReady 传入该应用 classloader】（类名来自逆向报告，全部容错）
 *  B1 ZeusManagerService$ZeusManagerStub.activityStarting(Intent,String) -> true
 *     zeus 5 项检查链（标准动作/黑名单/强制更新/专注模式/通用检查）的唯一入口，直接短路。
 *  B2 ZeusManagerExempted.isExempted()/getIsExempted() -> true；无 getter 时用
 *     hookClassInitializer 在 clinit 后改写静态字段。同时放行系统属性
 *     persist.xdf.debug.zeus.manager.exempted -> "1" 兜底。
 *  B3 ForceControlProvider.call(String,String,Bundle) -> null
 *     云控 ContentProvider（24 个命令：专注模式/音量/下拉/Home/锁屏/屏幕超时/手势/AI教师），
 *     返回 null 即 Provider 未处理该命令，所有云控下发失效。
 *  B4 DeviceInfoProvider.call("getSerialNumber") -> proceed 后把 Bundle 内 String 值替换为
 *     等长数字串（key 名未核实，采用"改值不改 key"策略，无需猜协议）。
 *
 * 【C. 不 hook 的点及原因】
 *  - ChooserActivity.startSelected(IZZ) 的 NotSelectableTargetInfo 检查：
 *    该检查是 AOSP 原版逻辑（占位符 PlaceHolderTargetInfo / 空项 EmptyTargetInfo 机制，
 *    current apk 中 EmptyTargetInfo 仅在 completeServiceTargetLoading 的 AOSP 标准位置创建）。
 *    且 NotSelectableTargetInfo.start() 恒返回 false、getResolvedIntent() 为 null，
 *    绕过检查直接走 super 会 NPE。"图标点不动"的真正原因是 A4 的 IActivityController
 *    拦截 + zeus 检查链，修好 A/B 组即可。
 *  - XdfManagerService$UidObserver.appDied：仅通知 zeus 进程死亡，无拦截行为。
 *  - AppBlackListManager：方法名未核实且返回值语义未知，hook 错方向会把放行变拦截；
 *    B1 已从检查链入口短路整个黑名单逻辑，无需单独 hook。
 */
final class ZeusUnlocker {

    private static final String TAG = "ZeusUnlocker";

    /** zeus 检查链读取的豁免属性（ZeusManagerExempted.getZeusManagerExempted()） */
    private static final String PROP_EXEMPTED = "persist.xdf.debug.zeus.manager.exempted";

    private ZeusUnlocker() {
    }

    /* ==================================================================
     * A. system_server 进程
     * ================================================================== */

    static void hookSystemServer(ClassLoader cl) {
        // A1 分享/拨号/搜索/翻译/PROCESS_TEXT 禁止 —— framework 层根开关
        XDFHook.safeHook(cl, "com.android.internal.app.xdf.IntentStandardActionManager",
                "activityStartingStandardAction",
                new Class<?>[]{android.content.Intent.class},
                chain -> Boolean.TRUE,
                "activityStartingStandardAction -> true (解除 9 个 action 禁止)");

        // A2 不再跳转 zeus 的 "不支持此功能" 提示页
        XDFHook.safeHook(cl, "com.android.internal.app.xdf.IntentStandardActionManager",
                "startIntentStandardActionActivity",
                new Class<?>[]{Context.class},
                chain -> null,
                "startIntentStandardActionActivity -> skipped (不再跳提示页)");

        // A3 HOME 键强制锁定 XDF 桌面（defPackageName/defClassName 唯一使用点）
        XDFHook.safeHook(cl, "com.android.internal.app.ResolverActivity",
                "setDefaultLauncher",
                new Class<?>[]{int.class},
                chain -> null,
                "setDefaultLauncher -> skipped (解除 HOME 锁定)");

        // A4 IActivityController 回调 —— 全局 Activity 启动/恢复放行
        XDFHook.safeHook(cl, "cn.xdf.server.XdfManagerService$ActivityController",
                "activityStarting",
                new Class<?>[]{android.content.Intent.class, String.class},
                chain -> Boolean.TRUE,
                "ActivityController.activityStarting -> true");
        XDFHook.safeHook(cl, "cn.xdf.server.XdfManagerService$ActivityController",
                "activityResuming",
                new Class<?>[]{String.class},
                chain -> Boolean.TRUE,
                "ActivityController.activityResuming -> true");

        // A5 services 侧 ZeusManager 单例（XdfManagerService 全部管控转发到此）
        XDFHook.safeHook(cl, "cn.xdf.server.zeus.ZeusManager", "activityStarting",
                new Class<?>[]{android.content.Intent.class, String.class},
                chain -> Boolean.TRUE, "ZeusManager.activityStarting -> true");
        XDFHook.safeHook(cl, "cn.xdf.server.zeus.ZeusManager", "canInstall",
                new Class<?>[]{String.class},
                chain -> Boolean.TRUE, "ZeusManager.canInstall -> true");
        XDFHook.safeHook(cl, "cn.xdf.server.zeus.ZeusManager", "canWebLoadUrl",
                new Class<?>[]{String.class, String.class},
                chain -> Boolean.TRUE, "ZeusManager.canWebLoadUrl -> true");
        XDFHook.safeHook(cl, "cn.xdf.server.zeus.ZeusManager", "isDisabledExpand",
                new Class<?>[0], chain -> Boolean.FALSE,
                "ZeusManager.isDisabledExpand -> false (恢复状态栏下拉)");
        XDFHook.safeHook(cl, "cn.xdf.server.zeus.ZeusManager", "isDisabledHomeAndRecent",
                new Class<?>[0], chain -> Boolean.FALSE,
                "ZeusManager.isDisabledHomeAndRecent -> false (恢复 Home/最近任务)");
        XDFHook.safeHook(cl, "cn.xdf.server.zeus.ZeusManager", "isFocusModeStatus",
                new Class<?>[0], chain -> Boolean.FALSE,
                "ZeusManager.isFocusModeStatus -> false (专注模式视为关闭)");
        XDFHook.safeHook(cl, "cn.xdf.server.zeus.ZeusManager", "getVolumeControlState",
                new Class<?>[0], chain -> Boolean.FALSE,
                "ZeusManager.getVolumeControlState -> false (解除音量管控)");

        // A6 WebView 网址白名单的 meta-data 豁免
        XDFHook.safeHook(cl, "cn.xdf.server.checker.AppMetaDataChecker",
                "checkAppMetaDataExempted",
                new Class<?>[]{Context.class, String.class, String.class},
                chain -> Boolean.TRUE,
                "AppMetaDataChecker.checkAppMetaDataExempted -> true");

        hookChooserUi(cl);
    }

    /* ==================================================================
     * C. ChooserActivity UI 修复（分享面板 App 图标点击无反应/无按压动画）
     *
     * current apk 静态事实：
     * 1. startSelected(IZZ) 的唯一外部入口是 ResolverActivity$ItemClickListener.onItemClick，
     *    而 ItemClickListener 只在【基类】ResolverActivity.onPrepareAdapterView 中注册；
     *    ChooserActivity 重写了 onPrepareAdapterView 且不调 super、也没有任何
     *    setOnItemClickListener —— 唯一点击入口被绕过。
     * 2. item 布局 resolve_grid_item.xml 根 view android:focusable="true"，
     *    且 ChooserActivity.onPrepareAdapterView 里 ListView.setItemsCanFocus(true)，
     *    AbsListView 的触摸路径（CheckForTap 的 setPressed、PerformClick 的
     *    performItemClick）与键盘路径（onKeyDown DPAD_CENTER）全部被
     *    !child.hasFocusable() 检查短路。
     * 3. ChooserRowAdapter.areAllItemsEnabled() 被硬编码返回 false。
     *
     * 上述三条叠加 = 手点无涟漪动画 + 空格/ENTER 无反应（用户实测）。
     * 修复：给每个图标 cell 绑定 View.OnClickListener（View 层自点击，不经过
     * AbsListView 的 hasFocusable/enabled 门禁，且 clickable 后涟漪动画自动恢复），
     * 再给 ListView 补注册 OnItemClickListener 作为键盘路径兜底。
     * ================================================================== */

    private static void hookChooserUi(ClassLoader cl) {
        final String listAdapterCls = "com.android.internal.app.ChooserActivity$ChooserListAdapter";
        final String rowAdapterCls = "com.android.internal.app.ChooserActivity$ChooserRowAdapter";
        final String targetInfoCls = "com.android.internal.app.ResolverActivity$TargetInfo";
        final String resolveListAdapterCls = "com.android.internal.app.ResolverActivity$ResolveListAdapter";

        // C1: 恢复 areAllItemsEnabled（mAreAllItemsSelectable 标志，影响按键导航判定）
        XDFHook.safeHook(cl, rowAdapterCls, "areAllItemsEnabled",
                new Class<?>[0],
                chain -> Boolean.TRUE,
                "ChooserRowAdapter.areAllItemsEnabled -> true");

        // C2: onPrepareAdapterView 之后补注册 OnItemClickListener（键盘路径兜底）
        try {
            Class<?> chooser = Class.forName("com.android.internal.app.ChooserActivity", false, cl);
            Class<?> param2 = Class.forName(resolveListAdapterCls, false, cl);
            Method m = Reflect.findDeclared(chooser, "onPrepareAdapterView",
                    new Class<?>[]{android.widget.AbsListView.class, param2});
            m.setAccessible(true);
            XDFHook.hook(m, chain -> {
                Object r = chain.proceed();
                try {
                    final Object activity = chain.getThisObject();
                    Object lv = chain.getArg(0);
                    if (activity != null && lv instanceof android.widget.AbsListView) {
                        ((android.widget.AbsListView) lv).setOnItemClickListener(
                                new android.widget.AdapterView.OnItemClickListener() {
                                    @Override
                                    public void onItemClick(android.widget.AdapterView<?> parent,
                                                            android.view.View view,
                                                            int position, long id) {
                                        try {
                                            Reflect.call(activity, "startSelected",
                                                    new Class<?>[]{int.class, boolean.class, boolean.class},
                                                    position, false, false);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                });
                        XDFHook.logi(TAG, "chooser: OnItemClickListener registered (fallback)");
                    }
                } catch (Throwable t) {
                    XDFHook.logw(TAG, "chooser listener fallback failed: " + t);
                }
                return r;
            });
            XDFHook.logi(TAG, "hooked: ChooserActivity.onPrepareAdapterView (C2)");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "onPrepareAdapterView skipped: " + t);
        }

        // C3: 每个图标 cell 绑定 OnClickListener（主修复：触摸 + 空格都走这里）
        try {
            Class<?> listAdapter = Class.forName(listAdapterCls, false, cl);
            Class<?> targetInfo = Class.forName(targetInfoCls, false, cl);
            Method m = Reflect.findDeclared(listAdapter, "onBindView",
                    new Class<?>[]{android.view.View.class, targetInfo});
            m.setAccessible(true);
            XDFHook.hook(m, chain -> {
                Object r = chain.proceed();
                try {
                    final Object adapter = chain.getThisObject();
                    final Object info = chain.getArg(1);
                    final Object viewObj = chain.getArg(0);
                    if (adapter != null && info != null && viewObj instanceof android.view.View) {
                        ((android.view.View) viewObj).setOnClickListener(v -> {
                            try {
                                int pos = findAdapterPosition(adapter, info);
                                if (pos >= 0) {
                                    Object activity = Reflect.getField(adapter, "this$0");
                                    Reflect.call(activity, "startSelected",
                                            new Class<?>[]{int.class, boolean.class, boolean.class},
                                            pos, false, false);
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                } catch (Throwable t) {
                    XDFHook.logw(TAG, "chooser cell bind failed: " + t);
                }
                return r;
            });
            XDFHook.logi(TAG, "hooked: ChooserListAdapter.onBindView (C3, item click unlocked)");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "onBindView skipped: " + t);
        }
    }

    /** 在 ChooserListAdapter 中按引用反查 TargetInfo 的 position */
    private static int findAdapterPosition(Object adapter, Object info) throws Exception {
        Method count = Reflect.findDeclared(adapter.getClass(), "getCount", new Class<?>[0]);
        Method get = Reflect.findDeclared(adapter.getClass(), "getItem", new Class<?>[]{int.class});
        int n = (Integer) count.invoke(adapter);
        for (int i = 0; i < n; i++) {
            if (get.invoke(adapter, i) == info) {
                return i;
            }
        }
        return -1;
    }

    /* ==================================================================
     * B. cn.xdf.zeus 进程（类名来自逆向报告，全部容错处理）
     * ================================================================== */

    static void hookZeusProcess(ClassLoader cl) {
        // B2 属性兜底（先于其他 hook 注册，让 zeus 读到 "1"）
        hookPropBypass(cl);

        // B1 zeus 检查链唯一入口
        hookStubActivityStarting(cl);

        // B2 豁免开关
        hookExempted(cl);

        // B3 云控短路：区分两类命令（逆向确认 25 个命令）——
//  · 查询型（is*/get* 及 stylusSystemGestureDisabled，共 7 个：isFocusModeStatus /
//    getVolumeControlState / isLockScreenDisabled / isDisabledExpand /
//    isDisabledHomeAndRecent / isFocusModeFunction / stylusSystemGestureDisabled）：
//    保留读取通道（proceed 拿真实 Bundle），只把管控布尔强制置 false（=未管控）。
//    若象旧版那样 return null，查询方 getBoolean 拿 null/NPE，导致"原本能读的属性读不到"。
//  · 写/管控命令（disable*/enable*/start*/stop*/reset*/set*/pause*/resume* 等）：
//    不执行真实逻辑（短路），返回同构的 Bundle{method:false}，让云控下发失效。
XDFHook.safeHook(cl, "cn.xdf.zeus.sdk.core.provider.ForceControlProvider", "call",
                new Class<?>[]{String.class, String.class, Bundle.class},
                chain -> {
                    String m = (String) chain.getArg(0);
                    boolean isQuery = m != null
                            && (m.startsWith("is") || m.startsWith("get")
                                || "stylusSystemGestureDisabled".equals(m));
                    if (isQuery) {
                        Object r = chain.proceed();
                        if (r instanceof Bundle && m != null) {
                            ((Bundle) r).putBoolean(m, false);
                        }
                        return r;
                    }
                    Bundle b = new Bundle();
                    if (m != null) {
                        b.putBoolean(m, false);
                    }
                    return b;
                },
                "ForceControlProvider.call -> 查询短路 false / 写命令不执行 (云控失效)");

        // B4 设备信息冒充（serial / model）：仅替换 zeus 经 DeviceInfoProvider 读取到的值，
        // 改值不改 key；配置为空时透传真实值。日志给出 inline(真实) -> custom(冒充) 对比，
        // 用于核对用户自定义是否生效且不影响系统真实值。
        XDFHook.safeHook(cl, "cn.xdf.zeus.sdk.core.provider.DeviceInfoProvider", "call",
                new Class<?>[]{String.class, String.class, Bundle.class},
                chain -> {
                    Object r = chain.proceed();
                    String method = (String) chain.getArg(0);
                    if (method == null) {
                        return r;
                    }
                    try {
                        String lower = method.toLowerCase();
                        String custom = "";
                        if (lower.contains("serial") || lower.contains("sn")) {
                            custom = AppConfig.get().zeusSn;
                        } else if (lower.contains("model")) {
                            custom = AppConfig.get().zeusModel;
                        } else {
                            return r;
                        }
                        logSpoof(method, r, custom);
                        return spoofStrings(r, custom);
                    } catch (Throwable t) {
                        return r;
                    }
                },
                "DeviceInfoProvider.call -> 冒充 zeus 读取的 serial/model");
    }

    /** 放行 zeus 豁免属性读取（DeviceInfoCache/SystemPropertiesCache 等） */
    private static void hookPropBypass(ClassLoader cl) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties", false, cl);
            Method m = Reflect.findDeclared(sp, "get", new Class<?>[]{String.class, String.class});
            m.setAccessible(true);
            XDFHook.hook(m, chain -> {
                try {
                    if (PROP_EXEMPTED.equals(chain.getArg(0))) {
                        return "1";
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            XDFHook.logi(TAG, "prop bypass armed: " + PROP_EXEMPTED + " -> \"1\"");
        } catch (Throwable t) {
            XDFHook.logw(TAG, "prop bypass skipped: " + t);
        }
    }

    /** B1: ZeusManagerStub.activityStarting(Intent,String) -> true（容错：精确类名 -> 外部类内部类扫描） */
    private static void hookStubActivityStarting(ClassLoader cl) {
        String[] candidates = {
                "cn.xdf.zeus.core.manager.service.ZeusManagerService$ZeusManagerStub",
                "cn.xdf.zeus.core.manager.service.ZeusManagerService"
        };
        for (String name : candidates) {
            Class<?> c = tryClass(cl, name);
            if (c == null) {
                continue;
            }
            for (Class<?> cc = c; cc != null; cc = cc.getSuperclass()) {
                for (Method m : cc.getDeclaredMethods()) {
                    // 审查结论 4：签名精确校验，防外部类兜底误伤同名不同义方法
                    if ("activityStarting".equals(m.getName())
                            && m.getReturnType() == boolean.class
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == android.content.Intent.class
                            && m.getParameterTypes()[1] == String.class) {
                        XDFHook.hook(m, chain -> Boolean.TRUE);
                        XDFHook.logi(TAG, "zeus stub: " + m + " -> true");
                        return;
                    }
                }
            }
        }
        XDFHook.logw(TAG, "ZeusManagerStub.activityStarting not found (zeus 类名可能已混淆)");
    }

    /** B2: ZeusManagerExempted.isExempted()/getIsExempted() -> true；无方法则改写 clinit 后的静态字段 */
    private static void hookExempted(ClassLoader cl) {
        Class<?> c = tryClass(cl, "cn.xdf.zeus.core.manager.service.ZeusManagerExempted");
        if (c == null) {
            XDFHook.logw(TAG, "ZeusManagerExempted not found");
            return;
        }
        boolean hooked = false;
        for (Method m : c.getDeclaredMethods()) {
            String n = m.getName();
            if (("isExempted".equals(n) || "getIsExempted".equals(n)) && m.getParameterCount() == 0) {
                XDFHook.hook(m, chain -> Boolean.TRUE);
                XDFHook.logi(TAG, "ZeusManagerExempted." + n + "() -> true");
                hooked = true;
            }
        }
        if (hooked) {
            return;
        }
        // Kotlin val 无 getter（@JvmField 或内联）场景：clinit 后强制置 true
        try {
            final Field f = c.getDeclaredField("isExempted");
            f.setAccessible(true);
            XDFHook.hookClassInit(c, chain -> {
                // 先跑原 clinit：异常自然上抛（PROTECTIVE 兜住，语义与无 hook 一致）；
                // 置位失败只记日志（审查结论：先置后跑会被 Kotlin 初始化写回）
                chain.proceed();
                try {
                    f.setBoolean(null, true);
                    XDFHook.logi(TAG, "ZeusManagerExempted.isExempted field forced true (after clinit)");
                } catch (Throwable t) {
                    XDFHook.logw(TAG, "exempted field set failed: " + t);
                }
                return null;
            });
        } catch (Throwable t) {
            XDFHook.logw(TAG, "ZeusManagerExempted no hookable member: " + t);
        }
    }

    /* ==================== 工具 ==================== */

    private static Class<?> tryClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 日志核对：inline(zeus 本应读到的真实值) -> custom(冒充值)；空 = 透传 */
    private static void logSpoof(String method, Object result, String custom) {
        try {
            String inline = firstString(result);
            if (custom == null || custom.isEmpty()) {
                XDFHook.logi(TAG, "zeus deviceinfo: method=" + method
                        + " inline=" + inline + " => 透传真实值");
            } else {
                XDFHook.logi(TAG, "zeus deviceinfo: method=" + method
                        + " inline=" + inline + " => 冒充=" + custom);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 取出 Bundle 中第一个非空 String（用于日志对比，不猜协议 key） */
    private static String firstString(Object result) {
        try {
            if (result instanceof Bundle) {
                Bundle b = (Bundle) result;
                for (String key : new java.util.ArrayList<>(b.keySet())) {
                    Object v = b.get(key);
                    if (v instanceof String && !((String) v).isEmpty()) {
                        return (String) v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 把 Bundle 中所有 String 值替换为 custom（改值不改 key）；空值则透传 */
    private static Object spoofStrings(Object result, String custom) {
        if (custom == null || custom.isEmpty() || !(result instanceof Bundle)) {
            return result;
        }
        try {
            Bundle b = (Bundle) result;
            for (String key : new java.util.ArrayList<>(b.keySet())) {
                if (b.get(key) instanceof String) {
                    b.putString(key, custom);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }
}
