package cn.cf3012.xdf.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import cn.cf3012.xdf.AppConfig;
import cn.cf3012.xdf.FileLogger;
import cn.cf3012.xdf.R;

import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.lang.reflect.Method;

/**
 * SettingsPage — 选项：Hook 策略开关、日志级别，以及关于/检查更新/跳转仓库。
 */
final class SettingsPage extends BasePage {

    private static final String REPO_URL = "https://github.com/chengfeng3012/XDFHook";

    // 输入法拦截器相关
    private Switch swInputMethod;
    private TextView inputMethodModeDesc;
    private TextView inputMethodListDesc;
    private boolean bindingInputMethod;

    // 日志落盘相关
    private Switch swLogFile;
    private TextView logPathValue;
    private TextView logCapValue;
    private boolean bindingLogFile;

    // 安装解锁
    private Switch swPackageInstall;
    /** 安装解锁开关防循环绑定标志 */
    private boolean bindingPackageInstall;

    // Zeus 设备信息冒充
    private TextView zeusModelValue;
    private TextView zeusSnValue;
    private TextView zeusSystemInfo;

    SettingsPage(Activity activity) {
        super(activity, R.layout.page_settings);

        Switch swAggressive = find(R.id.sw_aggressive);
        TextView levelValue = find(R.id.log_level_value);

        swAggressive.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (binding) {
                    return;
                }
                if (!AppConfig.setBoolean(AppConfig.K_AGGRESSIVE, isChecked)) {
                    Toast.makeText(activity, R.string.env_no_write, Toast.LENGTH_LONG).show();
                }
            }
        });

        find(R.id.row_log_level).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLevelPicker();
            }
        });

        // 输入法拦截器
        swInputMethod = find(R.id.sw_input_method);
        inputMethodModeDesc = find(R.id.input_method_mode_desc);
        inputMethodListDesc = find(R.id.input_method_list_desc);

        swInputMethod.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingInputMethod) {
                    return;
                }
                if (!AppConfig.setBoolean(AppConfig.K_MOD_INPUT_METHOD, isChecked)) {
                    Toast.makeText(activity, R.string.env_no_write, Toast.LENGTH_LONG).show();
                }
            }
        });

        find(R.id.row_input_method_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInputMethodModePicker();
            }
        });

        find(R.id.row_input_method_list).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInputMethodListPicker();
            }
        });

        // 日志落盘：开关 / 路径（即时校验）/ 上限（单位 KB）
        swLogFile = find(R.id.sw_log_file);
        logPathValue = find(R.id.log_path_value);
        logCapValue = find(R.id.log_cap_value);

        swLogFile.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingLogFile) {
                    return;
                }
                if (!AppConfig.setBoolean(AppConfig.K_LOG_FILE_ENABLED, isChecked)) {
                    Toast.makeText(activity, R.string.env_no_write, Toast.LENGTH_LONG).show();
                }
            }
        });

        find(R.id.row_log_path).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogPathDialog();
            }
        });

        find(R.id.row_log_cap).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogCapDialog();
            }
        });

        // 安装解锁开关
        swPackageInstall = find(R.id.sw_package_install);
        swPackageInstall.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingPackageInstall) {
                    return;
                }
                if (!AppConfig.setBoolean(AppConfig.K_MOD_PACKAGE_INSTALL, isChecked)) {
                    Toast.makeText(activity, R.string.env_no_write, Toast.LENGTH_LONG).show();
                }
            }
        });

        // Zeus 板块：型号选择 / 序列号输入 / 系统真实值对比
        zeusModelValue = find(R.id.zeus_model_value);
        zeusSnValue = find(R.id.zeus_sn_value);
        zeusSystemInfo = find(R.id.zeus_system_info);

        find(R.id.row_zeus_model).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showZeusModelPicker();
            }
        });
        find(R.id.row_zeus_sn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showZeusSnDialog();
            }
        });

        find(R.id.row_about).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.app_name)
                        .setMessage(String.format(activity.getString(R.string.about_body),
                                versionName(), versionCode()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

        find(R.id.row_update).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate();
            }
        });

        find(R.id.row_repo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRepo();
            }
        });


        ((TextView) find(R.id.version_text)).setText(String.format(
                "XDFHook v%s (%d) · LibXposed API 102", versionName(), versionCode()));

        bind();
        levelValue.setText(levelName(AppConfig.refresh().logLevel));
    }

    /** 防循环绑定标志 */
    private boolean binding;

    private void bind() {
        binding = true;
        AppConfig cfg = AppConfig.refresh();
        ((Switch) find(R.id.sw_aggressive)).setChecked(cfg.pmsAggressive);
        binding = false;

        bindingInputMethod = true;
        swInputMethod.setChecked(cfg.modInputMethod);
        updateInputMethodModeDesc(cfg.inputMethodMode);
        updateInputMethodListDesc(cfg.inputMethodList);
        bindingInputMethod = false;

        bindingLogFile = true;
        swLogFile.setChecked(cfg.logFileEnabled);
        updateLogPathValue(cfg.logFilePath);
        updateLogCapValue(cfg.logFileCapKb);
        bindingLogFile = false;

        bindingPackageInstall = true;
        swPackageInstall.setChecked(cfg.modPackageInstall);
        bindingPackageInstall = false;

        updateZeusModelValue(cfg.zeusModel);
        updateZeusSnValue(cfg.zeusSn);
        updateZeusSystemInfo();
    }

    @Override
    public void onShown() {
        bind();
    }

    private void showLevelPicker() {
        final String[] names = {
                activity.getString(R.string.level_v),
                activity.getString(R.string.level_d),
                activity.getString(R.string.level_i),
                activity.getString(R.string.level_w),
                activity.getString(R.string.level_e),
        };
        final int[] values = {Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR};
        int current = AppConfig.refresh().logLevel;
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                idx = i;
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.opt_log_level)
                .setSingleChoiceItems(names, idx, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (!AppConfig.setInt(AppConfig.K_LOG_LEVEL, values[which])) {
                            Toast.makeText(activity, R.string.env_no_write, Toast.LENGTH_LONG).show();
                        }
                        ((TextView) find(R.id.log_level_value)).setText(names[which]);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String levelName(int level) {
        switch (level) {
            case Log.VERBOSE:
                return activity.getString(R.string.level_v);
            case Log.DEBUG:
                return activity.getString(R.string.level_d);
            case Log.WARN:
                return activity.getString(R.string.level_w);
            case Log.ERROR:
                return activity.getString(R.string.level_e);
            default:
                return activity.getString(R.string.level_i);
        }
    }

    // 输入法拦截器相关方法
    private void updateInputMethodModeDesc(int mode) {
        if (mode == 0) {
            inputMethodModeDesc.setText(R.string.opt_input_method_mode_lock_desc);
        } else {
            inputMethodModeDesc.setText(R.string.opt_input_method_mode_blacklist_desc);
        }
    }

    private void updateInputMethodListDesc(String list) {
        if (list == null || list.isEmpty()) {
            inputMethodListDesc.setText(R.string.input_method_none);
        } else {
            String[] items = list.split(",");
            inputMethodListDesc.setText(String.format(activity.getString(R.string.input_method_selected), items.length));
        }
    }

    private void showInputMethodModePicker() {
        final String[] names = {
                activity.getString(R.string.opt_input_method_mode_lock),
                activity.getString(R.string.opt_input_method_mode_blacklist),
        };
        final int[] values = {0, 1};
        int current = AppConfig.refresh().inputMethodMode;
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                idx = i;
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.opt_input_method_mode)
                .setSingleChoiceItems(names, idx, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (!AppConfig.setInt(AppConfig.K_INPUT_METHOD_MODE, values[which])) {
                            Toast.makeText(activity, R.string.env_no_write, Toast.LENGTH_LONG).show();
                        }
                        updateInputMethodModeDesc(values[which]);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showInputMethodListPicker() {
        // 枚举系统已安装的输入法
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imeList = imm.getInputMethodList();
        
        final List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        
        for (InputMethodInfo info : imeList) {
            String id = info.getId(); // 形如 com.sohu.inputmethod.sogou/.SogouIME
            String label = info.loadLabel(activity.getPackageManager()).toString();
            labels.add(label + " - [" + id + "]");
            values.add(id);
        }
        
        // 获取当前黑名单
        String currentList = AppConfig.refresh().inputMethodList;
        Set<String> selectedSet = new HashSet<>();
        if (currentList != null && !currentList.isEmpty()) {
            selectedSet.addAll(Arrays.asList(currentList.split(",")));
        }
        
        final boolean[] checkedItems = new boolean[labels.size()];
        for (int i = 0; i < values.size(); i++) {
            checkedItems[i] = selectedSet.contains(values.get(i));
        }
        
        new AlertDialog.Builder(activity)
                .setTitle(R.string.input_method_select_title)
                .setMultiChoiceItems(labels.toArray(new String[0]), checkedItems,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which, boolean isChecked) {
                                checkedItems[which] = isChecked;
                            }
                        })
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < checkedItems.length; i++) {
                            if (checkedItems[i]) {
                                if (sb.length() > 0) {
                                    sb.append(",");
                                }
                                sb.append(values.get(i));
                            }
                        }
                        String newList = sb.toString();
                        if (!AppConfig.setString(AppConfig.K_INPUT_METHOD_LIST, newList)) {
                            Toast.makeText(activity, R.string.env_no_write, Toast.LENGTH_LONG).show();
                        }
                        updateInputMethodListDesc(newList);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateLogPathValue(String path) {
        if (path == null || path.isEmpty()) {
            logPathValue.setText(R.string.input_log_path_default);
        } else {
            logPathValue.setText(activity.getString(R.string.log_path_value_fmt, path));
        }
    }

    private void updateLogCapValue(int kb) {
        logCapValue.setText(activity.getString(R.string.log_cap_value_fmt, kb));
    }

    /**
     * 落盘路径对话框：输入后立即试探写入该路径——
     * 成功才保存（即时生效），失败提示并不保存。
     */
    private void showLogPathDialog() {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        String cur = AppConfig.get().logFilePath;
        input.setText(cur == null ? "" : cur);
        input.setHint(R.string.input_log_path_hint);
        input.setSelection(Math.max(0, input.getText().length()));
        new AlertDialog.Builder(activity)
                .setTitle(R.string.opt_log_path)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String path = input.getText().toString().trim();
                                if (path.isEmpty()) {
                                    Toast.makeText(activity, R.string.toast_log_path_fail,
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                if (!FileLogger.probeWrite(path)) {
                                    Toast.makeText(activity, R.string.toast_log_path_fail,
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (!AppConfig.setString(AppConfig.K_LOG_FILE_PATH, path)) {
                                    Toast.makeText(activity, R.string.env_no_write,
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                updateLogPathValue(path);
                                Toast.makeText(activity, R.string.toast_log_path_ok,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 日志上限（KB）对话框：1 ~ MAX_LOG_CAP_KB，非法值拒绝保存 */
    private void showLogCapDialog() {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int cur = AppConfig.get().logFileCapKb;
        input.setText(String.valueOf(cur));
        input.setHint(activity.getString(R.string.input_log_cap_hint, AppConfig.MAX_LOG_CAP_KB));
        input.setSelection(Math.max(0, input.getText().length()));
        new AlertDialog.Builder(activity)
                .setTitle(R.string.opt_log_cap)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                int kb;
                                try {
                                    kb = Integer.parseInt(input.getText().toString().trim());
                                } catch (NumberFormatException e) {
                                    kb = -1;
                                }
                                if (kb < AppConfig.MIN_LOG_CAP_KB || kb > AppConfig.MAX_LOG_CAP_KB) {
                                    Toast.makeText(activity,
                                            activity.getString(R.string.toast_log_cap_invalid,
                                                    AppConfig.MAX_LOG_CAP_KB),
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                if (!AppConfig.setInt(AppConfig.K_LOG_FILE_CAP_KB, kb)) {
                                    Toast.makeText(activity, R.string.env_no_write,
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                updateLogCapValue(kb);
                                Toast.makeText(activity, R.string.toast_log_cap_ok,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /* ==================== Zeus 设备信息冒充 ==================== */

    private void updateZeusModelValue(String m) {
        if (m == null || m.isEmpty()) {
            zeusModelValue.setText(R.string.zeus_model_none);
        } else {
            zeusModelValue.setText(m);
        }
    }

    private void updateZeusSnValue(String s) {
        if (s == null || s.isEmpty()) {
            zeusSnValue.setText(R.string.zeus_sn_none);
        } else {
            zeusSnValue.setText(s);
        }
    }

    /** 显示系统真实型号/序列号，与自定义冒充值对比（用于核对 zeus 生效）。
        型号取 ro.release.model.internal —— 与 zeus getModelInternal 的 inline 源一致；
        序列号取 ro.serialno（DeviceProtectedUtils.getSerial 最终也源自系统序列号）。 */
    private void updateZeusSystemInfo() {
        String model = props("ro.release.model.internal");
        String serial = props("ro.serialno");
        zeusSystemInfo.setText(activity.getString(R.string.zeus_system_info,
                model.isEmpty() ? "?" : model,
                serial.isEmpty() ? "?" : serial));
    }

    /** 读取只读系统属性（ro.*），无需权限的隐藏 API 反射 */
    private String props(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, "");
            return v == null ? "" : v.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 型号：从预置列表单选（首项 = 未设置，恢复真实型号） */
    private void showZeusModelPicker() {
        final String[] names = new String[AppConfig.ZEUS_MODELS.length + 1];
        names[0] = activity.getString(R.string.zeus_model_none);
        System.arraycopy(AppConfig.ZEUS_MODELS, 0, names, 1, AppConfig.ZEUS_MODELS.length);
        String current = AppConfig.refresh().zeusModel;
        int idx = 0;
        for (int i = 1; i < names.length; i++) {
            if (names[i].equals(current)) {
                idx = i;
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.select_zeus_model_title)
                .setSingleChoiceItems(names, idx,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String v = which == 0 ? "" : names[which];
                                if (!AppConfig.setString(AppConfig.K_ZEUS_MODEL, v)) {
                                    Toast.makeText(activity, R.string.env_no_write,
                                            Toast.LENGTH_LONG).show();
                                }
                                updateZeusModelValue(v);
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 序列号：自由输入；留空 = 未设置（恢复真实） */
    private void showZeusSnDialog() {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        String cur = AppConfig.get().zeusSn;
        input.setText(cur == null ? "" : cur);
        input.setHint(R.string.input_zeus_sn_hint);
        input.setSelection(Math.max(0, input.getText().length()));
        new AlertDialog.Builder(activity)
                .setTitle(R.string.input_zeus_sn_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String v = input.getText().toString().trim();
                                if (!AppConfig.setString(AppConfig.K_ZEUS_SN, v)) {
                                    Toast.makeText(activity, R.string.env_no_write,
                                            Toast.LENGTH_LONG).show();
                                }
                                updateZeusSnValue(v);
                                Toast.makeText(activity, R.string.toast_zeus_sn_ok,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 在线检查更新：调 GitHub API，结果回 UI 线程弹窗 */
    private void checkForUpdate() {
        final int cur = versionCode();
        // 进度框
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(R.string.action_update)
                .setMessage(R.string.update_checking)
                .setCancelable(false)
                .show();
        GithubUpdateChecker.checkAsync(cur, new GithubUpdateChecker.Listener() {
            @Override
            public void onUpdate(final String latestTag, final String vName,
                                 final int vCode, final String apkUrl, final String note) {
                runOnUi(() -> {
                    dismissQuietly(progress);
                    AlertDialog.Builder b = new AlertDialog.Builder(activity)
                            .setTitle(R.string.update_available)
                            .setMessage(String.format(activity.getString(R.string.update_found),
                                    latestTag,
                                    String.format("%s (%d)", vName, vCode)));
                    if (note != null && !note.isEmpty()) {
                        b.setMessage(String.format(activity.getString(R.string.update_found_body),
                                latestTag,
                                String.format("%s (%d)", vName, vCode),
                                note));
                    }
                    if (apkUrl != null && !apkUrl.isEmpty()) {
                        b.setPositiveButton(R.string.update_download, (d, w) -> openUrl(apkUrl));
                    }
                    b.setNeutralButton(R.string.action_repo, (d, w) -> openRepo());
                    b.setNegativeButton(android.R.string.cancel, null);
                    b.show();
                });
            }

            @Override
            public void onLatest(String latestTag, int vCode) {
                runOnUi(() -> {
                    dismissQuietly(progress);
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.action_update)
                            .setMessage(String.format(activity.getString(R.string.update_latest),
                                    versionName(), versionCode()))
                            .setPositiveButton(R.string.action_repo, (d, w) -> openRepo())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUi(() -> {
                    dismissQuietly(progress);
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.action_update)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
    }

    private void dismissQuietly(AlertDialog d) {
        try {
            if (d != null && d.isShowing()) {
                d.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private void runOnUi(final Runnable r) {
        try {
            activity.runOnUiThread(r);
        } catch (Throwable ignored) {
        }
    }

    private void openUrl(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(activity, url, Toast.LENGTH_LONG).show();
        }
    }

    private void openRepo() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
        } catch (Throwable t) {
            Toast.makeText(activity, REPO_URL, Toast.LENGTH_LONG).show();
        }
    }

    private String versionName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int versionCode() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }
}
