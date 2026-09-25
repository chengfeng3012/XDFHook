package cn.cf3012.xdf.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

import cn.cf3012.xdf.R
import cn.cf3012.xdf.Root
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.preference.SwitchPreference

/* ---------------- 命令常量（与原 OperationPage 一致） ---------------- */

private const val GT_SYS = "settings get system accelerometer_rotation"
private const val GT_UI = "settings get secure ui_night_mode"
private const val GT_NAV = "settings get secure navigation_mode"

private const val CMD_ROTATE =
    "if [ \"$(settings get system accelerometer_rotation)\" = \"1\" ]; then " +
        "settings put system accelerometer_rotation 0; " +
        "else settings put system accelerometer_rotation 1; fi"
private const val CMD_DARK =
    "if [ \"$(settings get secure ui_night_mode)\" = \"2\" ]; then " +
        "settings put secure ui_night_mode 1; " +
        "else settings put secure ui_night_mode 2; fi"
// 导航切换方向区分：切三键需 kill systemui 立即生效；切全面屏不 kill
private const val CMD_NAV =
    "mode=\$(settings get secure navigation_mode); " +
        "if [ \"\$mode\" = \"2\" ]; then settings put secure navigation_mode 0; " +
        "killall -9 com.android.systemui; " +
        "else settings put secure navigation_mode 2; " +
        "cmd overlay enable com.android.internal.systemui.navbar.gestural; fi"
private const val CMD_REFRESH_MEDIA =
    "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/"
private const val CMD_IME = "am start -a android.settings.INPUT_METHOD_SETTINGS"
private const val CMD_DESKTOP = "am start -a android.settings.HOME_SETTINGS"
private fun cmdOrient(deg: Int) =
    "settings put system accelerometer_rotation 0; settings put system user_rotation $deg"
private const val CMD_SOFT_REBOOT = "kill -9 \$(pidof system_server)"
private const val CMD_HARD_REBOOT = "reboot"
private const val CMD_KILL_SYSUI = "killall -9 com.android.systemui"

/* ---------------- 动作模型 ---------------- */

private const val T_NONE = 0
private const val T_ROTATE = 1
private const val T_DARK = 2
private const val T_NAV = 3

private class FuncAction(
    val title: Int,
    val short: Int,
    val color: Long,
    val cmd: String? = null,
    val toggleType: Int = T_NONE,
    val labelOn: Int = 0,
    val labelOff: Int = 0,
    val getCmd: String? = null,
    val onValue: String? = null,
    val orientationMenu: Boolean = false,
)

private val FUNC_ACTIONS = listOf(
    FuncAction(R.string.op_act_rotate, 0, 0xFF3482FF, CMD_ROTATE, T_ROTATE,
        R.string.op_btn_off, R.string.op_btn_on, GT_SYS, "1"),
    FuncAction(R.string.op_act_orientation, R.string.op_act_orientation_short,
        0xFF00B8D9, orientationMenu = true),
    FuncAction(R.string.op_act_dark, 0, 0xFF7C5CFF, CMD_DARK, T_DARK,
        R.string.op_btn_light, R.string.op_btn_deep, GT_UI, "2"),
    FuncAction(R.string.op_act_nav, 0, 0xFFFF8A00, CMD_NAV, T_NAV,
        R.string.op_btn_three, R.string.op_btn_gesture, GT_NAV, "2"),
    FuncAction(R.string.op_act_ime, R.string.op_act_ime_short, 0xFF34C759, CMD_IME),
    FuncAction(R.string.op_act_desktop, R.string.op_act_desktop_short, 0xFFFF6482, CMD_DESKTOP),
    FuncAction(R.string.op_act_media, R.string.op_act_media_short, 0xFFFFC42E, CMD_REFRESH_MEDIA),
)

private class DangerAction(val title: Int, val short: Int, val color: Long,
                           val confirmTitle: Int, val confirmMsg: Int, val cmd: String)

private val DANGER_ACTIONS = listOf(
    DangerAction(R.string.op_act_soft, R.string.op_act_soft_short, 0xFFFFB340,
        R.string.op_confirm_soft_title, R.string.op_confirm_soft_msg, CMD_SOFT_REBOOT),
    DangerAction(R.string.op_act_hard, R.string.op_act_hard_short, 0xFFFF6369,
        R.string.op_confirm_hard_title, R.string.op_confirm_hard_msg, CMD_HARD_REBOOT),
    DangerAction(R.string.op_act_sysui, R.string.op_act_sysui_short, 0xFFFF6482,
        R.string.op_confirm_sysui_title, R.string.op_confirm_sysui_msg, CMD_KILL_SYSUI),
)

