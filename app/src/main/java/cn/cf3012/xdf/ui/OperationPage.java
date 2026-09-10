package cn.cf3012.xdf.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.cf3012.xdf.R;
import cn.cf3012.xdf.Root;

/**
 * OperationPage — 「操作」页。
 *
 * 顶部一个 Root 总开关（横条形）：开启时先请求并检测 root 权限，拿到才可用；
 * 可随时关闭，关闭后下方所有圆形按钮禁用（防无 root 或 root 被屏蔽的环境误触）。
 *
 * 「功能」组：自动旋转 / 深色模式 / 导航模式 为状态感知的开-关键（按钮文字显示
 * 将要切换到的状态，如当前已开则显示\"关\"），加刷新媒体库。每组按行 4 个圆形按钮。
 * 「危险区」组：软重启 / 硬重启 / 重启系统 UI，三项均需二次确认，卡片为淡红底。
 * 所有命令经 su 以 root 在后台线程执行。
 */
final class OperationPage extends BasePage {

    /** toggle 类型：0=静态按钮，1=自动旋转，2=深色，3=导航 */
    private static final int T_NONE = 0, T_ROTATE = 1, T_DARK = 2, T_NAV = 3;

    private static final class Action {
        final int titleRes;
        final int shortRes;        // 静态按钮的圆内短字；toggle 为其默认态
        final int color;
        final boolean danger;
        final int confirmTitleRes;
        final int confirmMsgRes;
        final String command;      // 执行（toggle 命令）
        final int toggleType;
        final int labelOnRes;      // toggle：值为 onValue 时显示的下一步
        final int labelOffRes;
        final String getCmd;
        final String onValue;
        final boolean orientationMenu; // 弹 4 选 1 固定朝向菜单

        Action(int titleRes, int shortRes, int color, boolean danger,
               int confirmTitleRes, int confirmMsgRes, String command) {
            this.titleRes = titleRes;
            this.shortRes = shortRes;
            this.color = color;
            this.danger = danger;
            this.confirmTitleRes = confirmTitleRes;
            this.confirmMsgRes = confirmMsgRes;
            this.command = command;
            this.toggleType = T_NONE;
            this.labelOnRes = 0;
            this.labelOffRes = 0;
            this.getCmd = null;
            this.onValue = null;
            this.orientationMenu = false;
        }

        Action(int titleRes, int color, int toggleType, int labelOnRes, int labelOffRes,
               String getCmd, String onValue, String command) {
            this.titleRes = titleRes;
            this.shortRes = labelOnRes;
            this.color = color;
            this.danger = false;
            this.confirmTitleRes = 0;
            this.confirmMsgRes = 0;
            this.command = command;
            this.toggleType = toggleType;
            this.labelOnRes = labelOnRes;
            this.labelOffRes = labelOffRes;
            this.getCmd = getCmd;
            this.onValue = onValue;
            this.orientationMenu = false;
        }

        /** 菜单型：固定屏幕朝向（点按弹 4 选 1） */
        Action(int titleRes, int shortRes, int color) {
            this.titleRes = titleRes;
            this.shortRes = shortRes;
            this.color = color;
            this.danger = false;
            this.confirmTitleRes = 0;
            this.confirmMsgRes = 0;
            this.command = null;
            this.toggleType = T_NONE;
            this.labelOnRes = 0;
            this.labelOffRes = 0;
            this.getCmd = null;
            this.onValue = null;
            this.orientationMenu = true;
        }
    }

    private static final String GT_SYS = "settings get system accelerometer_rotation";
    private static final String GT_UI = "settings get secure ui_night_mode";
    private static final String GT_NAV = "settings get secure navigation_mode";

    private static final String CMD_ROTATE =
            "if [ \"$(settings get system accelerometer_rotation)\" = \"1\" ]; then "
                    + "settings put system accelerometer_rotation 0; "
                    + "else settings put system accelerometer_rotation 1; fi";
    private static final String CMD_DARK =
            "if [ \"$(settings get secure ui_night_mode)\" = \"2\" ]; then "
                    + "settings put secure ui_night_mode 1; "
                    + "else settings put secure ui_night_mode 2; fi";
    private static final String CMD_NAV =
            "mode=$(settings get secure navigation_mode); "
                    + "if [ \"$mode\" = \"2\" ]; then settings put secure navigation_mode 0; "
                    + "else settings put secure navigation_mode 2; fi; "
                    + "cmd overlay enable com.android.internal.systemui.navbar.gestural; "
                    + "killall -9 com.android.systemui";
    private static final String CMD_REFRESH_MEDIA =
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/";
    private static final String CMD_IME =
            "am start -a android.settings.INPUT_METHOD_SETTINGS";
    private static final String CMD_DESKTOP =
            "am start -a android.settings.HOME_SETTINGS";
    private static final String CMD_ORIENT(int deg) {
        return "settings put system accelerometer_rotation 0; "
                + "settings put system user_rotation " + deg;
    }
    private static final String CMD_SOFT_REBOOT = "kill -9 $(pidof system_server)";
    private static final String CMD_HARD_REBOOT = "reboot";
    private static final String CMD_KILL_SYSUI = "killall -9 com.android.systemui";

