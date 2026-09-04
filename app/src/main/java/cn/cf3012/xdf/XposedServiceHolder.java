package cn.cf3012.xdf;

import io.github.libxposed.service.XposedService;

/**
 * XposedServiceHolder — 官方跨进程服务的持有者（模块 UI 进程专用）。
 *
 * 送达机制：LSPosed daemon 在模块 app 进程创建时，通过 service aar 自带的
 * XposedProvider(ContentProvider) 把 IXposedService binder 塞进本进程，
 * 最终回调 XposedServiceHelper.onBinderReceived —— 因此 XdfApp 必须
 * 在 Application.onCreate 里 registerListener（若 binder 先到会缓存回放）。
 *
 * service 可用意味着：
 *   - 模块已被 LSPosed 正确识别（daemon 校验调用者包名为已装模块，天然免鉴权）；
 *   - getRemotePreferences/openRemoteFile 与所有 hook 进程共享同一份
 *     daemon 托管存储（root 目录），不怕删、不怕改、无 SELinux 问题；
 *   - getRunningTargets() 可直接查询当前被 hook 的进程列表与状态。
 */
public final class XposedServiceHolder {

    public interface Listener {
        /** service 状态变化（绑定/失联），主线程回调 */
        void onServiceChanged();
    }

    private static volatile XposedService sService;
    private static volatile Listener sListener;

    private XposedServiceHolder() {
    }

    static void setService(XposedService service) {
        sService = service;
        DebugProbe.log(service != null
                ? "XposedService BOUND (binder delivered)"
                : "XposedService DIED");
        if (service != null) {
            // 绑定成功即初始化 UI 侧配置通道（remote prefs）
            AppConfig.uiInit(service);
        } else {
            // binder 死亡：丢弃 remote 引用，配置降级文件模式，允许重绑重建
            AppConfig.onRemoteDied();
        }
        notifyChanged();
    }

    public static XposedService get() {
        return sService;
    }

    public static boolean isConnected() {
        return sService != null;
    }

    public static void setListener(Listener l) {
        sListener = l;
    }

    private static void notifyChanged() {
        Listener l = sListener;
        if (l != null) {
            try {
                l.onServiceChanged();
            } catch (Throwable ignored) {
            }
        }
    }
}
