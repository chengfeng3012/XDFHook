package cn.cf3012.xdf.ui

import android.os.Handler
import android.os.Looper
import cn.cf3012.xdf.XposedServiceHolder

/**
 * ScopeManager — LSPosed 作用域管理（UI 侧封装）。
 * 基于 LibXposed service 官方接口（getScope/requestScope/removeScope），
 * 无需 root；requestScope 由 daemon/管理器决定批准或拒绝。
 */
object ScopeManager {

    fun isConnected(): Boolean = XposedServiceHolder.isConnected()

    /** 当前模块的 LSPosed 作用域列表（system/android/包名） */
    fun scope(): List<String> {
        return try {
            XposedServiceHolder.get()?.getScope() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun isInScope(scope: String): Boolean = scope()!!.contains(scope)

    /** 请求把 scope 加入模块作用域；结果回调在主线程 */
    fun request(scope: String, onResult: (Boolean, String?) -> Unit) {
        val svc = XposedServiceHolder.get()
        if (svc == null) {
            onResult(false, "未连接到框架服务")
            return
        }
        try {
            ScopeRequester.request(
                svc, scope,
                object : ScopeRequester.Listener {
                    override fun onResult(ok: Boolean, reason: String?) {
                        post { onResult(ok, reason) }
                    }
                },
            )
        } catch (t: Throwable) {
            onResult(false, "请求失败：${t.message}")
        }
    }

    /** 从模块作用域移除 */
    fun remove(scope: String) {
        try {
            XposedServiceHolder.get()?.removeScope(listOf(scope))
        } catch (_: Throwable) {
        }
    }

    private fun post(r: () -> Unit) {
        Handler(Looper.getMainLooper()).post(r)
    }
}