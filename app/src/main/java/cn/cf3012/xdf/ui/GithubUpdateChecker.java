package cn.cf3012.xdf.ui;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 在线检查更新 —— 调 GitHub API 取最新 Release。
 *
 * 约定：
 *  - 仓库 github.com/chengfeng3012/XDFHook
 *  - 端点 GET /repos/{owner}/{repo}/releases/latest（不鉴权，60 次/小时配额足够）
 *  - Release tag 格式 vMAJOR.MINOR.BUILDCODE，如 v1.0.10；
 *    比较基准取 tag 里的 BUILDCODE（= Android versionCode），比比较 versionName 更可靠。
 *  - 下载链接取 release assets 里第一个 .apk 的 browser_download_url。
 *
 * 纯 Java 后台线程 + org.json（系统自带），不引第三方库，UI 线程回调。
 */
final class GithubUpdateChecker {

    /** 检查结果回调 */
    interface Listener {
        /** 找到更新：latestTag 如 v1.0.10，apkUrl 为直链，note 为 release body 摘要 */
        void onUpdate(String latestTag, String versionName, int versionCode,
                      String apkUrl, String note);

        /** 当前已是最新（或发布同名） */
        void onLatest(String latestTag, int versionCode);

        void onError(String message);
    }

    private static final String API_LATEST =
            "https://api.github.com/repos/chengfeng3012/XDFHook/releases/latest";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 12000;

    private GithubUpdateChecker() {
    }

    /** 后台线程发起检查，结果回到传入线程回调（invoke 用 runOnUiThread 自行处理） */
    static void checkAsync(final int currentVersionCode,
                           final Listener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject rel = fetchLatest();
                    if (rel == null) {
                        listener.onError("无法获取 Release 信息（网络或配额）");
                        return;
                    }
                    String tag = rel.optString("tag_name", "");
                    // tag 形如 v1.0.10，取最后一段作为 buildcode/versionCode
                    int remoteCode = parseBuildCode(tag);
                    String versionName = parseVersionName(tag);
                    String downloadUrl = firstApkUrl(rel);
                    String body = rel.optString("body", "");

                    if (remoteCode < 0) {
                        listener.onError("Release tag 格式未知: " + tag);
                        return;
                    }
                    if (remoteCode > currentVersionCode) {
                        listener.onUpdate(tag, versionName, remoteCode,
                                downloadUrl, body);
                    } else {
                        listener.onLatest(tag, remoteCode);
                    }
                } catch (Exception e) {
                    listener.onError("检查失败: " + e.getMessage());
                }
            }
        }, "xdf-update-check").start();
    }

    /** 请求 latest release API，返回 JSONObject；请求失败返回 null */
    private static JSONObject fetchLatest() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API_LATEST).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "XDFHook");
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                return null;
            }
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                return new JSONObject(sb.toString());
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 从 tag "v1.0.10" 解析出 versionCode=10，无法解析返回 -1 */
    private static int parseBuildCode(String tag) {
        try {
            String trimmed = tag.trim();
            if (trimmed.startsWith("v")) {
                trimmed = trimmed.substring(1);
            }
            String[] parts = trimmed.split("\\.");
            if (parts.length == 0) {
                return -1;
            }
            // 最后一段即 build code
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 从 tag "v1.0.10" 解析出可读 versionName（去掉 v 与最后段）："1.0" */
    private static String parseVersionName(String tag) {
        try {
            String trimmed = tag.trim();
            if (trimmed.startsWith("v")) {
                trimmed = trimmed.substring(1);
            }
            String[] parts = trimmed.split("\\.");
            if (parts.length <= 1) {
                return trimmed;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) {
                    sb.append('.');
                }
                sb.append(parts[i]);
            }
            return sb.toString();
        } catch (Exception e) {
            return tag;
        }
    }

    /** 取 release assets 中第一个 .apk 的直链 */
    private static String firstApkUrl(JSONObject rel) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) {
            return "";
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String name = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (name.toLowerCase().endsWith(".apk") && !url.isEmpty()) {
                return url;
            }
        }
        return "";
    }
}