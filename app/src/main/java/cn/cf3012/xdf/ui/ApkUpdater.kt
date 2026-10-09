package cn.cf3012.xdf.ui

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ApkUpdater — Release APK 下载 + root 静默安装。
 *
 * 安装为什么不怕宿主进程被杀：
 *   pm 是独立进程（com.android.commands.pm），真正的安装事务由 system_server
 *   的 PackageInstallerService 持有；本 App 进程被杀不会影响事务本身。
 *   最坏情况是 pm 客户端在握手前被杀 → 事务干净回滚，不会出现坏包
 *   （包管理器是原子替换）。这里仍用 setsid+nohup 让安装命令脱离本进程组，
 *   并把结果写入日志文件轮询，保证成功率与可观测性。
 */
object ApkUpdater {

    private const val INSTALL_POLL_MS = 600L
    private const val INSTALL_TIMEOUT_MS = 180_000L

    /**
     * 判定 host 是否可用的超时：5s。
     * 只覆盖「连接 + 拿到响应头（含跟随重定向）」这一段，
     * 5s 内没响应或报错（超时 / HTTP 非 2xx）即判定该 host 不可用，换下一个。
     * 真正的数据传输不适用这个值——release 包有 10MB+，慢速下载总耗时
     * 本来就会超过 5s，若沿用会中途断流。
     */
    private const val HOST_PROBE_TIMEOUT_MS = 5_000

    /** 判定可用后，正式传输阶段的读超时：给慢速/大文件留足余量 */
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    /**
     * 下载镜像 host，按优先级依次回退。
     * 三者指向同一份 release 资源（反向代理），只是出口不同：
     *   1. gh.cfoss.dpdns.org  首选代理
     *   2. gh.cf3012.eu.org    备用代理
     *   3. github.com          官方源兜底
     * 直链里的 host 通常是 github.com / objects.githubusercontent.com，
     * 这里统一替换为当前尝试的 host，path 与 query 原样保留。
     */
    private val HOST_FALLBACKS = listOf(
        "gh.cfoss.dpdns.org",
        "gh.cf3012.eu.org",
        "github.com",
    )

    /**
     * 下载 APK 到应用私有目录（无需任何存储权限），返回本地文件。
     *
     * 回退策略：依次尝试 HOST_FALLBACKS，每个 host 最多 HOST_TRY_TIMEOUT_MS；
     * 连接异常 / 读超时 / HTTP 非 2xx（如 403）都视为该 host 不可用，
     * 立即换下一个。全部失败才回调错误。
     */
    fun download(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: (File?, String?) -> Unit,
    ) {
        Thread {
            val base = parse(url)
            if (base == null) {
                onDone(null, "下载地址无效：$url")
                return@Thread
            }
            val dir = context.getExternalFilesDir("updates")
                ?: File(context.filesDir, "updates").also { it.mkdirs() }
            val name = base.path.substringAfterLast('/').ifBlank { "update.apk" }
            val tmp = File(dir, "$name.part")
            val out = File(dir, name)

            val failures = StringBuilder()
            for (host in HOST_FALLBACKS) {
                val attemptUrl = withHost(base, host)
                // 每次尝试都从 0 开始上报，避免上一 host 的进度串味
                onProgress(0L, -1L)
                try {
                    val ok = downloadOnce(attemptUrl, tmp, out, onProgress)
                    if (ok) {
                        onDone(out, null)
                        return@Thread
                    }
                    failures.append("$host: HTTP/响应错误; ")
                } catch (t: Throwable) {
                    failures.append("$host: ${t.message ?: t.javaClass.simpleName}; ")
                }
            }
            // 全部失败：清掉半截文件，避免下次误当成功
            runCatching { tmp.delete() }
            onDone(null, "下载失败（已尝试 ${HOST_FALLBACKS.size} 个源）\n$failures")
        }.start()
    }

    /** 单 host 下载：成功返回 true；HTTP 非 2xx 抛异常，中断/超时也抛 */
    private fun downloadOnce(
        url: String,
        tmp: File,
        out: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            // 阶段一：5s 内完成连接并拿到响应码（含跟随 302 重定向）
            conn.connectTimeout = HOST_PROBE_TIMEOUT_MS
            conn.readTimeout = HOST_PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            // 走代理时部分服务端会因无 UA 而 403
            conn.setRequestProperty("User-Agent", "XDFHook-Updater")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            // 阶段二：host 已判定可用，放宽读超时再开始收数据，
            // 避免大文件/慢速下载因单次阻塞超过 5s 而断流。
            // HttpURLConnection 允许在 connect 后、getInputStream 前改 readTimeout。
            conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        onProgress(got, if (total > 0) total else -1)
                    }
                }
            }
            if (!tmp.renameTo(out)) {
                throw IllegalStateException("保存失败")
            }
            return true
        } finally {
            conn?.disconnect()
        }
    }

    /** 拆出 protocol/path/query（host 单独留待替换） */
    private class UrlParts(
        val protocol: String,
        val path: String,
        val query: String?,
    )

    private fun parse(url: String): UrlParts? = try {
        val u = URL(url)
        UrlParts(u.protocol, u.path ?: "/", u.query)
    } catch (_: Throwable) {
        null
    }

    /** 只换 host，其余原样拼接 */
    private fun withHost(parts: UrlParts, host: String): String = buildString {
        append(parts.protocol).append("://").append(host).append(parts.path)
        parts.query?.let { append('?').append(it) }
    }

    /**
     * root 静默安装：用 setsid+nohup 让 pm 脱离本进程组（本 App 被系统杀掉
     * 也不影响），结果写入日志文件并轮询确认。
     */
    fun installWithRoot(
        context: Context,
        apk: File,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        val dir = context.getExternalFilesDir("updates")
            ?: File(context.filesDir, "updates").also { it.mkdirs() }
        val log = File(dir, "install.log")
        log.delete()
        val cmd = "setsid nohup sh -c \"pm install -r -t --user 0 " +
            "'${apk.absolutePath}' > '${log.absolutePath}' 2>&1\" " +
            "</dev/null >/dev/null 2>&1 &"
        Thread {
            try {
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (_: Throwable) {
                // 启动失败也会体现在日志缺失/超时上
            }
            val deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                if (log.exists()) {
                    val txt = runCatching { log.readText() }.getOrDefault("")
                    if (txt.isNotBlank()) {
                        last = txt
                        if (txt.contains("Success")) {
                            post { onResult(true, "Success") }
                            return@Thread
                        }
                        if (txt.contains("Failure") || txt.contains("Error")) {
                            val line = txt.lineSequence().lastOrNull { it.isNotBlank() } ?: txt
                            post { onResult(false, line.take(300)) }
                            return@Thread
                        }
                    }
                }
                Thread.sleep(INSTALL_POLL_MS)
            }
            post { onResult(false, if (last.isBlank()) "安装超时" else last.take(300)) }
        }.start()
    }

    private fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}