/* ---------------- root 状态缓存（进程内） ---------------- */

/**
 * root 探测结果在进程内缓存。
 *
 * 默认关闭且**不主动探测**（进入页面不会弹 Magisk 授权）：
 *   - 用户打开 root 开关 → probe() 请求授权；成功则 ok=true（面板出现），
 *     失败则保持关闭（开关自动弹回）
 *   - 用户关闭 root 开关 → clear() 隐藏面板，不再请求 root
 * su -c id 会触发 Magisk 提示，因此探测只由用户操作驱动，绝不随重组重复发起。
 */
private object RootState {
    var ok by mutableStateOf(false)
    var probing by mutableStateOf(false)

    fun probe() {
        if (probing) return
        probing = true
        Thread {
            val r = Root.available()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ok = r
                probing = false
            }
        }.start()
    }

    /** 用户主动关闭：隐藏面板，不再请求 root */
    fun clear() {
        ok = false
    }
}

/* ---------------- 页面 ---------------- */

@Composable
fun OperationScreen(tick: Int, context: Context) {
    val rootOk = RootState.ok
    val probing = RootState.probing
    var toggleLabels by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var showOrient by remember { mutableStateOf(false) }
    var showSilent by remember { mutableStateOf(false) }
    var dangerTarget by remember { mutableStateOf<DangerAction?>(null) }
    var apkPath by remember { mutableStateOf("") }

    // 选择 APK：系统文件选择器（返回 content:// URI，复制到缓存后回填真实路径）
    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val copied = runCatching { copyApkToCache(context, it) }.getOrNull()
            if (copied != null) {
                apkPath = copied
            } else {
                Toast.makeText(context, "读取所选 APK 失败", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // toggle 当前状态：仅在 root 可用时读取一次，不随 tick 重跑
    LaunchedEffect(rootOk) {
        if (!rootOk) {
            toggleLabels = emptyMap()
            return@LaunchedEffect
        }
        val labels = mutableMapOf<Int, Int>()
        FUNC_ACTIONS.filter { it.toggleType != T_NONE }.forEach { a ->
            val v = Root.get(a.getCmd.orEmpty()).trim()
            labels[a.title] = if (v == a.onValue) a.labelOff else a.labelOn
        }
        toggleLabels = labels
    }

    fun runCommand(cmd: String, done: (() -> Unit)? = null) {
        Toast.makeText(context, R.string.op_running, Toast.LENGTH_SHORT).show()
        Thread {
            val ok = Root.exec(cmd)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    if (ok) R.string.op_exec_done else R.string.op_exec_fail,
                    Toast.LENGTH_SHORT,
                ).show()
                done?.invoke()
            }
        }.start()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            SwitchPreference(
                checked = rootOk,
                // 开 = 请求 root 授权（失败自动弹回关闭）；关 = 隐藏全部操作面板
                onCheckedChange = { checked ->
                    if (checked) {
                        if (!probing) RootState.probe()
                    } else {
                        RootState.clear()
                    }
                },
                enabled = !probing,
                title = stringResource(R.string.op_root_title),
                summary = when {
                    probing -> stringResource(R.string.op_root_checking)
                    rootOk -> stringResource(R.string.op_root_subtitle_ok)
                    else -> stringResource(R.string.op_root_subtitle_off)
                },
            )
        }
        if (!rootOk) {
            item {
                Text(
                    text = stringResource(R.string.op_root_hint),
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        if (rootOk) {
        item {
            SmallTitle(text = stringResource(R.string.op_section_function))
        }
        item {
            // 功能按钮：8 个 = 4+4 两行网格（静默安装并入第 2 行第 4 位）
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                buildList {
                    addAll(FUNC_ACTIONS)
                    add(null) // 静默安装
                }.chunked(4).forEach { rowActs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowActs.forEach { a ->
                            if (a == null) {
                                SilentInstallCircle(
                                    modifier = Modifier.weight(1f),
                                    enabled = rootOk,
                                    onClick = { showSilent = true },
                                )
                            } else {
                                FuncCircle(
                                    modifier = Modifier.weight(1f),
                                    action = a,
                                    labelRes = if (a.toggleType != T_NONE)
                                        toggleLabels[a.title] ?: 0 else 0,
                                    enabled = rootOk,
                                    onClick = {
                                        when {
                                            a.orientationMenu -> showOrient = true
                                            a.cmd != null -> runCommand(a.cmd) {}
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = "按钮中央文字为当前状态，点击后切换。",
                fontSize = 12.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        item {
            SmallTitle(text = stringResource(R.string.op_section_danger))
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DANGER_ACTIONS.forEach { d ->
                    DangerCircle(
                        modifier = Modifier.weight(1f),
                        action = d,
                        enabled = rootOk,
                        onClick = { dangerTarget = d },
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.op_footnote),
                fontSize = 12.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        }
    }

    // 固定朝向 4 选 1
    if (showOrient) {
        val names = listOf(
            R.string.op_orient_portrait,
            R.string.op_orient_landscape,
            R.string.op_orient_rev_portrait,
            R.string.op_orient_rev_landscape,
        )
        AlertDialog(
            onDismissRequest = { showOrient = false },
            title = { Text(stringResource(R.string.op_orient_title)) },
            text = {
                Column {
                    names.forEachIndexed { idx, res ->
                        Text(
                            text = stringResource(res),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showOrient = false
                                    runCommand(cmdOrient(idx)) {}
                                }
                                .padding(vertical = 10.dp),
                            fontSize = 15.sp,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOrient = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 静默安装
    if (showSilent) {
        val okClick = {
            val path = apkPath.trim()
            if (path.isEmpty()) {
                Toast.makeText(context, R.string.op_silent_install_empty, Toast.LENGTH_SHORT).show()
            } else {
                runCommand("pm install -r -t --user 0 '$path'") {
                    Toast.makeText(context, R.string.op_silent_install_ok, Toast.LENGTH_SHORT).show()
                }
                showSilent = false
            }
        }
        AlertDialog(
            onDismissRequest = { showSilent = false },
            title = { Text(stringResource(R.string.op_silent_install_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = apkPath,
                        onValueChange = { apkPath = it },
                        placeholder = { Text(stringResource(R.string.op_silent_install_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.op_silent_install_pick),
                        fontSize = 14.sp,
                        color = Color(0xFF3482FF),
                        modifier = Modifier
                            .clickable { apkPicker.launch("application/vnd.android.package-archive") }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = okClick) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSilent = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 危险操作确认
    dangerTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { dangerTarget = null },
            title = { Text(stringResource(d.confirmTitle)) },
            text = { Text(stringResource(d.confirmMsg)) },
            confirmButton = {
                TextButton(onClick = {
                    dangerTarget = null
                    runCommand(d.cmd) {}
                }) {
                    Text(stringResource(R.string.op_confirm_ok), color = Color(0xFFFF6369))
                }
            },
            dismissButton = {
                TextButton(onClick = { dangerTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** 功能圆钮：圆内短字（toggle 为下一步文字/静态为短字），下方标题 */
@Composable
private fun FuncCircle(
    modifier: Modifier = Modifier,
    action: FuncAction,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(if (action.color > 0) action.color else 0xFF3482FF), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    labelRes != 0 -> stringResource(labelRes)
                    action.short != 0 -> stringResource(action.short)
                    else -> stringResource(action.title)
                },
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(action.title),
            fontSize = 11.sp,
            color = Color(0xFF3C3C43),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** 静默安装（颜色风格一致的独立圆钮） */
@Composable
private fun SilentInstallCircle(modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0xFF00BCD4), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.op_act_silent_install_short),
                color = Color.White,
                fontSize = 13.sp,
            )
        }
        Text(
            text = stringResource(R.string.op_act_silent_install),
            fontSize = 11.sp,
            color = Color(0xFF3C3C43),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** 危险操作圆钮（淡红体验证态保持原色） */
@Composable
private fun DangerCircle(modifier: Modifier = Modifier, action: DangerAction, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(action.color), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(action.short),
                color = Color.White,
                fontSize = 13.sp,
            )
        }
        Text(
            text = stringResource(action.title),
            fontSize = 11.sp,
            color = Color(0xFF3C3C43),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** 把 content:// URI 指向的 APK 复制到缓存目录，返回真实文件路径（pm install 需要真实路径） */
private fun copyApkToCache(context: Context, uri: Uri): String? {
    val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: "picked.apk"
    val out = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    return out.absolutePath
}
