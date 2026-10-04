package cn.cf3012.xdf.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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

/**
 * 哪些 hook 单元「关→开」必须重启目标进程才生效（2026-10）。
 *
 * <p><b>判据</b>：XDFHook.onPackageReady 用 `if (cfg.xxx)` 决定<b>要不要安装 hook</b>。
 * 配置推到进程时 onPackageReady 早已跑完，hook 装不上 → 只能重启目标进程。</p>
 *
 * <p><b>反过来「开→关」多数无需重启</b>：只要 hook 体内每事件都有运行时门控
 * （InputMethodHooks.hookSystemServer 首行、LockScreenHooks.enabled()、
 * ZeusUnlocker.spoofStrings 每次 AppConfig.get()），关闭立即生效。</p>
 *
 * <p>这三个是唯一实现了运行时门控的单元 → 它们的「开启」也属热生效，不在此表内。</p>
 */
private val NEEDS_RESTART_ON_ENABLE = setOf(
    AppConfig.hookKey(AppConfig.SCOPE_SYSTEM, AppConfig.H_IME_GUARD),
    AppConfig.hookKey(AppConfig.SCOPE_SETTINGS, AppConfig.H_LOCK_UNLOCK),
    AppConfig.hookKey(AppConfig.SCOPE_ZEUS, AppConfig.H_SPOOF_DEVICE),
)


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
        HookDef(AppConfig.H_USB_AUTH, "USB 授权弹窗", "插拔 UVC 摄像头等设备时，让系统授权弹窗正常弹出",
            { it.hSystemUsbAuth }),
        HookDef(AppConfig.H_USB_AUTH_DIAG, "USB 授权诊断日志", "记录 USB 授权请求来源，便于排查不弹窗问题",
            { it.hSystemUsbDiag }),
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
        HookDef(AppConfig.H_LOCK_UNLOCK, "锁屏方式恢复", "恢复滑动 / PIN / 图案 / 密码的选择入口",
            { it.hSettingsLockUnlock }),
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
    AppConfig.SCOPE_SYSTEMUI -> listOf(
        HookDef(AppConfig.H_QS_FIX, "控制中心修复", "下拉菜单可完整展开、按钮恢复分页与排版",
            { it.hSystemuiQsFix }),
    )
    else -> emptyList()
}

/** 参数行（system=输入法设置；zeus=设备信息伪装参数） */
private sealed class Param {
    object ImeMode : Param()
    object ImeList : Param()
    object ZeusModel : Param()
    object ZeusSn : Param()
    object QsColumns : Param()
    object QsMaxRows : Param()
    object QsFooterFg : Param()
    object QsTileText : Param()
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

    /**
     * 统一写入入口。
     *
     * 写成功后必须 cfgTick++：页面用 remember(cfgTick){ AppConfig.refresh() }
     * 取值，不 bump 就会一直显示旧值（实测：SN 伪装/日志级别改完必须返回再进
     * 才生效）。所有写配置的分支一律走这里，避免再漏。
     */
    fun save(block: () -> Boolean): Boolean {
        if (block()) {
            cfgTick++
            return true
        }
        Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
        return false
    }

