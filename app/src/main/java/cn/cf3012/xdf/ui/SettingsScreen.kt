package cn.cf3012.xdf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.cf3012.xdf.AppConfig
import cn.cf3012.xdf.R
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private val LEVEL_NAMES = listOf(
    R.string.level_v,
    R.string.level_d,
    R.string.level_i,
    R.string.level_w,
    R.string.level_e,
)
private val LEVEL_VALUES = listOf(2, 3, 4, 5, 6) // VERBOSE..ERROR

private sealed class LogDialog {
    object Level : LogDialog()
    object Path : LogDialog()
    object Cap : LogDialog()
}

private sealed class UpdateState {
    object Checking : UpdateState()
    data class Found(val tag: String, val apkUrl: String) : UpdateState()
    data class Latest(val tag: String) : UpdateState()
    data class Error(val msg: String) : UpdateState()
}

/** 选项页：日志设置 + 关于/更新/仓库（其余功能已迁入各 scope 详情页） */
@Composable
fun SettingsScreen(tick: Int, context: Context) {
    val cfg = remember(tick) { AppConfig.refresh() }
    var dialog by remember { mutableStateOf<LogDialog?>(null) }
    var about by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState?>(null) }

    fun setOr(key: String, value: Boolean) {
        if (!AppConfig.setBoolean(key, value)) {
            Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item { SmallTitle(text = stringResource(R.string.opt_log_file)) }
        item {
            SwitchPreference(
                checked = cfg.logFileEnabled,
                onCheckedChange = { setOr(AppConfig.K_LOG_FILE_ENABLED, it) },
                title = stringResource(R.string.opt_log_file),
                summary = stringResource(R.string.opt_log_file_desc),
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_log_path),
                summary = stringResource(R.string.opt_log_path_desc),
                onClick = { dialog = LogDialog.Path },
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_log_cap),
                summary = stringResource(R.string.opt_log_cap_desc),
                onClick = { dialog = LogDialog.Cap },
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_log_level),
                summary = stringResource(R.string.opt_log_level_desc),
                onClick = { dialog = LogDialog.Level },
            )
        }

        item { SmallTitle(text = stringResource(R.string.section_about)) }
        item {
            ArrowPreference(
                title = stringResource(R.string.action_about),
                onClick = { about = true },
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.action_update),
                onClick = {
                    updateState = UpdateState.Checking
                    val listener = object : GithubUpdateChecker.Listener {
                        override fun onUpdate(
                            latestTag: String,
                            versionName: String,
                            versionCode: Int,
                            apkUrl: String,
                            note: String,
                        ) {
                            updateState = UpdateState.Found(latestTag, apkUrl)
                        }

                        override fun onLatest(latestTag: String, versionCode: Int) {
                            updateState = UpdateState.Latest(latestTag)
                        }

                        override fun onError(message: String) {
                            updateState = UpdateState.Error(message)
                        }
                    }
                    GithubUpdateChecker.checkAsync(versionCode(context), listener)
                },
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.action_repo),
                onClick = {
                    openUrl(context, "https://github.com/chengfeng3012/XDFHook")
                },
            )
        }
        item {
            Text(
                text = "配置版本：v${AppConfig.VERSION}（schema 自动迁移）",
                fontSize = 12.sp,
                color = androidx.compose.ui.graphics.Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }

    when (val d = dialog) {
        LogDialog.Level -> LevelDialog(
            current = cfg.logLevel,
            onPick = { v ->
                dialog = null
                if (!AppConfig.setInt(AppConfig.K_LOG_LEVEL, v)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { dialog = null },
        )
        LogDialog.Path -> TextInputDialog(
            title = R.string.opt_log_path,
            hint = R.string.input_log_path_hint,
            initial = cfg.logFilePath ?: "",
            context = context,
            onOk = { v ->
                dialog = null
                if (v.trim().isEmpty()) {
                    AppConfig.setString(AppConfig.K_LOG_FILE_PATH, "")
                    Toast.makeText(context, R.string.toast_log_path_ok, Toast.LENGTH_SHORT).show()
                } else {
                    if (!AppConfig.setString(AppConfig.K_LOG_FILE_PATH, v.trim())) {
                        Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.toast_log_path_ok, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCancel = { dialog = null },
        )
        LogDialog.Cap -> TextInputDialog(
            title = R.string.opt_log_cap,
            hint = R.string.input_log_cap_hint,
            initial = cfg.logFileCapKb?.toString() ?: "",
            context = context,
            inputType = true,
            onOk = { v ->
                dialog = null
                val kb = v.trim().toIntOrNull()
                if (kb == null || kb <= 0 || kb > AppConfig.MAX_LOG_CAP_KB) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_log_cap_invalid, AppConfig.MAX_LOG_CAP_KB),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    if (!AppConfig.setInt(AppConfig.K_LOG_FILE_CAP_KB, kb)) {
                        Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.toast_log_cap_ok, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCancel = { dialog = null },
        )
        null -> Unit
    }

    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.about_body,
                        versionName(context),
                        versionCode(context),
                    ),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { about = false }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }

    updateState?.let { state ->
        when (state) {
            is UpdateState.Checking -> AlertDialog(
                onDismissRequest = { updateState = null },
                title = { Text(stringResource(R.string.update_dialog)) },
                text = { Text(stringResource(R.string.update_checking)) },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { updateState = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
            is UpdateState.Found -> AlertDialog(
                onDismissRequest = { updateState = null },
                title = { Text(stringResource(R.string.update_found)) },
                text = { Text(stringResource(R.string.update_found_body, state.tag, "", "")) },
                confirmButton = {
                    TextButton(onClick = {
                        openUrl(context, state.apkUrl)
                        updateState = null
                    }) {
                        Text(stringResource(R.string.update_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateState = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
            is UpdateState.Latest -> AlertDialog(
                onDismissRequest = { updateState = null },
                title = { Text(stringResource(R.string.update_dialog)) },
                text = { Text(stringResource(R.string.update_latest, state.tag, versionCode(context))) },
                confirmButton = {
                    TextButton(onClick = { updateState = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
            is UpdateState.Error -> AlertDialog(
                onDismissRequest = { updateState = null },
                title = { Text(stringResource(R.string.update_dialog)) },
                text = { Text(state.msg) },
                confirmButton = {
                    TextButton(onClick = { updateState = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
    }
}

@Composable
private fun LevelDialog(current: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.opt_log_level)) },
        text = {
            Column {
                LEVEL_NAMES.forEachIndexed { i, res ->
                    Text(
                        text = stringResource(res),
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clickable { onPick(LEVEL_VALUES[i]) },
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
private fun TextInputDialog(
    title: Int,
    hint: Int,
    initial: String,
    context: Context,
    inputType: Boolean = false,
    onOk: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(stringResource(hint)) },
                singleLine = true,
                keyboardOptions = if (inputType) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
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

internal fun versionName(context: Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }
}

internal fun versionCode(context: Context): Int {
    return try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (_: Throwable) {
        0
    }
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Throwable) {
    }
}