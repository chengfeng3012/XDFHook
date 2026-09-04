package cn.cf3012.xdf;

import android.app.Application;

import io.github.libxposed.service.XposedServiceHelper;

/**
 * XdfApp — 模块 UI 进程入口。
 *
 * 只做一件事：注册 XposedService 监听，接收 LSPosed daemon 注入的
 * service binder（经 service aar 自带的 XposedProvider 送达）。
 * binder 可能在 registerListener 之前到达，Helper 内部有缓存回放，
 * 时序无需操心。
 */
public class XdfApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DebugProbe.setProcessName("app-ui");
        DebugProbe.setAppDir(getFilesDir());
        DebugProbe.log("XdfApp.onCreate");
        try {
            XposedServiceHelper.registerListener(
                    new XposedServiceHelper.OnServiceListener() {
                        @Override
                        public void onServiceBind(io.github.libxposed.service.XposedService service) {
                            XposedServiceHolder.setService(service);
                        }

                        @Override
                        public void onServiceDied(io.github.libxposed.service.XposedService service) {
                            XposedServiceHolder.setService(null);
                        }
                    });
        } catch (Throwable ignored) {
            // service aar 缺失/老 LSPosed 不支持注入：UI 走文件降级路径
        }
    }
}
