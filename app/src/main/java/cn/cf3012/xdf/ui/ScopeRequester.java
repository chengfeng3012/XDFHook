package cn.cf3012.xdf.ui;

import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.XposedService;

/**
 * ScopeRequester — requestScope 回调的 Java 桥接（Kotlin 覆盖该 Java
 * default 接口存在平台类型解析问题，走 Java 实现稳定）。
 */
final class ScopeRequester {

    interface Listener {
        void onResult(boolean ok, String reason);
    }

    static void request(XposedService svc, String scope, final Listener l) {
        try {
            svc.requestScope(Collections.singletonList(scope),
                    new XposedService.OnScopeEventListener() {
                        @Override
                        public void onScopeRequestApproved(List<String> approved) {
                            post(() -> l.onResult(true, null));
                        }

                        @Override
                        public void onScopeRequestFailed(String reason) {
                            post(() -> l.onResult(false, reason));
                        }
                    });
        } catch (Throwable t) {
            l.onResult(false, "请求失败：" + t.getMessage());
        }
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}