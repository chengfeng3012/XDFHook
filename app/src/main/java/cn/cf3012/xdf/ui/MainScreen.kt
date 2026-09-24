package cn.cf3012.xdf.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 页面宿主：Miuix Scaffold + TopAppBar + 底部 NavigationBar，四页切换 */
@Composable
fun MainScreen(tick: Int, context: Context) {
    var tab by rememberSaveable { mutableStateOf(0) }

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                0 -> OverviewScreen(tick = tick, context = context)
                1 -> LogsScreen(tick = tick, context = context)
                2 -> OperationScreen(tick = tick, context = context)
                else -> SettingsScreen(tick = tick, context = context)
            }
        }
    }
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
                androidx.compose.material3.Text(
                    text = stringResource(tab.titleRes),
                    fontSize = 10.sp,
                    color = tint,
                )
            }
        }
    }
}