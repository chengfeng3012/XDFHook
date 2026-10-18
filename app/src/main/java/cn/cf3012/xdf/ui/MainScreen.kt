package cn.cf3012.xdf.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.cf3012.xdf.AppConfig
import cn.cf3012.xdf.DeviceGate
import cn.cf3012.xdf.R
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底部导航 tab 定义（与旧 XML 版一致：概览/日志/操作/设置） */
private data class TabDef(
    val titleRes: Int,
    val iconRes: Int,
)

private val TABS = listOf(
    TabDef(R.string.tab_overview, R.drawable.ic_tab_overview),
    TabDef(R.string.tab_logs, R.drawable.ic_tab_logs),
    TabDef(R.string.tab_operation, R.drawable.ic_tab_operation),
    TabDef(R.string.tab_settings, R.drawable.ic_tab_settings),
)

/**
 * 页面宿主：Miuix Scaffold + TopAppBar + 底部 NavigationBar，四页切换。
 *
 * 另外承载**设备门禁**的 UI 表现（见 [DeviceGate]）：
 *   - 门禁不通过时，内容区顶部常驻一条警告横幅（可点开详情）；
 *   - 每次打开 App，若门禁不通过且本次会话尚未提示过，自动弹一次警告对话框。
 */
@Composable
fun MainScreen(tick: Int, context: Context) {
    var tab by rememberSaveable { mutableStateOf(0) }

    // ---- 设备门禁状态 ----
    // gateWarned：本次 Activity 会话内只自动弹一次警告（避免每次切 tab 都弹）
    var gateWarned by rememberSaveable { mutableStateOf(false) }
    var showGateDialog by rememberSaveable { mutableStateOf(false) }
    var gateTick by remember { mutableStateOf(0) }
    val gate = remember(tick, gateTick) { DeviceGate.info() }
    val cfg = remember(tick, gateTick) { AppConfig.refresh() }
    val gateAllowed = gate.allowed(cfg.forceUnknownDevice)

    // 打开 App 时警告（Android 版本不符 = 硬门禁，同样告警）
    LaunchedEffect(gateAllowed) {
        if (!gateAllowed && !gateWarned) {
            showGateDialog = true
            gateWarned = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = stringResource(TABS[tab].titleRes))
        },
        bottomBar = {
            MainNavigationBar(
                selected = tab,
                onSelect = { tab = it },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!gateAllowed) {
                DeviceGateBanner(gate = gate, onOpen = { showGateDialog = true })
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> OverviewScreen(tick = tick, context = context)
                    1 -> LogsScreen(tick = tick, context = context)
                    2 -> OperationScreen(tick = tick, context = context)
                    else -> SettingsScreen(tick = tick, context = context)
                }
            }
        }
    }

    if (showGateDialog) {
        DeviceGateDialog(
            gate = gate,
            onForceEnable = {
                AppConfig.setBoolean(AppConfig.K_FORCE_DEVICE, true)
                showGateDialog = false
                gateTick++
            },
            onDismiss = { showGateDialog = false },
        )
    }
}

/**
 * 门禁警告横幅：门禁不通过时贴在内容区顶部。
 * 红 = Android 版本不符（不可绕过）；黄 = 机型不符（可在设置里强制开启）。
 */
@Composable
private fun DeviceGateBanner(gate: DeviceGate.Info, onOpen: () -> Unit) {
    val bg = if (!gate.sdkOk) Color(0xFFFDECEA) else Color(0xFFFFF4CE)
    val fg = if (!gate.sdkOk) Color(0xFFB3261E) else Color(0xFF7A5900)
    val title = if (!gate.sdkOk) {
        "已停止注入：Android 版本不受支持"
    } else {
        "已停止注入：未检测到 XDF-N1"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, color = fg, fontWeight = FontWeight.Bold)
            Text(text = gate.describe() + "　(点击查看详情)", fontSize = 11.sp, color = fg)
        }
    }
}

/**
 * 门禁警告对话框。
 *
 * - Android 版本不符：只给「知道了」，无任何绕过途径。
 * - 机型不符：默认「保持禁用」；另给一条**明确标注风险**的「我已知晓，仍要注入」
 *   （写入 [AppConfig.K_FORCE_DEVICE]，系统会重新评估门禁）。
 */
@Composable
private fun DeviceGateDialog(
    gate: DeviceGate.Info,
    onForceEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sdkBlocked = !gate.sdkOk
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (sdkBlocked) "不支持的 Android 版本" else "未检测到 XDF-N1")
        },
        text = {
            Column {
                Text(
                    text = "当前设备：" + gate.describe(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (sdkBlocked) {
                        "\n本模块只支持 Android 10（API 29）。当前系统不是 Android 10，" +
                            "为避免损坏设备，注入已被强制禁用，且无法通过任何设置绕过。"
                    } else {
                        "\n本模块的 Hook 全部是针对 XDF-N1（学习机，MT8788 / Android 10）" +
                            "逐条逆向写死的（类名、方法签名、资源 id 都取自该 ROM）。" +
                            "在其它机型上运行轻则全部无效，重则把系统进程搞崩。\n\n" +
                            "因此已默认停止注入。只有当你明确知道自己在做什么" +
                            "（例如你手里就是该机型的 ROM，并核对过这些 Hook 点）时，" +
                            "才建议启用强制注入。"
                    },
                    fontSize = 13.sp,
                )
                if (!sdkBlocked) {
                    Text(
                        text = "\n开关位置：设置页 → 设备校验",
                        fontSize = 12.sp,
                        color = Color(0xFF8A8A8E),
                    )
                }
            }
        },
        confirmButton = {
            if (!sdkBlocked) {
                TextButton(onClick = onForceEnable) {
                    Text("我已知晓，仍要注入", color = Color(0xFFB3261E))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (sdkBlocked) "知道了" else "保持禁用")
            }
        },
    )
}

/** 底部导航条（Miuix NavigationBar，图标+文字，选中态 miuix 蓝） */
@Composable
private fun MainNavigationBar(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar(
        mode = NavigationBarDisplayMode.IconAndText,
    ) {
        TABS.forEachIndexed { index, tab ->
            val selectedNow = index == selected
            val tint = if (selectedNow) Color(0xFF3482FF) else Color(0xFF8A8A8E)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .selectable(
                        selected = selectedNow,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(tab.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(tint),
                )
                Text(
                    text = stringResource(tab.titleRes),
                    fontSize = 10.sp,
                    color = tint,
                )
            }
        }
    }
}
