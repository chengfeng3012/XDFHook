package cn.cf3012.xdf;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DeviceGate — 注入前的「设备门禁」。
 *
 * <p>本模块的绝大多数 hook 都是针对 XDF-N1（MT8788 / Android 10）实测出来的：类名、
 * 方法签名、字段名、资源 id、甚至硬编码常量（如 {@code UPGRADE_TOUCH_DRIVER_TIMEOUT}）
 * 都直接取自该 ROM。装到别的机型 / 别的 Android 版本上，这些假设全部不成立 ——
 * 轻则该功能无效，重则 hook 到语义完全不同的方法上，把宿主进程搞崩（system_server
 * 尤其致命）。所以注入前必须先确认「是不是目标机器」。</p>
 *
 * <h3>两条门禁</h3>
 * <ul>
 *   <li><b>Android 版本（硬门禁，不可绕过）</b>：必须是 Android 10（API 29）。
 *       版本不符一律不注入，用户也没有开关可以打开。</li>
 *   <li><b>机型（软门禁，用户可显式豁免）</b>：默认必须是 XDF-N1；不是则<b>默认不注入</b>，
 *       只有用户在 App 里主动打开「强制注入（无视设备校验）」才继续。</li>
 * </ul>
 *
 * <h3>★ 为什么型号判定要看多个来源（实测数据，2026-10-17 Termux root getprop）</h3>
 * <pre>
 *   ro.product.model          = XP11G-G   ← ODM 通用名，【不是】机型名
 *   ro.release.model.internal = XDF-N1    ← XDF 自己的机型名（最权威）
 *   ro.product.device         = XDF-N1
 * </pre>
 * 只看 {@code ro.product.model}（= {@link Build#MODEL}）会误判成本机不是 XDF-N1，
 * 因此这里对「多个来源取任一命中」判定，并把每个来源的原始值打进日志，便于排错。
 *
 * <p>判定本身是"值以 {@code XDF-N1} 开头"（忽略大小写），从而同时覆盖官方变体机型
 * （如 XDF-N1-GM）。</p>
 */
public final class DeviceGate {

    /** 唯一受支持的 Android 版本：Android 10 = API 29 */
    public static final int REQUIRED_SDK = 29;

    /** 受支持的机型前缀 */
    public static final String MODEL_PREFIX = "XDF-N1";

    /**
     * 型号候选来源的属性键（除 {@link Build} 的公开字段之外）。
     * 反射读 {@code SystemProperties} 在部分进程可能受 hidden API 限制而失败，
     * 失败即跳过 —— {@link Build#DEVICE} 已足以兜底。
     */
    private static final String[] MODEL_PROP_KEYS = {
            "ro.release.model.internal",
            "ro.product.device",
            "ro.product.model",
    };

    private DeviceGate() {
    }

    /** 设备门禁探测结果（不可变） */
    public static final class Info {
        public final int sdkInt;
        public final String androidRelease;
        /** 形如 {@code Build.DEVICE=XDF-N1} 的来源明细（诊断用） */
        public final List<String> modelSources;
        /** 去重后的候选型号值 */
        public final List<String> models;
        public final boolean sdkOk;
        public final boolean modelOk;

        Info(int sdkInt, String androidRelease, List<String> modelSources,
             List<String> models, boolean sdkOk, boolean modelOk) {
            this.sdkInt = sdkInt;
            this.androidRelease = androidRelease;
            this.modelSources = modelSources;
            this.models = models;
            this.sdkOk = sdkOk;
            this.modelOk = modelOk;
        }

        /**
         * 是否允许注入。
         *
         * @param force 用户在 App 中显式打开的「强制注入」开关（只对型机校验生效，
         *              <b>不能</b>绕过 Android 版本硬门禁）
         */
        public boolean allowed(boolean force) {
            if (!sdkOk) {
                return false;
            }
            return modelOk || force;
        }

        /** 候选型号的可读文本 */
        public String modelsText() {
            return models.isEmpty() ? "(unknown)" : String.join(", ", models);
        }

        /** 一行式描述，用于日志与 UI */
        public String describe() {
            return "Android " + androidRelease + " (API " + sdkInt + "), model=" + modelsText();
        }

        /** 阻断原因（未阻断返回 null） */
        public String blockedReason(boolean force) {
            if (!sdkOk) {
                return "Android " + androidRelease + " (API " + sdkInt + ") 不受支持，仅支持 Android 10 (API 29)";
            }
            if (!modelOk && !force) {
                return "未检测到 XDF-N1（当前：" + modelsText() + "）";
            }
            return null;
        }
    }

    /** 探测当前设备（每次调用都重新读取，UI 侧可随时刷新） */
    public static Info info() {
        final int sdk = Build.VERSION.SDK_INT;
        final String release = Build.VERSION.RELEASE;

        List<String> sources = new ArrayList<>();
        Set<String> values = new LinkedHashSet<>();
        addCandidate(sources, values, "Build.DEVICE", Build.DEVICE);
        addCandidate(sources, values, "Build.MODEL", Build.MODEL);
        addCandidate(sources, values, "Build.PRODUCT", Build.PRODUCT);
        for (String key : MODEL_PROP_KEYS) {
            addCandidate(sources, values, key, readProp(key));
        }

        boolean modelOk = false;
        for (String v : values) {
            if (matchesModel(v)) {
                modelOk = true;
                break;
            }
        }
        return new Info(sdk, release, sources, new ArrayList<>(values),
                sdk == REQUIRED_SDK, modelOk);
    }

    /** 便捷判定：单次探测（hook 进程用） */
    public static boolean allowed(boolean force) {
        return info().allowed(force);
    }

    /** 型号匹配：忽略大小写与首尾空白，以 XDF-N1 开头即命中（覆盖 XDF-N1-GM 等变体） */
    public static boolean matchesModel(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.regionMatches(true, 0, MODEL_PREFIX, 0, MODEL_PREFIX.length());
    }

    private static void addCandidate(List<String> sources, Set<String> values,
                                     String source, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sources.add(source + "=" + value);
        values.add(value.trim());
    }

    /**
     * 反射读系统属性（{@code SystemProperties.get(String,String)} 是 @hide）。
     * 任何失败（hidden API 限制 / 类缺失）都返回 null，由 Build 公开字段兜底。
     */
    private static String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getDeclaredMethod("get", String.class, String.class);
            m.setAccessible(true);
            Object v = m.invoke(null, key, "");
            return (v instanceof String && !((String) v).isEmpty()) ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 诊断：一行可读的设备信息（含全部来源） */
    public static String describeSources() {
        Info i = info();
        return i.describe() + " sources=[" + String.join(" | ", i.modelSources) + "]";
    }
}
