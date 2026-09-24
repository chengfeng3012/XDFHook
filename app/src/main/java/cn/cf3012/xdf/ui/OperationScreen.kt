package cn.cf3012.xdf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
private const val CMD_NAV =
    "mode=\$(settings get secure navigation_mode); " +
        "if [ \"\$mode\" = \"2\" ]; then settings put secure navigation_mode 0; " +
        "else settings put secure navigation_mode 2; fi; " +
        "cmd overlay enable com.android.internal.systemui.navbar.gestural; " +
        "killall -9 com.android.systemui"
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

/* ---------------- 页面 ---------------- */

@Composable
fun OperationScreen(tick: Int, context: Context) {
    var rootOk by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(true) }
    var toggleLabels by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var showOrient by remember { mutableStateOf(false) }
    var showSilent by remember { mutableStateOf(false) }
    var dangerTarget by remember { mutableStateOf<DangerAction?>(null) }
    var apkPath by remember { mutableStateOf("") }

    // root 探测
    LaunchedEffect(tick) {
        probing = true
        rootOk = Root.available()
        probing = false
    }
    // toggle 状态后台读取（原 label 显示"将切换到的状态"）
    LaunchedEffect(tick, rootOk) {
        if (!rootOk) return@LaunchedEffect
        val labels = mutableMapOf<Int, Int>()
        FUNC_ACTIONS.filter { it.toggleType != T_NONE }.forEach { a ->
            val v = Root.get(a.getCmd.orEmpty()).trim()
            labels[a.title] = if (v == a.onValue) a.labelOn else a.labelOff
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
                onCheckedChange = { },
                enabled = false,
                title = stringResource(R.string.op_root_title),
                summary = when {
                    probing -> stringResource(R.string.op_root_checking)
                    rootOk -> stringResource(R.string.op_root_subtitle_ok)
                    else -> stringResource(R.string.op_root_subtitle_off)
                },
            )
        }
        item {
            Text(
                text = stringResource(R.string.op_root_footnote),
                fontSize = 12.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        item {
            SmallTitle(text = stringResource(R.string.op_section_function))
        }
        item {
            // 功能圆钮：7 个 + 静默安装，每行 4 个
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                FUNC_ACTIONS.chunked(4).forEach { rowActs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        rowActs.forEach { a ->
                            FuncCircle(
                                action = a,
                                labelRes = if (a.toggleType != T_NONE)
                                    toggleLabels[a.title] ?: 0 else 0,
                                enabled = rootOk || a.cmd != null || a.orientationMenu,
                                onClick = {
                                    when {
                                        a.orientationMenu -> showOrient = true
                                        a.title == R.string.op_act_silent_install ->
                                            showSilent = true
                                        a.cmd != null -> runCommand(a.cmd) {
                                            // toggle 刷新
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                // 静默安装钮追加到末尾行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SilentInstallCircle(
                        enabled = rootOk,
                        onClick = { showSilent = true },
                    )
                }
            }
        }
        item {
            SmallTitle(text = stringResource(R.string.op_section_danger))
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DANGER_ACTIONS.forEach { d ->
                    DangerCircle(
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
                OutlinedTextField(
                    value = apkPath,
                    onValueChange = { apkPath = it },
                    placeholder = { Text(stringResource(R.string.op_silent_install_hint)) },
                    singleLine = true,
                )
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
    action: FuncAction,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .padding(vertical = 4.dp),
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
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(action.title),
            fontSize = 11.sp,
            color = Color(0xFF3C3C43),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 静默安装（颜色风格一致的独立圆钮） */
@Composable
private fun SilentInstallCircle(enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(76.dp).padding(vertical = 4.dp),
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
                fontSize = 12.sp,
            )
        }
        Text(
            text = stringResource(R.string.op_act_silent_install),
            fontSize = 11.sp,
            color = Color(0xFF3C3C43),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 危险操作圆钮（淡红体验证态保持原色） */
@Composable
private fun DangerCircle(action: DangerAction, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(76.dp).padding(vertical = 4.dp),
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
                fontSize = 12.sp,
            )
        }
        Text(
            text = stringResource(action.title),
            fontSize = 11.sp,
            color = Color(0xFF3C3C43),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}