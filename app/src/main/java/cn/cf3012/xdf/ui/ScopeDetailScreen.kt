package cn.cf3012.xdf.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.cf3012.xdf.AppConfig
import cn.cf3012.xdf.R
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** hook 单元定义：标题 + 说明 + 绑定 AppConfig 字段 */
private class HookDef(
    val hook: String,
    val title: String,
    val desc: String,
    val get: (AppConfig) -> Boolean,
)

private fun hooksFor(scope: String): List<HookDef> = when (scope) {
    AppConfig.SCOPE_SYSTEM -> listOf(
        HookDef(AppConfig.H_UNLOCK_CTRL, "解除管控限制", "放行学习机管控屏蔽的系统行为",
            { it.hSystemUnlockControl }),
        HookDef(AppConfig.H_HOME_UNLOCK, "默认桌面解锁", "解除桌面被锁死的限制",
            { it.hSystemHomeUnlock }),
        HookDef(AppConfig.H_IME_GUARD, "输入法保护", "防止输入法被意外改掉",
            { it.hSystemImeGuard }),
        HookDef(AppConfig.H_DESKTOP_PROTECT, "桌面锁定防护", "阻止默认桌面被偷偷改回 XDF",
            { it.hSystemDesktopProtect }),
    )
    AppConfig.SCOPE_ANDROID -> listOf(
        HookDef(AppConfig.H_SHARE_CHOOSER, "分享面板修复", "恢复分享面板的\"仅此一次\"与点击响应",
            { it.hAndroidShareChooser }),
    )
    AppConfig.SCOPE_ZEUS -> listOf(
        HookDef(AppConfig.H_UNLOCK_CTRL, "解除管控限制", "跳过学习机的管控检查与云控下发",
            { it.hZeusUnlockControl }),
        HookDef(AppConfig.H_SPOOF_DEVICE, "设备信息伪装", "向管控服务伪造型号与序列号",
            { it.hZeusSpoofDevice }),
    )
    AppConfig.SCOPE_SETTINGS -> listOf(
        HookDef(AppConfig.H_SETTINGS_UNLOCK, "完整设置", "恢复被隐藏的设置项与开发者选项",
            { it.hSettingsUnlock }),
    )
    AppConfig.SCOPE_LAUNCHER -> listOf(
        HookDef(AppConfig.H_RECENT_TASKS, "桌面增强", "恢复最近任务列表不被隐藏",
            { it.hLauncherRecentTasks }),
        HookDef(AppConfig.H_HOME_UNLOCK, "默认桌面解锁", "切换桌面时不被锁回",
            { it.hLauncherHomeUnlock }),
    )
    AppConfig.SCOPE_GALLERY -> listOf(
        HookDef(AppConfig.H_GALLERY_EDIT, "图片编辑", "恢复相册的编辑入口",
            { it.hGalleryEdit }),
    )
    AppConfig.SCOPE_PACKAGE_INSTALLER -> listOf(
        HookDef(AppConfig.H_INSTALL_UNLOCK, "自由安装", "允许安装任意来源的应用",
            { it.hInstallUnlock }),
    )
    else -> emptyList()
}

/** 参数行（system=输入法设置；zeus=设备信息伪装参数） */
private sealed class Param {
    object ImeMode : Param()
    object ImeList : Param()
    object ZeusModel : Param()
    object ZeusSn : Param()
}