    fun setHook(hook: String, checked: Boolean) {
        val key = AppConfig.hookKey(scope, hook)
        val ok = save { AppConfig.setBoolean(key, checked) }
        if (ok && checked && key !in NEEDS_RESTART_ON_ENABLE) {
            Toast.makeText(context, R.string.hook_need_restart,
                Toast.LENGTH_LONG).show()
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
        if (scope == AppConfig.SCOPE_SYSTEMUI && cfg.hSystemuiQsFix) {
            item { SmallTitle(text = "控制中心排版参数") }
            item {
                ArrowPreference(
                    title = "按钮列数",
                    summary = "当前 ${cfg.qsColumns} 列（可选 1–6）",
                    onClick = { param = Param.QsColumns },
                )
            }
            item {
                ArrowPreference(
                    title = "最大行数",
                    summary = "当前 ${cfg.qsMaxRows} 行（可选 1–3）",
                    onClick = { param = Param.QsMaxRows },
                )
            }
            item {
                ArrowPreference(
                    title = "底栏按钮颜色",
                    summary = colorSummary("底栏铅笔/齿轮", cfg.qsFooterFg),
                    onClick = { param = Param.QsFooterFg },
                )
            }
            item {
                ArrowPreference(
                    title = "编辑页按钮文字颜色",
                    summary = colorSummary("编辑页文字", cfg.qsTileTextDark),
                    onClick = { param = Param.QsTileText },
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
                save { AppConfig.setInt(AppConfig.K_INPUT_METHOD_MODE, v) }
            },
            onCancel = { param = null },
        )
        is Param.ImeList -> ImeListDialog(
            selected = cfg.inputMethodList,
            context = context,
            onPick = { list ->
                param = null
                save { AppConfig.setString(AppConfig.K_INPUT_METHOD_LIST, list) }
            },
            onCancel = { param = null },
        )
        is Param.ZeusModel -> ZeusModelDialog(
            context = context,
            onPick = { v ->
                param = null
                save { AppConfig.setString(AppConfig.K_ZEUS_MODEL, v) }
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
                if (save { AppConfig.setString(AppConfig.K_ZEUS_SN, v.trim()) }) {
                    Toast.makeText(context, "序列号已保存", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { param = null },
        )
        is Param.QsColumns -> NumberPickDialog(
            title = "按钮列数",
            values = (1..6).toList(),
            current = cfg.qsColumns,
            extra = "列数决定每个按钮的宽度，过多会让按钮难以点击。",
            onPick = { v ->
                param = null
                save { AppConfig.setInt(AppConfig.K_QS_COLUMNS, v) }
            },
            onCancel = { param = null },
        )
        is Param.QsMaxRows -> NumberPickDialog(
            title = "最大行数",
            values = (1..3).toList(),
            current = cfg.qsMaxRows,
            extra = "行数决定面板高度，行数过多可能挤占通知区域。",
            onPick = { v ->
                param = null
                save { AppConfig.setInt(AppConfig.K_QS_MAX_ROWS, v) }
            },
            onCancel = { param = null },
        )
        is Param.QsFooterFg -> ColorPickDialog(
            title = "底栏按钮颜色",
            current = cfg.qsFooterFg,
            extra = "底栏的铅笔、齿轮与版本号文字。深色背景建议选浅色。",
            onPick = { v ->
                param = null
                save { AppConfig.setInt(AppConfig.K_QS_FOOTER_FG, v) }
            },
            onCancel = { param = null },
        )
        is Param.QsTileText -> ColorPickDialog(
            title = "编辑页按钮文字颜色",
            current = cfg.qsTileTextDark,
            extra = "编辑页（长按齿轮进入）里各按钮的名称文字。",
            onPick = { v ->
                param = null
                save { AppConfig.setInt(AppConfig.K_QS_TILE_TEXT_DARK, v) }
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
/* ---------------- QS 排版参数对话框 ---------------- */

/** 颜色摘要文案 */
private fun colorSummary(what: String, argb: Int): String {
    val a = (argb ushr 24) and 0xFF
    val hex = String.format("#%06X", argb and 0xFFFFFF)
    val alpha = if (a == 0xFF) "不透明" else "透明度 ${(a * 100 / 255)}%"
    return "$what：$hex（$alpha）"
}

/**
 * 数字选择对话框（列数/行数）。
 *
 * values 已经过取值域约束（1–6 / 1–3），且 current 若不在列表内会被拉回，
 * 所以这里不存在把非法值写进配置的可能。
 */
@Composable
private fun NumberPickDialog(
    title: String,
    values: List<Int>,
    current: Int,
    extra: String,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    // current 兜底：不在候选内则取最接近的合法值
    val safeCurrent = if (current in values) current else values.first()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = extra,
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                values.forEach { v ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = v == safeCurrent,
                                onValueChange = { if (it) onPick(v) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = v == safeCurrent, onCheckedChange = null)
                        Text(
                            text = "$v${if (v == safeCurrent) "（当前）" else ""}",
                            fontSize = 15.sp,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * 颜色选择对话框：从一组安全预设色里挑。
 *
 * 不做自由取色器：只提供经过对比度考量的常用色，避免用户选出
 * 与背景同色导致"按钮看不见"这类难排查的问题。
 */
@Composable
private fun ColorPickDialog(
    title: String,
    current: Int,
    extra: String,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val presets = listOf(
        "纯白" to 0xFFFFFFFF.toInt(),
        "浅灰" to 0xFFE0E0E0.toInt(),
        "深灰黑" to 0xFF212121.toInt(),
        "纯黑" to 0xFF000000.toInt(),
        "蓝灰" to 0xFF9E9E9E.toInt(),
        "品牌蓝" to 0xFF3482FF.toInt(),
        "半透明白" to 0x99FFFFFF.toInt(),
    )
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = extra,
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                presets.forEach { (name, v) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(v) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 色块
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(
                                    Color(v),
                                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                ),
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                text = name + (if (v == current) "（当前）" else ""),
                                fontSize = 14.sp,
                            )
                            Text(
                                text = String.format("#%08X", v),
                                fontSize = 11.sp,
                                color = Color(0xFF8A8A8E),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
