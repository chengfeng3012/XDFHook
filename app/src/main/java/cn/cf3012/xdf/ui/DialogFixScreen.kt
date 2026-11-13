package cn.cf3012.xdf.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import cn.cf3012.xdf.AppConfig
import cn.cf3012.xdf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 「弹窗文字修复」应用选择页。
 *
 * <h3>为什么需要这一页</h3>
 *
 * <p>XDF 把 framework 的 `ThemeOverlay.Material.Dialog.Alert` 的 `windowBackground`
 * 写死成 `@drawable/bg_white_rect_corner_16`（纯白 + 16dp 圆角）。而 `alertDialogTheme`
 * 只是个 <b>ThemeOverlay</b>，只覆盖背景、不带文字色 —— 文字仍从 Activity 主题继承。
 * 于是<b>深色主题 App</b>（`Theme.Material` / `Theme.DeviceDefault`）的 AlertDialog
 * 变成「白底白字」，标题/正文/单选列表全部隐形（按钮走 `colorAccent` 反而正常）。</p>
 *
 * <p>修复必须跑在 <b>目标 App 进程</b>里（dialog 与资源解析都在 App 进程内完成，
 * system_server 无从干预），所以本页的作用是：<b>让用户自己选哪些 App 需要修复</b>，
 * 并把选中的应用自动补进模块作用域（`requestScope`）。</p>
 *
 * <p>★ 默认清单为空 —— 不做"全量作用域"推荐，也不默认给每个 App 注入。</p>
 */
private data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
)

/** "a,b,c" → {"a","b","c"}（去空、去空格） */
private fun parseAppList(s: String?): Set<String> {
    if (s.isNullOrBlank()) return emptySet()
    return s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

/** 取有启动图标的已安装应用（按名称排序，排除模块自身） */
private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val ris = try {
        pm.queryIntentActivities(intent, 0)
    } catch (t: Throwable) {
        return emptyList()
    }
    val seen = HashSet<String>()
    val out = ArrayList<AppEntry>(ris.size)
    ris.forEach { ri ->
        val ai = ri.activityInfo ?: return@forEach
        val pkg = ai.packageName ?: return@forEach
        if (pkg == context.packageName) return@forEach
        if (!seen.add(pkg)) return@forEach
        val label = try {
            ri.loadLabel(pm).toString()
        } catch (t: Throwable) {
            pkg
        }
        val icon = try {
            ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
        } catch (t: Throwable) {
            null
        }
        out.add(AppEntry(pkg, label, icon))
    }
    return out.sortedBy { it.label.lowercase() }
}

@Composable
fun DialogFixScreen(tick: Int, context: Context, onBack: () -> Unit) {
    var cfgTick by remember { mutableStateOf(tick) }
    var scopeTick by remember { mutableStateOf(tick) }
    val cfg = remember(cfgTick) { AppConfig.refresh() }
    val selected = remember(cfgTick) { parseAppList(cfg.dialogFixApps) }
    val scopeSet = remember(scopeTick) { ScopeManager.scope().toSet() }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        loading = false
    }

    fun save(list: Set<String>): Boolean {
        val v = list.filter { it.isNotEmpty() }.sorted().joinToString(",")
        return if (AppConfig.setString(AppConfig.K_DIALOG_FIX_APPS, v)) {
            cfgTick++
            true
        } else {
            Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
            false
        }
    }

    fun toggle(pkg: String, checked: Boolean) {
        val cur = parseAppList(AppConfig.refresh().dialogFixApps).toMutableSet()
        if (checked) cur.add(pkg) else cur.remove(pkg)
        if (!save(cur)) return

        if (!checked) {
            // 只停止修复，不动用户的 LSPosed 作用域（避免"取消勾选 = 悄悄移出作用域"）
            Toast.makeText(context, "已停止修复该应用（作用域未改动）", Toast.LENGTH_SHORT).show()
            return
        }
        // 勾选：若目标应用还不在作用域里，直接请求加入（LSPosed 会弹确认窗）
        if (pkg in ScopeManager.scope()) {
            Toast.makeText(context, "已启用，重新打开该应用后生效", Toast.LENGTH_SHORT).show()
        } else {
            ScopeManager.request(pkg) { ok, reason ->
                if (ok) {
                    scopeTick++
                    Toast.makeText(
                        context,
                        "已加入作用域，重新打开该应用后生效",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "加入作用域失败：${reason ?: "未知原因"}（可在 LSPosed 里手动添加）",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹  返回",
                    fontSize = 15.sp,
                    color = Color(0xFF3482FF),
                    modifier = Modifier.clickable(onClick = onBack),
                )
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(text = "弹窗文字修复", fontSize = 18.sp, color = Color(0xFF1C1C1E))
                Text(
                    text = "学习机的系统弹窗背景被写死为纯白，深色主题的应用会变成"
                            + "「白底白字」，标题和列表文字看不见。"
                            + "在这里勾选需要修复的应用即可；勾选后会自动把该应用加入模块作用域。",
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用名或包名") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item {
            SmallTitle(
                text = if (selected.isEmpty()) "尚未选择应用（默认不修复任何应用）"
                else "已选择 ${selected.size} 个应用"
            )
        }
        if (loading) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(
                        text = "  正在读取应用列表…",
                        fontSize = 13.sp,
                        color = Color(0xFF8A8A8E),
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                Text(
                    text = "没有匹配的应用",
                    fontSize = 13.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        items(filtered, key = { it.pkg }) { app ->
            val checked = app.pkg in selected
            val inScope = app.pkg in scopeSet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        onValueChange = { toggle(app.pkg, it) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(text = app.label, fontSize = 15.sp, color = Color(0xFF1C1C1E))
                    Text(text = app.pkg, fontSize = 11.sp, color = Color(0xFF8A8A8E))
                    if (checked) {
                        Text(
                            text = if (inScope) "● 已加入作用域（注入中）"
                            else "○ 未加入作用域 —— 重新勾选可再次请求",
                            fontSize = 11.sp,
                            color = if (inScope) Color(0xFF34C759) else Color(0xFFFF6482),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Checkbox(checked = checked, onCheckedChange = null)
            }
        }
        item {
            Text(
                text = "提示：勾选后需要重新打开目标应用才会生效；"
                        + "取消勾选只是停止修复，不会把应用移出模块作用域。",
                fontSize = 12.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
