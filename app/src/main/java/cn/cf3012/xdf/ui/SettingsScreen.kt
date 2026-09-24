package cn.cf3012.xdf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
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

private val IME_MODES = listOf(
    R.string.opt_input_method_mode_lock,
    R.string.opt_input_method_mode_blacklist,
)

/** 设置页：全局 / 桌面锁定 / 输入法锁定 / 日志 / 安装 / Zeus / 关于 */
@Composable
fun SettingsScreen(tick: Int, context: Context) {
    val cfg = remember(tick) { AppConfig.refresh() }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var snack by remember { mutableStateOf<String?>(null) }

    // 更新检查结果状态
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
        item { SmallTitle(text = stringResource(R.string.section_global)) }
        item {
            SwitchPreference(
                checked = cfg.pmsAggressive,
                onCheckedChange = { setOr(AppConfig.K_AGGRESSIVE, it) },
                title = stringResource(R.string.opt_aggressive),
                summary = stringResource(R.string.opt_aggressive_desc),
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_log_level),
                summary = stringResource(R.string.opt_log_level_desc),
                onClick = { dialog = SettingsDialog.Level },
            )
        }

        item { SmallTitle(text = stringResource(R.string.section_ime)) }
        item {
            SwitchPreference(
                checked = cfg.modInputMethod,
                onCheckedChange = { setOr(AppConfig.K_MOD_INPUT_METHOD, it) },
                title = stringResource(R.string.opt_input_method),
                summary = stringResource(R.string.opt_input_method_desc),
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_input_method_mode),
                summary = if (cfg.inputMethodMode == 1) {
                    stringResource(R.string.opt_input_method_mode_lock_desc)
                } else {
                    stringResource(R.string.opt_input_method_mode_blacklist_desc)
                },
                onClick = { dialog = SettingsDialog.ImeMode },
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_input_method_list),
                summary = if (cfg.inputMethodList.isNullOrEmpty()) {
                    stringResource(R.string.input_method_none)
                } else {
                    stringResource(
                        R.string.input_method_selected,
                        cfg.inputMethodList.split(",").size,
                    )
                },
                onClick = { dialog = SettingsDialog.ImeList },
            )
        }

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
                onClick = { dialog = SettingsDialog.LogPath },
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_log_cap),
                summary = stringResource(R.string.opt_log_cap_desc),
                onClick = { dialog = SettingsDialog.LogCap },
            )
        }

        item { SmallTitle(text = stringResource(R.string.section_install)) }
        item {
            SwitchPreference(
                checked = cfg.modPackageInstall,
                onCheckedChange = { setOr(AppConfig.K_MOD_PACKAGE_INSTALL, it) },
                title = stringResource(R.string.opt_package_install),
                summary = stringResource(R.string.opt_package_install_desc),
            )
        }

        item { SmallTitle(text = stringResource(R.string.section_zeus)) }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_zeus_model),
                summary = if (cfg.zeusModel.isNullOrEmpty()) {
                    stringResource(R.string.zeus_model_none)
                } else {
                    cfg.zeusModel
                },
                onClick = { dialog = SettingsDialog.ZeusModel },
            )
        }
        item {
            ArrowPreference(
                title = stringResource(R.string.opt_zeus_sn),
                summary = if (cfg.zeusSn.isNullOrEmpty()) {
                    stringResource(R.string.zeus_sn_none)
                } else {
                    cfg.zeusSn
                },
                onClick = { dialog = SettingsDialog.ZeusSn },
            )
        }
        item {
            Text(
                text = stringResource(R.string.zeus_footnote),
                fontSize = 12.sp,
                color = androidx.compose.ui.graphics.Color(0xFF8A8A8E),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        item { SmallTitle(text = stringResource(R.string.section_about)) }
        item {
            ArrowPreference(
                title = stringResource(R.string.action_about),
                onClick = { dialog = SettingsDialog.About },
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
    }

    // ---- 对话框分发 ----
    when (val d = dialog) {
        SettingsDialog.Level -> LevelDialog(
            current = cfg.logLevel,
            onPick = { v ->
                dialog = null
                if (!AppConfig.setInt(AppConfig.K_LOG_LEVEL, v)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { dialog = null },
        )
        SettingsDialog.ImeMode -> ImeModeDialog(
            current = cfg.inputMethodMode,
            onPick = { v ->
                dialog = null
                if (!AppConfig.setInt(AppConfig.K_INPUT_METHOD_MODE, v)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { dialog = null },
        )
        SettingsDialog.ImeList -> ImeListDialog(
            selected = cfg.inputMethodList,
            context = context,
            onPick = { list ->
                dialog = null
                if (!AppConfig.setString(AppConfig.K_INPUT_METHOD_LIST, list)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { dialog = null },
        )
        SettingsDialog.LogPath -> TextInputDialog(
            title = R.string.opt_log_path,
            hint = R.string.input_log_path_hint,
            initial = cfg.logFilePath ?: "",
            context = context,
            onOk = { v ->
                dialog = null
                val path = v.trim()
                if (path.isEmpty()) {
                    AppConfig.setString(AppConfig.K_LOG_FILE_PATH, "")
                    Toast.makeText(context, R.string.toast_log_path_ok, Toast.LENGTH_SHORT).show()
                } else {
                    if (!AppConfig.setString(AppConfig.K_LOG_FILE_PATH, path)) {
                        Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.toast_log_path_ok, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCancel = { dialog = null },
        )
        SettingsDialog.LogCap -> TextInputDialog(
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
        SettingsDialog.ZeusModel -> ZeusModelDialog(
            current = cfg.zeusModel,
            context = context,
            onPick = { v ->
                dialog = null
                if (!AppConfig.setString(AppConfig.K_ZEUS_MODEL, v)) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = { dialog = null },
        )
        SettingsDialog.ZeusSn -> TextInputDialog(
            title = R.string.input_zeus_sn_title,
            hint = R.string.input_zeus_sn_hint,
            initial = cfg.zeusSn ?: "",
            context = context,
            title2 = R.string.opt_zeus_sn,
            onOk = { v ->
                dialog = null
                if (!AppConfig.setString(AppConfig.K_ZEUS_SN, v.trim())) {
                    Toast.makeText(context, R.string.env_no_write, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, R.string.toast_zeus_sn_ok, Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { dialog = null },
        )
        SettingsDialog.About -> AboutDialog(
            context = context,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    // ---- 更新检查状态 ----
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
                text = {
                    Text(stringResource(R.string.update_found_body, state.tag))
                },
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
                text = { Text(stringResource(R.string.update_latest, state.tag)) },
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

private sealed class SettingsDialog {
    object Level : SettingsDialog()
    object ImeMode : SettingsDialog()
    object ImeList : SettingsDialog()
    object LogPath : SettingsDialog()
    object LogCap : SettingsDialog()
    object ZeusModel : SettingsDialog()
    object ZeusSn : SettingsDialog()
    object About : SettingsDialog()
}

private sealed class UpdateState {
    object Checking : UpdateState()
    data class Found(val tag: String, val apkUrl: String) : UpdateState()
    data class Latest(val tag: String) : UpdateState()
    data class Error(val msg: String) : UpdateState()
}

/* ---------------- 对话框实现 ---------------- */

@Composable
private fun LevelDialog(current: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.opt_log_level)) },
        text = {
            Column {
                LEVEL_NAMES.forEachIndexed { i, res ->
                    val value = LEVEL_VALUES[i]
                    Text(
                        text = stringResource(res),
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clickable { onPick(value) },
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
private fun ImeModeDialog(current: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.opt_input_method_mode)) },
        text = {
            Column {
                IME_MODES.forEachIndexed { i, res ->
                    Text(
                        text = stringResource(res),
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
        title = { Text(stringResource(R.string.input_method_select_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                list.forEach { info ->
                    val id = info.id
                    val label = info.loadLabel(context.packageManager).toString()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = id in checked,
                                onValueChange = {
                                    checked = if (it) checked + id else checked - id
                                },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = id in checked,
                            onCheckedChange = null,
                        )
                        Text(
                            text = "$label - [$id]",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
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
private fun TextInputDialog(
    title: Int,
    hint: Int,
    initial: String,
    context: Context,
    inputType: Boolean = false,
    title2: Int = 0,
    onOk: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (title2 != 0) title2 else title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(stringResource(hint)) },
                singleLine = true,
                keyboardOptions = if (inputType) {
                    androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    )
                } else {
                    androidx.compose.foundation.text.KeyboardOptions.Default
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

@Composable
private fun ZeusModelDialog(
    current: String?,
    context: Context,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val models = buildList {
        add("")
        addAll(AppConfig.ZEUS_MODELS)
    }
    val names = remember {
        listOf(context.getString(R.string.zeus_model_none)) + AppConfig.ZEUS_MODELS.toList()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.select_zeus_model_title)) },
        text = {
            Column {
                models.forEachIndexed { i, m ->
                    Text(
                        text = names[i],
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { onPick(m) },
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
private fun AboutDialog(context: Context, onDismiss: () -> Unit) {
    val ver = "XDFHook v${versionName(context)} (${versionCode(context)}) · LibXposed API 102"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Text(
                text = "${stringResource(R.string.about_body)}\n\n$ver",
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

/* ---------------- 工具 ---------------- */

internal fun versionName(context: Context): String {
    return try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        pi.versionName ?: ""
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