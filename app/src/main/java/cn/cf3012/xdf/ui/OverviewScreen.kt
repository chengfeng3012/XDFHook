package cn.cf3012.xdf.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.cf3012.xdf.AppConfig
import cn.cf3012.xdf.R
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 总览页：master 总闸 + per-scope 应用列表（点击进入该 scope 的功能详情） */
@Composable
fun OverviewScreen(tick: Int, context: Context) {
    var detail by remember { mutableStateOf<String?>(null) }
    val current = detail
    if (current != null) {
        ScopeDetailScreen(
            scope = current,
            onBack = { detail = null },
            tick = tick,
            context = context,
        )
    } else {
        ScopeListScreen(tick = tick, context = context, onOpen = { detail = it })
    }
}

/** scope 列表（7 个固定条目：2 框架 + 5 业务包） */
private val SCOPE_ORDER = listOf(
    AppConfig.SCOPE_SYSTEM,
    AppConfig.SCOPE_ANDROID,
    AppConfig.SCOPE_ZEUS,
    AppConfig.SCOPE_SETTINGS,
    AppConfig.SCOPE_LAUNCHER,
    AppConfig.SCOPE_GALLERY,
    AppConfig.SCOPE_PACKAGE_INSTALLER,
)

@Composable
fun ScopeListScreen(tick: Int, context: Context, onOpen: (String) -> Unit) {
    // 本地刷新 tick：写配置 / 改 scope 后自增，避免受控开关被旧值弹回
    var cfgTick by remember { mutableStateOf(tick) }
    var scopeTick by remember { mutableStateOf(tick) }
    val cfg = remember(cfgTick) { AppConfig.refresh() }
    val scopeSet = remember(scopeTick) { ScopeManager.scope().toSet() }
    val connected = ScopeManager.isConnected()
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var confirmAdd by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            SwitchPreference(
                checked = cfg.master,
                onCheckedChange = { checked ->
                    if (AppConfig.setBoolean(AppConfig.K_MASTER, checked)) {
                        cfgTick++
                    } else {
                        Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                    }
                },
                title = stringResource(R.string.master_title),
                summary = stringResource(R.string.master_subtitle),
            )
        }
        item {
            SmallTitle(text = stringResource(R.string.section_module))
        }
        SCOPE_ORDER.forEach { scope ->
            item {
                val inScope = scopeSet.contains(scope)
                ScopeRow(
                    scope = scope,
                    inScope = inScope,
                    enabled = connected,
                    onToggle = {
                        if (inScope) {
                            confirmRemove = scope
                        } else {
                            confirmAdd = scope
                        }
                    },
                    onClick = { onOpen(scope) },
                )
            }
        }
        if (!connected) {
            item {
                Text(
                    text = stringResource(R.string.env_service_off),
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }

    confirmAdd?.let { scope ->
        AlertDialog(
            onDismissRequest = { confirmAdd = null },
            title = { Text("加入作用域？") },
            text = { Text("把「${scopeName(scope)}」加入模块作用域后，模块代码才会注入该进程。") },
            confirmButton = {
                TextButton(onClick = {
                    val s = scope
                    confirmAdd = null
                    ScopeManager.request(s) { ok, reason ->
                        if (ok) {
                            scopeTick++
                            Toast.makeText(
                                context,
                                "已请求加入，重新打开应用后生效",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "加入失败：${reason ?: "未知原因"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAdd = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    confirmRemove?.let { scope ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移出作用域？") },
            text = { Text("「${scopeName(scope)}」将不再注入模块代码，重新打开应用后生效。") },
            confirmButton = {
                TextButton(onClick = {
                    ScopeManager.remove(scope)
                    scopeTick++
                    confirmRemove = null
                    Toast.makeText(context, "已移出作用域", Toast.LENGTH_SHORT).show()
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** 单个 scope 行：图标 + 名称/包名 + 作用域开关 */
@Composable
private fun ScopeRow(
    scope: String,
    inScope: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标
            val icon = remember(scope) { loadScopeIcon(context, scope) }
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(text = scopeName(scope), fontSize = 15.sp, color = Color(0xFF1C1C1E))
                Text(
                    text = scope,
                    fontSize = 11.sp,
                    color = Color(0xFF8A8A8E),
                )
            }
            androidx.compose.material3.Switch(
                checked = inScope,
                enabled = enabled,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

/** scope 显示名 */
internal fun scopeName(scope: String): String = when (scope) {
    AppConfig.SCOPE_SYSTEM -> "系统框架"
    AppConfig.SCOPE_ANDROID -> "系统界面"
    AppConfig.SCOPE_ZEUS -> "Zeus 管控"
    AppConfig.SCOPE_SETTINGS -> "系统设置"
    AppConfig.SCOPE_LAUNCHER -> "桌面"
    AppConfig.SCOPE_GALLERY -> "相册"
    AppConfig.SCOPE_PACKAGE_INSTALLER -> "应用安装器"
    else -> scope
}

/** 按 scope 加载应用图标（system/android 用 android 默认图标） */
internal fun loadScopeIcon(context: Context, scope: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val pkg = if (scope == AppConfig.SCOPE_SYSTEM) "android" else scope
        val pm = context.packageManager
        val d: Drawable = try {
            pm.getApplicationIcon(pkg)
        } catch (_: Throwable) {
            context.getDrawable(android.R.drawable.sym_def_app_icon) ?: return null
        }
        d.toBitmap(36, 36).asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}