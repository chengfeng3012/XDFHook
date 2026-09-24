package cn.cf3012.xdf.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.cf3012.xdf.AppConfig
import cn.cf3012.xdf.XposedServiceHolder
import cn.cf3012.xdf.R
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 模块开关定义（总开关之外的功能域） */
private val MODULES = listOf(
    androidx.compose.ui.graphics.Color(0xFF7C5CFF) to R.string.mod_zeus,
    androidx.compose.ui.graphics.Color(0xFF3482FF) to R.string.mod_settings,
    androidx.compose.ui.graphics.Color(0xFF00B8D9) to R.string.mod_chooser,
    androidx.compose.ui.graphics.Color(0xFF34C759) to R.string.mod_launcher,
    androidx.compose.ui.graphics.Color(0xFFFFC42E) to R.string.mod_home,
    androidx.compose.ui.graphics.Color(0xFFFF6482) to R.string.mod_gallery,
)

private val MODULE_KEYS = listOf(
    AppConfig.K_ZEUS,
    AppConfig.K_SETTINGS,
    AppConfig.K_CHOOSER,
    AppConfig.K_LAUNCHER,
    AppConfig.K_HOME,
    AppConfig.K_GALLERY,
)

private val MODULE_DESCS = listOf(
    R.string.mod_zeus_desc,
    R.string.mod_settings_desc,
    R.string.mod_chooser_desc,
    R.string.mod_launcher_desc,
    R.string.mod_home_desc,
    R.string.mod_gallery_desc,
)

/** 概览页：总开关 + 功能域模块开关 + 环境/运行状态 */
@Composable
fun OverviewScreen(tick: Int, context: Context) {
    val cfg = remember { AppConfig.refresh() }
    androidx.compose.runtime.LaunchedEffect(tick) { }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            SwitchPreference(
                checked = cfg.master,
                onCheckedChange = { checked ->
                    if (!AppConfig.setBoolean(AppConfig.K_MASTER, checked)) {
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
        MODULES.forEachIndexed { i, pair ->
            item {
                SwitchPreference(
                    checked = when (i) {
                        0 -> cfg.modZeus
                        1 -> cfg.modSettings
                        2 -> cfg.modChooser
                        3 -> cfg.modLauncher
                        4 -> cfg.modHome
                        else -> cfg.modGallery
                    },
                    onCheckedChange = { checked ->
                        if (!AppConfig.setBoolean(MODULE_KEYS[i], checked)) {
                            Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                        }
                    },
                    title = stringResource(pair.second),
                    summary = stringResource(MODULE_DESCS[i]),
                    startAction = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(pair.first, CircleShape),
                        )
                    },
                )
            }
        }
        item {
            EnvironmentLine(tick = tick, context = context)
        }
        item {
            Text(
                text = stringResource(R.string.overview_footnote),
                fontSize = 12.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}

/** 环境状态行：服务连接 + 运行中的应用数（一行轻量呈现） */
@Composable
private fun EnvironmentLine(tick: Int, context: Context) {
    val connected = XposedServiceHolder.isConnected()
    val targets = remember(tick) { AppConfig.runningTargets() }
    val envText = if (connected) {
        stringResource(R.string.env_service_ok, BuildVersion.fw())
    } else {
        stringResource(R.string.env_service_off)
    }
    val tail = if (targets.isEmpty()) {
        stringResource(R.string.state_off)
    } else {
        stringResource(R.string.state_ok) + " · " + targets.size
    }
    Text(
        text = "·  $envText    $tail",
        fontSize = 13.sp,
        color = Color(0xFF8A8A8E),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

/** 内部工具：固件/版本串 */
internal object BuildVersion {
    fun fw(): String {
        return try {
            android.os.Build.VERSION.RELEASE ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}