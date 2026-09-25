package cn.cf3012.xdf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
    data class Found(
        val tag: String,
        val versionName: String,
        val note: String,
        val apkUrl: String,
        val releaseUrl: String,
    ) : UpdateState()
    data class Latest(val displayName: String, val versionCode: Int) : UpdateState()
    data class Error(val msg: String) : UpdateState()
}

/** 下载/安装过程态（覆盖在更新弹窗之上） */
private sealed class BusyState {
    data class Downloading(val percent: Int) : BusyState()
    object Installing : BusyState()
    data class Message(val text: String) : BusyState()
}

/** 选项页：日志设置 + 关于/更新/仓库（其余功能已迁入各 scope 详情页） */
@Composable
fun SettingsScreen(tick: Int, context: Context) {
    // 本地刷新 tick：写配置后自增，避免受控开关被旧值弹回
    var cfgTick by remember { mutableStateOf(tick) }
    val cfg = remember(cfgTick) { AppConfig.refresh() }
    var dialog by remember { mutableStateOf<LogDialog?>(null) }
    var about by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState?>(null) }
    var busy by remember { mutableStateOf<BusyState?>(null) }

    // 下载 APK（可选：下载完直接 root 静默安装）
    fun startDownload(found: UpdateState.Found, thenInstall: Boolean) {
        busy = BusyState.Downloading(0)
        ApkUpdater.download(
            context, found.apkUrl,
            onProgress = { got, total ->
                if (total > 0) {
                    busy = BusyState.Downloading(((got * 100) / total).toInt())
                }
            },
            onDone = { file, err ->
                if (file == null) {
                    busy = BusyState.Message(err ?: "下载失败")
                } else if (!thenInstall) {
                    busy = BusyState.Message(
                        context.getString(R.string.update_downloaded_fmt, file.absolutePath),
                    )
                } else {
                    busy = BusyState.Installing
                    ApkUpdater.installWithRoot(context, file) { ok, msg ->
                        busy = if (ok) {
                            BusyState.Message(context.getString(R.string.update_install_ok))
                        } else {
                            BusyState.Message(
                                context.getString(R.string.update_install_fail_fmt, msg),
                            )
                        }
                    }
                }
            },
        )
    }

    fun setOr(key: String, value: Boolean) {
        if (AppConfig.setBoolean(key, value)) {
            cfgTick++
        } else {
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
                summary = "当前：" + stringResource(
                    R.string.log_cap_value_fmt, cfg.logFileCapKb,
                ),
                onClick = { dialog = LogDialog.Cap },
            )
        }
        item {
            val levelNameRes = LEVEL_NAMES.getOrNull(
                LEVEL_VALUES.indexOf(cfg.logLevel),
            ) ?: R.string.level_i
            ArrowPreference(
                title = stringResource(R.string.opt_log_level),
                summary = "当前：" + stringResource(levelNameRes),
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
                            updateState = UpdateState.Found(
                                latestTag, versionName, note, apkUrl,
                                "https://github.com/chengfeng3012/XDFHook/releases/tag/$latestTag",
                            )
                        }

                        override fun onLatest(latestTag: String, versionCode: Int) {
                            // update_latest 自带 "v" 前缀，这里传不带 v 的展示名
                            updateState = UpdateState.Latest(
                                latestTag.removePrefix("v"), versionCode,
                            )
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

    busy?.let { b ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.action_update)) },
            text = {
                when (b) {
                    is BusyState.Downloading -> Text(
                        stringResource(
                            R.string.update_downloading_fmt, b.percent,
                        ),
                    )
                    is BusyState.Installing ->
                        Text(stringResource(R.string.update_installing))
                    is BusyState.Message -> Text(b.text)
                }
            },
            confirmButton = {
                TextButton(onClick = { busy = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
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
                title = { Text(stringResource(R.string.action_update)) },
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
                title = { Text(stringResource(R.string.update_available)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(
                                R.string.update_found_body,
                                state.tag,
                                state.versionName,
                                "",
                            ).trim(),
                            fontSize = 14.sp,
                            color = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                        if (state.note.isNotBlank()) {
                            MarkdownText(
                                markdown = state.note,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    // 四个动作：取消 / 打开 Release 页 / 下载 APK / 下载并安装
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = { updateState = null }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                            TextButton(onClick = {
                                openUrl(context, state.releaseUrl)
                                updateState = null
                            }) {
                                Text(stringResource(R.string.update_open_release), fontSize = 12.sp)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = { startDownload(state, thenInstall = false) }) {
                                Text(stringResource(R.string.update_download_only), fontSize = 12.sp)
                            }
                            TextButton(onClick = { startDownload(state, thenInstall = true) }) {
                                Text(
                                    stringResource(R.string.update_download_install),
                                    fontSize = 12.sp,
                                    color = androidx.compose.ui.graphics.Color(0xFF3482FF),
                                )
                            }
                        }
                    }
                },
            )
            is UpdateState.Latest -> AlertDialog(
                onDismissRequest = { updateState = null },
                title = { Text(stringResource(R.string.action_update)) },
                text = {
                    Text(
                        stringResource(
                            R.string.update_latest, state.displayName, state.versionCode,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { updateState = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
            is UpdateState.Error -> AlertDialog(
                onDismissRequest = { updateState = null },
                title = { Text(stringResource(R.string.action_update)) },
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