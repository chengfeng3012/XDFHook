package cn.cf3012.xdf.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import cn.cf3012.xdf.XposedServiceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MainActivity — 模块配置界面（Miuix / HyperOS 风格，Compose Multiplatform UI）。
 *
 * 重构自手写 View 版（2026-09-25）：统一使用 Miuix 组件库（SwitchPreference /
 * SmallTitle / TopAppBar / NavigationBar 等），替代原先手工拼 XML 的"类 MIUI"样式。
 * Hook 逻辑层（SettingsHooks/UiRestorer 等）与业务层（AppConfig/FileLogger/Root）
 * 完全不变，本文件仅承载模块自带 App 的表现层。
 */
class MainActivity : ComponentActivity(), XposedServiceHolder.Listener {

    /** 服务状态/页面数据变动 tick：自增触发 Compose 重组刷新 */
    var refreshTick by mutableStateOf(0)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志落盘/自定义路径即时校验需要外部存储写权限（Android 10 运行时权限）
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ), 100
            )
        }
        setContent {
            MiuixTheme {
                MainScreen(
                    tick = refreshTick,
                    context = this@MainActivity,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // service 状态变化（绑定/失联）→ 刷新
        XposedServiceHolder.setListener(this)
        // hook 进程日志广播上行（IPC）：前台期间接收
        try {
            registerReceiver(LogStore.RECEIVER, LogStore.filter())
        } catch (_: Throwable) {
        }
        refreshTick++
    }

    override fun onPause() {
        XposedServiceHolder.setListener(null)
        try {
            unregisterReceiver(LogStore.RECEIVER)
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    /** XposedServiceHolder.Listener：LSPosed 服务连接状态变化 */
    override fun onServiceChanged() {
        refreshTick++
    }
}