/** 单个 scope 的详情页：该 scope 上可用的全部功能开关 + 参数 */
@Composable
fun ScopeDetailScreen(
    scope: String,
    onBack: () -> Unit,
    tick: Int,
    context: Context,
) {
    // 本地刷新 tick：写配置/改 scope 后自增，避免受控开关被旧值弹回
    var cfgTick by remember { mutableStateOf(tick) }
    var scopeTick by remember { mutableStateOf(tick) }
    val cfg = remember(cfgTick) { AppConfig.refresh() }
    val inScope = remember(scopeTick) { ScopeManager.isInScope(scope) }
    val hooks = remember { hooksFor(scope) }
    var param by remember { mutableStateOf<Param?>(null) }

    // 返回键：先退出详情页，而不是直接退出 App
    BackHandler(enabled = true) { onBack() }

    fun setHook(hook: String, checked: Boolean) {
        if (AppConfig.setBoolean(AppConfig.hookKey(scope, hook), checked)) {
            cfgTick++
        } else {
            Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
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
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = loadScopeIcon(context, scope)
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = scopeName(scope), fontSize = 18.sp, color = Color(0xFF1C1C1E))
                    Text(text = scope, fontSize = 11.sp, color = Color(0xFF8A8A8E))
                }
            }
        }
        item {
            // 作用域状态 + 操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (inScope) "● 已加入作用域（注入中）" else "○ 未加入作用域（不注入）",
                    fontSize = 13.sp,
                    color = if (inScope) Color(0xFF34C759) else Color(0xFFFF6482),
                    modifier = Modifier.weight(1f),
                )
                if (!inScope) {
                    Text(
                        text = "加入",
                        fontSize = 14.sp,
                        color = Color(0xFF3482FF),
                        modifier = Modifier.clickable {
                            ScopeManager.request(scope) { ok, reason ->
                                if (ok) {
                                    scopeTick++
                                }
                                Toast.makeText(
                                    context,
                                    if (ok) "已请求加入，重新打开应用后生效"
                                    else "加入失败：${reason ?: "未知原因"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                } else {
                    Text(
                        text = "移出",
                        fontSize = 14.sp,
                        color = Color(0xFFFF6369),
                        modifier = Modifier.clickable {
                            ScopeManager.remove(scope)
                            scopeTick++
                            Toast.makeText(context, "已移出作用域", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
        item {
            SmallTitle(text = "在此运行的应用中启用")
        }
        hooks.forEach { hook ->
            item {
                SwitchPreference(
                    checked = hook.get(cfg),
                    onCheckedChange = { setHook(hook.hook, it) },
                    title = hook.title,
                    summary = hook.desc,
                )
            }
        }

        // ---- 参数区（按 scope + 对应开关开启时显示） ----
        if (scope == AppConfig.SCOPE_SYSTEM && cfg.hSystemImeGuard) {
            item { SmallTitle(text = "输入法保护设置") }
            item {
                ArrowPreference(
                    title = "拦截模式",
                    summary = if (cfg.inputMethodMode == 1) "黑名单模式" else "固化模式",
                    onClick = { param = Param.ImeMode },
                )
            }
            item {
                ArrowPreference(
                    title = "黑名单管理",
                    summary = if (cfg.inputMethodList.isNullOrEmpty()) "未选择任何输入法"
                    else "已选择：${cfg.inputMethodList.split(",").size} 个",
                    onClick = { param = Param.ImeList },
                )
            }
        }
        if (scope == AppConfig.SCOPE_ZEUS && cfg.hZeusSpoofDevice) {
            item { SmallTitle(text = "设备信息伪装参数") }
            item {
                ArrowPreference(
                    title = "型号",
                    summary = if (cfg.zeusModel.isNullOrEmpty()) "未设置（使用真实型号）" else cfg.zeusModel,
                    onClick = { param = Param.ZeusModel },
                )
            }
            item {
                ArrowPreference(
                    title = "序列号",
                    summary = if (cfg.zeusSn.isNullOrEmpty()) "未设置（使用真实序列号）" else cfg.zeusSn,
                    onClick = { param = Param.ZeusSn },
                )
            }
        }
        item {
            Text(
                text = "某些功能改动需要重新打开目标应用后生效。",
                fontSize = 12.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }

    // ---- 参数对话框 ----
    when (param) {
        is Param.ImeMode -> ImeModeDialog(
            current = cfg.inputMethodMode,
            onPick = { v ->
                param = null
                if (!AppConfig.setInt(AppConfig.K_INPUT_METHOD_MODE, v)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { param = null },
        )
        is Param.ImeList -> ImeListDialog(
            selected = cfg.inputMethodList,
            context = context,
            onPick = { list ->
                param = null
                if (!AppConfig.setString(AppConfig.K_INPUT_METHOD_LIST, list)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { param = null },
        )
        is Param.ZeusModel -> ZeusModelDialog(
            context = context,
            onPick = { v ->
                param = null
                if (!AppConfig.setString(AppConfig.K_ZEUS_MODEL, v)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { param = null },
        )
        is Param.ZeusSn -> TextInputDialog(
            title = "输入序列号",
            hint = "留空则恢复真实值",
            initial = cfg.zeusSn ?: "",
            context = context,
            onOk = { v ->
                param = null
                if (!AppConfig.setString(AppConfig.K_ZEUS_SN, v.trim())) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "序列号已保存", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { param = null },
        )
        null -> Unit
    }
}

/* ---------------- 参数对话框 ---------------- */

@Composable
private fun ImeModeDialog(current: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val modes = listOf("固化模式", "黑名单模式")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("拦截模式") },
        text = {
            Column {
                modes.forEachIndexed { i, name ->
                    Text(
                        text = name,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clickable { onPick(i) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ImeListDialog(
    selected: String?,
    context: Context,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val list = remember { imm.inputMethodList }
    val selectedSet = remember(selected) {
        if (selected.isNullOrEmpty()) emptySet() else selected.split(",").toSet()
    }
    var checked by remember { mutableStateOf(selectedSet) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("选择输入法") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                list.forEach { info ->
                    val id = info.id
                    val label = info.loadLabel(context.packageManager).toString()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = id in checked,
                                onValueChange = { checked = if (it) checked + id else checked - id },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = id in checked, onCheckedChange = null)
                        Text(text = "$label - [$id]", fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(checked.sorted().joinToString(",")) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ZeusModelDialog(context: Context, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val names = remember {
        listOf(context.getString(R.string.zeus_model_none)) + AppConfig.ZEUS_MODELS.toList()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("选择型号") },
        text = {
            Column {
                AppConfig.ZEUS_MODELS.forEachIndexed { i, m ->
                    Text(
                        text = names[i + 1],
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { onPick(m) },
                    )
                }
                Text(
                    text = names[0],
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable { onPick("") },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    hint: String,
    initial: String,
    context: Context,
    onOk: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(hint) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onOk(value) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}