    private static final List<Action> FUNCTION_ACTIONS = new ArrayList<>();
    private static final List<Action> DANGER_ACTIONS = new ArrayList<>();

    static {
        // 功能组：自动旋转 / 固定屏幕朝向 / 深色模式 / 导航模式 / 切换输入法 / 切换桌面 / 刷新媒体库
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_rotate, 0xFF3482FF, T_ROTATE,
                R.string.op_btn_off, R.string.op_btn_on, GT_SYS, "1", CMD_ROTATE));
        // 固定屏幕朝向（排在自动旋转后面，菜单 4 选 1）
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_orientation,
                R.string.op_act_orientation_short, 0xFF00B8D9));
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_dark, 0xFF7C5CFF, T_DARK,
                R.string.op_btn_light, R.string.op_btn_deep, GT_UI, "2", CMD_DARK));
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_nav, 0xFFFF8A00, T_NAV,
                R.string.op_btn_three, R.string.op_btn_gesture, GT_NAV, "2", CMD_NAV));
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_ime, R.string.op_act_ime_short,
                0xFF34C759, false, 0, 0, CMD_IME));
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_desktop, R.string.op_act_desktop_short,
                0xFFFF6482, false, 0, 0, CMD_DESKTOP));
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_media, R.string.op_act_media_short,
                0xFFFFC42E, false, 0, 0, CMD_REFRESH_MEDIA));

        // 静默安装：输入 APK 路径后 root pm install（绕过 PackageInstaller UI 限制）
        FUNCTION_ACTIONS.add(new Action(R.string.op_act_silent_install,
                R.string.op_act_silent_install_short,
                0xFF00BCD4, false, 0, 0, null));

        // 危险区：软重启 / 硬重启 / 重启系统 UI（均需确认）
        DANGER_ACTIONS.add(new Action(R.string.op_act_soft, R.string.op_act_soft_short,
                0xFFFFB340, true, R.string.op_confirm_soft_title, R.string.op_confirm_soft_msg,
                CMD_SOFT_REBOOT));
        DANGER_ACTIONS.add(new Action(R.string.op_act_hard, R.string.op_act_hard_short,
                0xFFFF6369, true, R.string.op_confirm_hard_title, R.string.op_confirm_hard_msg,
                CMD_HARD_REBOOT));
        DANGER_ACTIONS.add(new Action(R.string.op_act_sysui, R.string.op_act_sysui_short,
                0xFFFF6482, true, R.string.op_confirm_sysui_title, R.string.op_confirm_sysui_msg,
                CMD_KILL_SYSUI));
    }

    private static final int PER_ROW = 4;
    private static final int CIRCLE_DP = 52;

    private Switch swRoot;
    private TextView rootState;
    private boolean binding;
    private boolean probing;
    private boolean hasRoot;
    private final List<LinearLayout> columns = new ArrayList<>();
    /** toggle 按钮：(Action, TextView) 列表，用于读取现状并刷新圆内文字 */
    private final List<Object[]> toggles = new ArrayList<>();
    private final Map<Action, TextView> circleOf = new HashMap<>();

    OperationPage(Activity activity) {
        super(activity, R.layout.page_operation);
        swRoot = find(R.id.sw_root_op);
        rootState = find(R.id.root_state);

        swRoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (binding || probing) {
                    return;
                }
                if (isChecked) {
                    requestRoot();
                } else {
                    hasRoot = false;
                    rootState.setText(R.string.op_root_subtitle_off);
                    refreshButtons();
                }
            }
        });

        buildGrid(find(R.id.op_function_container), FUNCTION_ACTIONS);
        buildGrid(find(R.id.op_danger_container), DANGER_ACTIONS);
        refreshButtons();
    }

    @Override
    public void onShown() {
        // 每次展示刷新状态文案（运行时状态不持久化）
        if (!hasRoot) {
            rootState.setText(R.string.op_root_subtitle_off);
        } else {
            refreshToggleLabels();
        }
    }

    /** 打开总开关：后台探测 root，拿到后才保持打开，否则弹回并提示 */
    private void requestRoot() {
        probing = true;
        rootState.setText(R.string.op_root_checking);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Root.available();
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        probing = false;
                        if (ok) {
                            hasRoot = true;
                            rootState.setText(R.string.op_root_subtitle_ok);
                            refreshButtons();
                            refreshToggleLabels();
                        } else {
                            hasRoot = false;
                            binding = true;
                            swRoot.setChecked(false);
                            binding = false;
                            rootState.setText(R.string.op_root_subtitle_off);
                            refreshButtons();
                            Toast.makeText(activity, R.string.op_root_denied,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /** 按 hasRoot 控制所有按钮可用性 */
    private void refreshButtons() {
        for (LinearLayout col : columns) {
            col.setAlpha(hasRoot ? 1f : 0.35f);
            View circle = col.getChildAt(0);
            circle.setEnabled(hasRoot);
        }
    }

    /** 后台读取各 toggle 的当前值，把圆内文字刷新为\"将要切换到的状态\" */
    private void refreshToggleLabels() {
        if (!hasRoot || toggles.isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int[] labelRes = new int[toggles.size()];
                for (int i = 0; i < toggles.size(); i++) {
                    Action a = (Action) toggles.get(i)[0];
                    String v = Root.get(a.getCmd);
                    labelRes[i] = a.onValue.equals(v) ? a.labelOnRes : a.labelOffRes;
                }
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < toggles.size(); i++) {
                            ((TextView) toggles.get(i)[1]).setText(labelRes[i]);
                        }
                    }
                });
            }
        }).start();
    }

    private void buildGrid(LinearLayout container, List<Action> actions) {
        LinearLayout row = newRow();
        int i = 0;
        for (Action a : actions) {
            LinearLayout col = buildColumn(a);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            i++;
            if (i % PER_ROW == 0) {
                container.addView(row);
                row = newRow();
            }
        }
        if (row.getChildCount() > 0) {
            container.addView(row);
        }
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, 0, 0, dp(4));
        return row;
    }

    /** 生成「圆形按钮 + 下方说明」的一列 */
    private LinearLayout buildColumn(final Action a) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, dp(8), 0, dp(8));

        final TextView circle = new TextView(activity);
        circle.setText(a.shortRes);
        circle.setTextSize(14);
        circle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        circle.setTextColor(a.color);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(circleStates(a.color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CIRCLE_DP), dp(CIRCLE_DP));
        circle.setLayoutParams(lp);
        circle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onActionClick(a);
            }
        });

        TextView caption = new TextView(activity);
        caption.setText(a.titleRes);
        caption.setTextSize(12);
        caption.setTextColor(activity.getResources().getColor(R.color.text_secondary));
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(6), 0, 0);

        col.addView(circle);
        col.addView(caption);
        columns.add(col);
        if (a.toggleType != T_NONE) {
            toggles.add(new Object[]{a, circle});
            circleOf.put(a, circle);
        }
        return col;
    }

    /** 圆形按钮背景：常态低透明填充 + 按压加深 */
    private StateListDrawable circleStates(int color) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(tint(color, 0x22));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(tint(color, 0x44));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    private static int tint(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void onActionClick(final Action a) {
        if (!hasRoot) {
            return;
        }
        if (a.orientationMenu) {
            showOrientationPicker();
            return;
        }
        if (a.command == null) {
            // 静默安装：弹输入路径对话框（命令为 null 的 Action）
            showSilentInstallDialog();
            return;
        }
        if (a.danger) {
            new AlertDialog.Builder(activity)
                    .setTitle(a.confirmTitleRes)
                    .setMessage(a.confirmMsgRes)
                    .setPositiveButton(R.string.op_confirm_ok,
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    runAction(a);
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            runAction(a);
        }
    }

    private void runAction(final Action a) {
        runCommand(a.command, a.toggleType != T_NONE
                ? new Runnable() {
                    @Override
                    public void run() {
                        refreshToggleLabels();
                    }
                } : null);
    }

    /** 固定屏幕朝向：4 种规格 0°竖屏 / 90°横屏 / 180°反向竖屏 / 270°反向横屏 */
    private void showOrientationPicker() {
        final String[] names = {
                activity.getString(R.string.op_orient_portrait),
                activity.getString(R.string.op_orient_landscape),
                activity.getString(R.string.op_orient_rev_portrait),
                activity.getString(R.string.op_orient_rev_landscape),
        };
        final int[] deg = {0, 1, 2, 3};
        new AlertDialog.Builder(activity)
                .setTitle(R.string.op_orient_title)
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        runCommand(CMD_ORIENT(deg[which]), new Runnable() {
                            @Override
                            public void run() {
                                refreshToggleLabels();
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 静默安装：弹路径输入对话框，确认后 root pm install（借 system 权限静默绕过 PackageInstaller UI） */
    private void showSilentInstallDialog() {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(R.string.op_silent_install_hint);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.op_silent_install_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String path = input.getText().toString().trim();
                        if (path.isEmpty()) {
                            Toast.makeText(activity, R.string.op_silent_install_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // pm install -r -t --user 0：覆盖安装 + 允许测试包 + 限定当前用户
                        String cmd = "pm install -r -t --user 0 '" + path + "'";
                        runCommand(cmd, new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(activity, R.string.op_silent_install_ok, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 后台以 root 执行命令；完成后（成功/失败）均回调 done */
    private void runCommand(final String command, final Runnable done) {
        Toast.makeText(activity, R.string.op_running, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Root.exec(command);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(activity,
                                ok ? R.string.op_exec_done : R.string.op_exec_fail,
                                Toast.LENGTH_SHORT).show();
                        if (done != null) {
                            done.run();
                        }
                    }
                });
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}